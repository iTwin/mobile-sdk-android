/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.itwin.mobilesdk.jsonvalue.jsonOf
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.concurrent.schedule

/**
 * Class for the native-side implementation of a `navigator.geolocation` polyfill.
 *
 * @param context: The [Context] that is used for interacting with Android.
 */
class ITMGeolocationManager(private var context: Context) {
    private val geolocationJsInterface = object {
        @JavascriptInterface
        fun getCurrentPosition(positionId: Int) {
            mainScope.launch {
                try {
                    sendPosition(getGeolocationPosition(), positionId, "getCurrentPosition")
                } catch (error: GeolocationError) {
                    sendError(error, positionId, "getCurrentPosition")
                } catch (throwable: Throwable) {
                    val error = GeolocationError(GeolocationError.Code.POSITION_UNAVAILABLE, throwable.message)
                    sendError(error, positionId, "getCurrentPosition")
                }
            }
        }

        @JavascriptInterface
        fun watchPosition(positionId: Int) {
            mainScope.launch {
                try {
                    trackPosition(positionId)
                } catch (error: GeolocationError) {
                    sendError(error, positionId, "watchPosition")
                } catch (throwable: Throwable) {
                    val error = GeolocationError(GeolocationError.Code.POSITION_UNAVAILABLE, throwable.message)
                    sendError(error, positionId, "watchPosition")
                }
            }
        }

        @JavascriptInterface
        fun clearWatch(positionId: Int) {
            watchIds.remove(positionId)
            if (watchIds.isEmpty()) {
                stopLocationUpdates()
            }
        }
    }

    private data class GeolocationCoordinates(val latitude: Double, val longitude: Double, val accuracy: Double?, val heading: Double?)
    private data class GeolocationPosition(val coords: GeolocationCoordinates, val timestamp: Long)
    private data class GeolocationRequestData(val positionId: Int, val position: GeolocationPosition) {
        fun toJsonString(): String = Gson().toJson(this)
    }

    /**
     * [Exception] class to use for all exceptions that are sent back to JavaScript.
     *
     * @param code: The [Code] to use for this error.
     * @param message: The optional message to show to the user.
     */
    class GeolocationError(private val code: Code, message: String?) : Exception(message) {
        /**
         * The error code for a [GeolocationError].
         */
        enum class Code(val value: Int) {
            PERMISSION_DENIED(1),
            POSITION_UNAVAILABLE(2),
            TIMEOUT(3),
        }

        /**
         * Convert this [GeolocationError] to JSON compatible with the `GeolocationPositionError`
         * JavaScript type.
         */
        fun toJSON() =
            jsonOf(
                "code" to code.value,
                "message" to (message ?: ""),
                "PERMISSION_DENIED" to Code.PERMISSION_DENIED.value,
                "POSITION_UNAVAILABLE" to Code.POSITION_UNAVAILABLE.value,
                "TIMEOUT" to Code.TIMEOUT.value,
            )
    }

    private fun Location.toGeolocationPosition(heading: Double? = null): GeolocationPosition {
        val coordinates = GeolocationCoordinates(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null, heading)
        return GeolocationPosition(coordinates, time)
    }

    /** The [WebView] to receive location updates via javascript. */
    var webView: WebView? = null
        set(value) {
            field = value
            field?.addJavascriptInterface(geolocationJsInterface, "Bentley_ITMGeolocation")
        }

    private var mainScope = MainScope()
    private var requester: ITMGeolocationRequester? = null
    private var lastLocationTimeThresholdMillis = 0L

    /**
     * Sets the threshold when "last location" is used in location requests instead of requesting a
     * new location. The default of 0 means that all location requests ask for the location (which
     * can take time).
     *
     * @param value The threshold value (in milliseconds)
     */
    fun setLastLocationTimeThreshold(value: Long) {
        lastLocationTimeThresholdMillis = value
    }

