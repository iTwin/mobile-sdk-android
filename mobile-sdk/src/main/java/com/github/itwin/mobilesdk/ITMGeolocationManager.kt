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
import android.hardware.SensorManager.AXIS_MINUS_X
import android.hardware.SensorManager.AXIS_MINUS_Y
import android.hardware.SensorManager.AXIS_X
import android.hardware.SensorManager.AXIS_Y
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Surface.ROTATION_0
import android.view.Surface.ROTATION_90
import android.view.Surface.ROTATION_180
import android.view.Surface.ROTATION_270
import android.view.WindowManager
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
import kotlin.math.*


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
                "message" to message.orEmpty(),
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

    private val mainScope = MainScope()
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

    private val cancellationTokenSource = CancellationTokenSource()

    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private var otherSensor: Sensor? = null
    private var headingReading = FloatArray(0)
    private var rotationReading = FloatArray(0)
    private var accelerometerReading = FloatArray(0)
    private var magneticReading = FloatArray(0)
    private val magneticHeadings = mutableListOf<Double>()
    private var haveAccelerometerReading = false
    private var haveMagneticReading = false
    private var haveHeadingReading = false
    private var haveRotationReading = false
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
            when (event?.sensor?.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelerometerReading = event.values.copyOf()
                    haveAccelerometerReading = true
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magneticReading = event.values.copyOf()
                    haveMagneticReading = true
                    getHeading()?.let {
                        // Store all headings so that we can average them together each time we send
                        // an update to the web app.
                        mainScope.launch {
                            magneticHeadings += it
                        }
                    }
                }
                Sensor.TYPE_HEADING -> {
                    headingReading = event.values.copyOf()
                    haveHeadingReading = true
                }
                Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                    rotationReading = event.values.copyOf()
                    haveRotationReading = true
                }
            }
            val haveReading = haveAccelerometerReading && (haveMagneticReading || haveRotationReading || haveHeadingReading)
            if (!haveReading || watchIds.isEmpty() || watchTimerTask != null) {
                return
            }
            // Only update heading a maximum of 10 times per second.
            watchTimerTask = watchTimer.schedule(0, 100) {
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

    private fun getHeadingSensor(): Sensor? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // We don't have any test phones that return non-null for Sensor.TYPE_HEADING.
            // Until we do, we have no idea if our processing of the data is correct. So,
            // while the code to interpret the data from Sensor.TYPE_HEADING is being left
            // in the library, not returning the value makes sure that that data is never used.
            // Add return before the line below to activate usage of the heading sensor.
            sensorManager.getDefaultSensor(Sensor.TYPE_HEADING)
        }
        return null
    }

    private fun setupSensors() {
        if (listening) {
            return
        }
        if (accelerometerSensor == null) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        if (accelerometerSensor == null) {
            // All options require the accelerometer
            return
        }
        // NOTE: We only use one sensor (along with the accelerometer) to determine heading. We try
        // to create them in their preferred order: Sensor.TYPE_HEADING,
        // Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, and
        // Sensor.TYPE_MAGNETIC_FIELD. When we successfully create a sensor, we don't try to create
        // any more.
        if (otherSensor == null) {
            otherSensor = getHeadingSensor() ?:
                sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?:
                sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) ?:
                sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
        if (otherSensor == null) {
            // None of the sensors we support for heading is present.
            return
        }
        listening = true
        // Note: we don't really have any control over how often sensor data is reported. Even if we
        // request a specific time (instead of SENSOR_DELAY_UI), reports often happen much more
        // frequently than that. So a timer gets used in the callbacks to prevent a deluge of
        // updates from being sent to JS. The timer sends updates a maximum of 10 times per second.
        sensorManager.registerListener(sensorListener, accelerometerSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(sensorListener, otherSensor, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopSensors() {
        if (listening) {
            listening = false
            sensorManager.unregisterListener(sensorListener)
            watchTimerTask?.cancel()
            watchTimerTask = null
        }
    }

    private fun getDisplay() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(WindowManager::class.java)?.defaultDisplay
        }

    private fun getHeadingAxes(isUpsideDown: Boolean): Pair<Int, Int>? {
        // The following takes the device's current orientation into account.
        // Note that devices have a default orientation, and for tablets this is often landscape
        // while for phones it is portrait. This code works based off of the default orientation,
        // not the portrait or landscape status.
        val display = getDisplay()
        return if (display != null) {
            when (display.rotation) {
                ROTATION_0   -> if (isUpsideDown) Pair(AXIS_MINUS_X, AXIS_MINUS_Y) else Pair(AXIS_X, AXIS_Y)
                ROTATION_90  -> if (isUpsideDown) Pair(AXIS_MINUS_Y, AXIS_X) else Pair(AXIS_Y, AXIS_MINUS_X)
                ROTATION_180 -> if (isUpsideDown) Pair(AXIS_X, AXIS_Y) else Pair(AXIS_MINUS_X, AXIS_MINUS_Y)
                ROTATION_270 -> if (isUpsideDown) Pair(AXIS_Y, AXIS_MINUS_X) else Pair(AXIS_MINUS_Y, AXIS_X)
                else         -> null
            }
        } else {
            Pair(AXIS_X, AXIS_Y)
        }
    }

    // @TODO: Test with heading sensor
    private fun adjustHeadingForDisplayOrientation(angle: Double, isUpsideDown: Boolean): Double {
        val twoPi = 2.0 * Math.PI
        return (when (getDisplay()?.rotation ?: 0) {
            ROTATION_90  -> Math.PI / 2.0
            ROTATION_180 -> if (isUpsideDown) 0.0 else Math.PI
            ROTATION_270 -> 3.0 * Math.PI / 2.0
            else         -> if (isUpsideDown) Math.PI else 0.0
        } + angle + twoPi) % twoPi
    }

    // @TODO: Test on phone with heading sensor
    private fun getHeadingFromHeadingSensor(isUpsideDown: Boolean) =
        Math.toDegrees(adjustHeadingForDisplayOrientation(Math.toRadians(headingReading[0].toDouble()), isUpsideDown))

    private fun getHeadingFromRotationSensor(isUpsideDown: Boolean): Double? {
        val rotationMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationReading)
        return getHeadingFromRotationMatrix(rotationMatrix, isUpsideDown)
    }

    private fun getHeadingFromRotationMatrix(rotationMatrix: FloatArray, isUpsideDown: Boolean): Double? {
        val rotationMatrixB = FloatArray(9)
        val (axisX, axisY) = getHeadingAxes(isUpsideDown) ?: return null
        if (!SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, rotationMatrixB)) {
            return null
        }
        val orientationAngles = FloatArray(3)
        val orientation = SensorManager.getOrientation(rotationMatrixB, orientationAngles)
        return Math.toDegrees(orientation[0].toDouble())
    }

    private fun getHeadingFromMagneticSensor(isUpsideDown: Boolean): Double? {
        val rotationMatrix = FloatArray(9)
        if (!SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magneticReading)) {
            return null
        }
        return getHeadingFromRotationMatrix(rotationMatrix, isUpsideDown)
    }

    private fun clampHeading(heading: Double?): Double? = if (heading != null) {
        val clamp = heading % 360.0
        if (clamp < 0.0) {
            clamp + 360.0
        } else {
            clamp
        }
    } else {
        null
    }

    private fun getHeading(): Double? {
        if (!haveAccelerometerReading) {
            return null
        }
        val isUpsideDown: Boolean = accelerometerReading[2] < 0
        return clampHeading(if (haveHeadingReading) {
            getHeadingFromHeadingSensor(isUpsideDown)
        } else if (haveRotationReading) {
            getHeadingFromRotationSensor(isUpsideDown)
        } else if (haveMagneticReading) {
            getHeadingFromMagneticSensor(isUpsideDown)
        } else {
            null
        })
    }

    data class Coord2D(var x: Double, var y: Double) {
        companion object {
            fun fromDegrees(degrees: Double): Coord2D {
                val radians = Math.toRadians(degrees)
                return Coord2D(cos(radians), sin(radians))
            }
        }

        operator fun plus(other: Coord2D) = Coord2D(x + other.x, y + other.y)

        operator fun minus(other: Coord2D) = Coord2D(x - other.x, y - other.y)

        operator fun div(scale: Double) = Coord2D(x / scale, y / scale)

        operator fun div(scale: Int) = Coord2D(x / scale, y / scale)

        fun magnitude() = sqrt(x * x + y * y)
    }

    private fun getAveragedHeading(): Double? {
        // Average all our stored headings together to decrease the compass jitter.
        var coord = Coord2D(0.0, 0.0)
        // Convert all the headings from unit values in polar coordinates to rectangular
        // coordinates and add them together.
        magneticHeadings.forEach {
            coord += Coord2D.fromDegrees(it)
        }
        // Divide by magnitude to get a unit vector pointing in the right direction
        coord /= coord.magnitude()
        // Make sure our average coordinate is on the unit circle
        assert(abs(coord.magnitude() - 1.0) < 0.0001)
        // Convert back to polar coordinates
        magneticHeadings.clear()
        return clampHeading(Math.toDegrees(atan2(coord.y, coord.x)))
    }

    private fun updateWatchers(location: Location) {
        // Subsequent sensor updates need to send a heading update, and that requires a location to go with the heading.
        lastLocation = location
        mainScope.launch {
            val heading = if (magneticHeadings.isEmpty()) {
                getHeading()
            } else {
                getAveragedHeading()
            }
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
