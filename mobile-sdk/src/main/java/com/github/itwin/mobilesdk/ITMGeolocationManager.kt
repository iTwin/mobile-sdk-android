/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.eclipsesource.json.JsonObject
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
 */
class ITMGeolocationManager(private var context: Context) {
    private val geolocationJsInterface = object {
        @Suppress("unused")
        @JavascriptInterface
        fun getCurrentPosition(positionId: Int) {
            scope.launch {
                try {
                    sendPosition(getGeolocationPosition(), positionId, "getCurrentPosition")
                } catch (error: GeolocationError) {
                    sendError(error, positionId, "getCurrentPosition")
                } catch (throwable: Throwable) {
                    sendError(GeolocationError(GeolocationError.Code.POSITION_UNAVAILABLE, throwable.message), positionId, "getCurrentPosition")
                }
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun watchPosition(positionId: Int) {
            scope.launch {
                try {
                    trackPosition(positionId)
                } catch (error: GeolocationError) {
                    sendError(error, positionId, "watchPosition")
                } catch (throwable: Throwable) {
                    sendError(GeolocationError(GeolocationError.Code.POSITION_UNAVAILABLE, throwable.message), positionId, "watchPosition")
                }
            }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun clearWatch(positionId: Int) {
            watchIds.remove(positionId)
            if (watchIds.isEmpty()) {
                stopLocationUpdates()
            }
        }
    }

    private data class GeolocationCoordinates(val latitude: Double, val longitude: Double, val accuracy: Double?, val heading: Double?)
    private data class GeolocationPosition(val coords: GeolocationCoordinates)
    private data class GeolocationRequestData(val positionId: String, val position: GeolocationPosition) {
        fun toJsonString(): String {
            return Gson().toJson(this)
        }
    }

    class GeolocationError(private val code: Code, message: String?) : Throwable(message) {
        enum class Code(val value: Int) {
            PERMISSION_DENIED(1),
            POSITION_UNAVAILABLE(2),
            TIMEOUT(3),
        }

        fun toJson(): JsonObject {
            return jsonOf(
                "code" to code.value,
                "message" to (message ?: ""),
                "PERMISSION_DENIED" to Code.PERMISSION_DENIED.value,
                "POSITION_UNAVAILABLE" to Code.POSITION_UNAVAILABLE.value,
                "TIMEOUT" to Code.TIMEOUT.value,
            )
        }
    }

    private fun Location.toGeolocationPosition(heading: Double? = null): GeolocationPosition {
        val coordinates = GeolocationCoordinates(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null, heading)
        return GeolocationPosition(coordinates)
    }

    /** The [WebView] to receive location updates via javascript. */
    var webView: WebView? = null
        set(value) {
            field = value
            field?.addJavascriptInterface(geolocationJsInterface, "Bentley_ITMGeolocation")
        }

    private var scope = MainScope()
    private var requester: ITMGeolocationRequester? = null

    /**
     * Adds a lifecycle observer that does the following:
     * - onStart: resumes location updates
     * - onStop: stops location updates
     * - onDestroy: clears [requester]
     *
     * @param owner The [LifecycleOwner] to observe.
     */
    @Suppress("private")
    fun addLifecycleObserver(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                resumeLocationUpdates()
                (owner as? Fragment)?.let {fragment ->
                    context = fragment.activity ?: fragment.requireContext()
                }
            }
            override fun onStop(owner: LifecycleOwner) {
                stopLocationUpdates()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                requester = null
                (owner as? Fragment)?.let {fragment ->
                    context = fragment.activity?.applicationContext ?: fragment.requireContext()
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
     * @param appContext The application [Context] to use when the fragment has not been created or is destroyed.
     */
    @Suppress("private")
    fun associateWithFragment(fragment: Fragment, appContext: Context) {
        context = appContext
        requester = ITMGeolocationRequester(fragment)
        addLifecycleObserver(fragment)
    }

    private fun getFusedLocationProviderClient(): FusedLocationProviderClient {
        // Prefer the Activity-based location client over the generic context one
        return if (context is Activity) {
            LocationServices.getFusedLocationProviderClient(context as Activity)
        } else {
            LocationServices.getFusedLocationProviderClient(context)
        }
    }

    private var _fusedLocationClient: FusedLocationProviderClient? = null
    private val fusedLocationClient: FusedLocationProviderClient
        get() {
            val client = _fusedLocationClient ?: getFusedLocationProviderClient()
            _fusedLocationClient = client
            return client
        }

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
    private var watchLocationRequest: LocationRequest? = null
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
                lastLocation?.let { lastLocation ->
                    updateWatchers(lastLocation)
                }
            }
        }
    }

    //region Construction

    /**
     * Constructor using a [ComponentActivity].
     */
    @Suppress("unused")
    constructor(activity: ComponentActivity): this(activity as Context) {
        associateWithActivity(activity)
    }

    /**
     * Constructor using a [Fragment].
     */
    @Suppress("unused")
    constructor(fragment: Fragment, context: Context): this(context) {
        associateWithFragment(fragment, context)
    }


    /**
     * Constructor using a [WebView]. Intended for use when when the lifetime of the instance
     * will outlive activities or fragments.
     */
    @Suppress("unused")
    constructor(webView: WebView): this(webView.context)

    //endregion

    //region public functions

    /**
     * Cancel all outstanding tasks, including any active watches.
     */
    @Suppress("unused")
    fun cancelTasks() {
        scope.cancel()
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
     * Ensures location permission and services are available and returns the current location.
     *
     * @return The current [Location].
     */
    @Suppress("unused")
    suspend fun getGeolocation(): Location {
        ensureLocationAvailability()
        return getCurrentLocation()
    }

    private suspend fun getGeolocationPosition(): GeolocationPosition {
        return getGeolocation().toGeolocationPosition()
    }

    private suspend fun getCurrentLocation(): Location {
        if (!context.checkFineLocationPermission())
            throw GeolocationError(GeolocationError.Code.PERMISSION_DENIED, "Location permission denied")

        return fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token).await()
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
        accelerometerSensor?.let { accelerometerSensor ->
            magneticSensor?.let { magneticSensor ->
                // Note: even though we ask for updates only every 250,000 microseconds (4 times per second), we get updates a LOT more
                // frequently than that. So a timer gets used in the callbacks to prevent a deluge of updates from being sent to JS.
                sensorManager.registerListener(sensorListener, accelerometerSensor, 250000, 250000)
                sensorManager.registerListener(sensorListener, magneticSensor, 250000, 250000)
                listening = true
            }
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
        scope.launch {
            for (watchId in watchIds) {
                sendPosition(location.toGeolocationPosition(heading), watchId, "watchPosition")
            }
        }
    }

    private fun requestLocationUpdates() {
        if (!context.checkFineLocationPermission())
            return

        setupSensors()
        val locationRequest = watchLocationRequest ?: LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build().also {
            watchLocationRequest = it
        }
        fusedLocationClient.requestLocationUpdates(locationRequest, watchCallback, Looper.getMainLooper())
    }
    //endregion

    //region Response sending
    private fun sendPosition(position: GeolocationPosition, positionId: Int?, messageName: String) {
        val webView = this.webView ?: return
        val locationData = GeolocationRequestData(positionId.toString(), position).toJsonString()
        val encodedLocationData = Base64.encodeToString(locationData.toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocationResponse('$messageName', '$encodedLocationData')"
        webView.evaluateJavascript(js, null)
    }

    private fun sendError(error: GeolocationError, positionId: Int, messageName: String) {
        val webView = this.webView ?: return
        val errorJson = jsonOf(
            "positionId" to positionId,
            "error" to error.toJson()
        )
        val errorResponse = Base64.encodeToString(errorJson.toString().toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocationResponse('$messageName', '$errorResponse')"
        webView.evaluateJavascript(js, null)
    }
    //endregion
}