    /**
     * Adds a lifecycle observer that does the following:
     * - onStop: stops location updates
     * - onStart: resumes location updates
     * - onDestroy: clears [requester]
     *
     * @param owner The [LifecycleOwner] to observe.
     */
    fun addLifecycleObserver(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                resumeLocationUpdates()
                (owner as? Fragment)?.let {
                    context = it.activity ?: it.requireContext()
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                stopLocationUpdates()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                requester = null
                (owner as? Fragment)?.let {
                    context = it.activity?.applicationContext ?: it.requireContext()
                }
            }
        })
    }

    /**
     * Associates with the given activity.
     *
     * @param activity The [ComponentActivity] to associate with.
     */
    fun associateWithActivity(activity: ComponentActivity) {
        context = activity
        requester = ITMGeolocationRequester(activity)
        addLifecycleObserver(activity)
    }

    /**
     * Associates with the given fragment.
     *
     * @param fragment The [Fragment] to associate with.
     */
    fun associateWithFragment(fragment: Fragment) {
        requester = ITMGeolocationRequester(fragment)
        addLifecycleObserver(fragment)
    }

    private fun getFusedLocationProviderClient() =
        // Prefer the Activity-based location client over the generic context one
        if (context is Activity) {
            LocationServices.getFusedLocationProviderClient(context as Activity)
        } else {
            LocationServices.getFusedLocationProviderClient(context)
        }

    private val fusedLocationClient by lazy { getFusedLocationProviderClient() }

    private var cancellationTokenSource = CancellationTokenSource()

    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private val accelerometerReading = FloatArray(3)
    private val magneticReading = FloatArray(3)
    private var haveAccelerometerReading = false
    private var haveMagneticReading = false
    private var listening = false

    private val watchIds: MutableSet<Int> = mutableSetOf()
    private val watchLocationRequest by lazy { LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build() }
    private var lastLocation: Location? = null
    private val watchTimer = Timer("GeolocationWatch")
    private var watchTimerTask: TimerTask? = null
    private val watchCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            locationResult.lastLocation?.let { updateWatchers((it)) }
        }
    }
    private val sensorListener = object : SensorEventListener {
        @Suppress("EmptyFunctionBlock")
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                return
            }
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, accelerometerReading, 0, accelerometerReading.size)
                haveAccelerometerReading = true
            } else if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
                System.arraycopy(event.values, 0, magneticReading, 0, magneticReading.size)
                haveMagneticReading = true
            }
            if (!haveAccelerometerReading || !haveMagneticReading || watchIds.isEmpty() || watchTimerTask != null) {
                return
            }
            // Only update heading a maximum of 4 times per second.
            watchTimerTask = watchTimer.schedule(0, 250) {
                lastLocation?.let {
                    updateWatchers(it)
                }
            }
        }
    }

    //region Construction

    /**
     * Constructor using a [ComponentActivity].
     *
     * @param activity The [ComponentActivity] to associate with this instance.
     */
    constructor(activity: ComponentActivity): this(activity as Context) {
        associateWithActivity(activity)
    }

    /**
     * Constructor using a [Fragment].
     *
     * @param fragment The [Fragment] to associate with this instance.
     * @param context The application [Context] to use when the fragment has not started.
     */
    constructor(fragment: Fragment, context: Context): this(context) {
        associateWithFragment(fragment)
    }

    //endregion

    //region public functions

    /**
     * Cancel all outstanding tasks, including any active watches.
     */
    fun cancelTasks() {
        mainScope.cancel()
        cancellationTokenSource.cancel()
        watchTimer.cancel()
        if (watchIds.isNotEmpty()) {
            stopLocationUpdates()
        }
    }

    /**
     * Stop all watches. Uses [resumeLocationUpdates] to resume.
     */
    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(watchCallback)
        stopSensors()
    }

    /**
     * Resume watches stopped by [stopLocationUpdates].
     */
    fun resumeLocationUpdates() {
        if (watchIds.isNotEmpty())
            requestLocationUpdates()
    }
    //endregion

    //region Location

    private suspend fun ensureLocationAvailability() {
        // If we have a requester, use it to ensure location availability, otherwise use the static
        // method. The latter can't make permission requests but will check if the location permission
        // and service are present.
        requester?.ensureLocationAvailability() ?: ITMGeolocationRequester.ensureLocationAvailability(context)
    }

    /**
     * Ensure location permission and services are available and return the current location.
     *
     * @return The current [Location].
     */
    suspend fun getGeolocation(): Location {
        ensureLocationAvailability()
        return getCurrentLocation()
    }

    private suspend fun getGeolocationPosition() = getGeolocation().toGeolocationPosition()

    @RequiresPermission(anyOf = ["android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"])
    private suspend fun getRecentLastLocation() =
        lastLocationTimeThresholdMillis.takeIf { it > 0 }?.let {
            val lastLocation = fusedLocationClient.lastLocation.await()
            val elapsedMax = it * 1000000
            val elapsed = SystemClock.elapsedRealtimeNanos() - lastLocation.elapsedRealtimeNanos
            lastLocation.takeIf { elapsed in (0 until elapsedMax) }
        }

    private suspend fun getCurrentLocation() =
        if (!context.checkFineLocationPermission()) {
            throw GeolocationError(
                GeolocationError.Code.PERMISSION_DENIED,
                "Location permission denied"
            )
        } else {
            getRecentLastLocation() ?: fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationTokenSource.token
            ).await()
        }
    //endregion

    //region Position tracking
    private suspend fun trackPosition(positionId: Int) {
        ensureLocationAvailability()
        watchIds.add(positionId)
        if (watchIds.size == 1) {
            requestLocationUpdates()
        }
    }

    private fun setupSensors() {
        if (accelerometerSensor == null) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
        if (listening) {
            return
        }
        letAll(accelerometerSensor, magneticSensor) { accelerometerSensor, magneticSensor ->
            // Note: even though we ask for updates only every 250,000 microseconds (4 times per second), we get updates a LOT more
            // frequently than that. So a timer gets used in the callbacks to prevent a deluge of updates from being sent to JS.
            sensorManager.registerListener(sensorListener, accelerometerSensor, 250000, 250000)
            sensorManager.registerListener(sensorListener, magneticSensor, 250000, 250000)
            listening = true
        }
    }

    private fun stopSensors() {
        if (listening) {
            listening = false
            sensorManager.unregisterListener(sensorListener)
            watchTimerTask?.cancel()
            watchTimerTask = null
        }
    }

    private fun getHeading(): Double? {
        if (!haveAccelerometerReading || !haveMagneticReading) {
            return null
        }
        val rotationMatrixA = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotationMatrixA, null, accelerometerReading, magneticReading)) {
            return null
        }
        val rotationMatrixB = FloatArray(9)
        // The following takes the device's current orientation (portrait, landscape) into account.
        if (!SensorManager.remapCoordinateSystem(rotationMatrixA, SensorManager.AXIS_X, SensorManager.AXIS_Z, rotationMatrixB)) {
            return null
        }
        val orientationAngles = FloatArray(3)
        val orientation = SensorManager.getOrientation(rotationMatrixB, orientationAngles)
        return (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
    }

    private fun updateWatchers(location: Location) {
        // Subsequent sensor updates need to send a heading update, and that requires a location to go with the heading.
        lastLocation = location
        val heading = getHeading()
        mainScope.launch {
            for (watchId in watchIds) {
                sendPosition(location.toGeolocationPosition(heading), watchId, "watchPosition")
            }
        }
    }

    private fun requestLocationUpdates() {
        if (!context.checkFineLocationPermission())
            return

        setupSensors()
        fusedLocationClient.requestLocationUpdates(watchLocationRequest, watchCallback, Looper.getMainLooper())
    }
    //endregion

    //region Response sending
    private fun sendPosition(position: GeolocationPosition, positionId: Int, messageName: String) {
        val webView = this.webView ?: return
        val locationData = GeolocationRequestData(positionId, position).toJsonString()
        val encodedLocationData = Base64.encodeToString(locationData.toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocationResponse('$messageName', '$encodedLocationData')"
        webView.evaluateJavascript(js, null)
    }

    private fun sendError(error: GeolocationError, positionId: Int, messageName: String) {
        val webView = this.webView ?: return
        val errorJson = jsonOf(
            "positionId" to positionId,
            "error" to error.toJSON()
        )
        val errorResponse = Base64.encodeToString(errorJson.toString().toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocationResponse('$messageName', '$errorResponse')"
        webView.evaluateJavascript(js, null)
    }
    //endregion
}
