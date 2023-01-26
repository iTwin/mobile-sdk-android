/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.eclipsesource.json.JsonObject
import com.github.itwin.mobilesdk.jsonvalue.jsonOf
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*
import kotlin.concurrent.schedule

/**
 * Class for the native-side implementation of a `navigator.geolocation` polyfill.
 *
 * __Note:__ [ITMGeolocationManager] must be bound to a [ActivityResultCaller] for showing permission
 * and service dialogs and handling responses.
 */
class ITMGeolocationManager(resultCaller: ActivityResultCaller, owner: LifecycleOwner, private val context: Context) {
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

    private class GeolocationError(private val code: Code, message: String?) : Throwable(message) {
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

    var webView: WebView? = null
        set(value) {
            field = value
            field?.addJavascriptInterface(geolocationJsInterface, "Bentley_ITMGeolocation")
        }

    private var scope = MainScope()

    private val requestPermission = resultCaller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            onLocationPermissionGranted()
        } else {
            onLocationPermissionDenied()
            Toast.makeText(context, context.getString(R.string.itm_location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
        }
    }

    private val requestLocationService = resultCaller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            onLocationServiceEnabled()
        } else {
            onLocationServiceEnableRequestDenied()
        }
    }

    private var requestPermissionsTask: CompletableDeferred<Boolean>? = null
    private var requestLocationServiceTask: CompletableDeferred<Boolean>? = null

    private var fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
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
    constructor(activity: ComponentActivity): this(activity, activity, activity)

    /**
     * Constructor using a [Fragment].
     */
    @Suppress("unused")
    constructor(fragment: Fragment): this(fragment, fragment, fragment.requireContext())

    init {
        owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                resumeLocationUpdates()
            }
            override fun onStop(owner: LifecycleOwner) {
                stopLocationUpdates()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                requestPermission.unregister()
                requestLocationService.unregister()
            }
        })
    }

    //endregion

    //region Lifecycle & events

    /**
     * Called when the user grants location permission.
     */
    private fun onLocationPermissionGranted() {
        requestPermissionsTask?.complete(true)
        requestPermissionsTask = null
    }

    /**
     * Called when the user denies location permission.
     */
    private fun onLocationPermissionDenied() {
        requestPermissionsTask?.complete(false)
        requestPermissionsTask = null
    }

    /**
     * Called when the app enables location service.
     */
    private fun onLocationServiceEnabled() {
        requestLocationServiceTask?.complete(true)
        requestLocationServiceTask = null
    }

    /**
     * Called when the app denies location service.
     */
    private fun onLocationServiceEnableRequestDenied() {
        requestLocationServiceTask?.complete(false)
        requestLocationServiceTask = null
    }

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

    //region location permissions and service access
    private suspend fun ensureLocationAvailability() {
        if (!requestLocationPermissionIfNeeded())
            throw GeolocationError(GeolocationError.Code.PERMISSION_DENIED, "Location permission denied")

        if (!isLocationServiceAvailable())
            throw GeolocationError(GeolocationError.Code.POSITION_UNAVAILABLE, "Location service is not available")
    }

    private suspend fun requestLocationPermissionIfNeeded(): Boolean {
        if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return true

        if (requestPermissionsTask == null) {
            requestPermissionsTask = CompletableDeferred()
            requestPermission.launch(ACCESS_FINE_LOCATION)
        }

        return requestPermissionsTask?.await() ?: false
    }

    private suspend fun isLocationServiceAvailable(): Boolean {
        return try {
            val locationSettingsResponse = createCheckLocationSettingsTask().await()
            locationSettingsResponse.locationSettingsStates?.isLocationUsable == true
        } catch (exception: ResolvableApiException) {
            tryResolveLocationServiceException(exception.resolution)
        } catch (exception: Exception) {
            false
        }
    }

    private fun createCheckLocationSettingsTask(): Task<LocationSettingsResponse> {
        val locationRequest = LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
        val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
        return LocationServices.getSettingsClient(context).checkLocationSettings(settingsRequest)
    }

    private suspend fun tryResolveLocationServiceException(resolution: PendingIntent): Boolean {
        if (requestLocationServiceTask == null) {
            try {
                requestLocationService.launch(IntentSenderRequest.Builder(resolution).build())
                requestLocationServiceTask = CompletableDeferred()
            } catch (sendException: IntentSender.SendIntentException) {
                return false
            }
        }

        return requestLocationServiceTask?.await() ?: false
    }
    //endregion

    //region Location
    private suspend fun getGeolocationPosition(): GeolocationPosition {
        ensureLocationAvailability()
        return getCurrentLocation().toGeolocationPosition()
    }

    private suspend fun getCurrentLocation(): Location {
        if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
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
        if (ActivityCompat.checkSelfPermission(context, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return

        setupSensors()
        if (watchLocationRequest == null) {
            watchLocationRequest = LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
        }
        watchLocationRequest?.let { locationRequest ->
            fusedLocationClient.requestLocationUpdates(locationRequest, watchCallback, Looper.getMainLooper())
        }
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
