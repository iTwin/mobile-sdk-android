/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.Manifest.permission.ACCESS_FINE_LOCATION
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
import androidx.activity.result.IntentSenderRequest
import androidx.core.app.ActivityCompat
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

// ITMGeolocationManager must be bound to ITMGeolocationFragment based fragment for showing permission and service dialogs and handling responses
// Bind using ITMGeolocationFragment's setGeolocationManager
class ITMGeolocationManager(private val appContext: Context, private val webView: WebView) {
    inner class GeolocationJsInterface {
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

        @JavascriptInterface
        fun clearWatch(positionId: Int) {
            watchIds.remove(positionId)
            if (watchIds.isEmpty()) {
                stopLocationUpdates()
            }
        }
    }

    data class GeolocationCoordinates(val latitude: Double, val longitude: Double, val accuracy: Double?, val heading: Double?)
    data class GeolocationPosition(val coords: GeolocationCoordinates)
    data class GeolocationRequestData(val positionId: String, val position: GeolocationPosition) {
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

    private var scope = MainScope()
    private var fragment: ITMGeolocationFragment? = null

    private var requestPermissionsTask: CompletableDeferred<Boolean>? = null
    private var requestLocationServiceTask: CompletableDeferred<Boolean>? = null

    private var fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)
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
            updateWatchers(locationResult.lastLocation)
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

    //region Lifecycle & events
    init {
        webView.addJavascriptInterface(GeolocationJsInterface(), "androidAppGeolocationInterface")
    }

    fun onLocationPermissionGranted() {
        requestPermissionsTask?.complete(true)
        requestPermissionsTask = null
    }

    fun onLocationPermissionDenied() {
        requestPermissionsTask?.complete(false)
        requestPermissionsTask = null
    }

    fun onLocationServiceEnabled() {
        requestLocationServiceTask?.complete(true)
        requestLocationServiceTask = null
    }

    fun onLocationServiceEnableRequestDenied() {
        requestLocationServiceTask?.complete(false)
        requestLocationServiceTask = null
    }

    //endregion

    //region public functions
    fun setGeolocationFragment(fragment: ITMGeolocationFragment?) {
        this.fragment = fragment
    }

    @Suppress("unused")
    fun cancelTasks() {
        scope.cancel()
        cancellationTokenSource.cancel()
        watchTimer.cancel()
        if (watchIds.isNotEmpty()) {
            stopLocationUpdates()
        }
    }

    fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(watchCallback)
        stopSensors()
    }

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
        if (ActivityCompat.checkSelfPermission(appContext, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            return true

        if (requestPermissionsTask == null) {
            requestPermissionsTask = CompletableDeferred()
            fragment?.requestPermission?.launch(ACCESS_FINE_LOCATION)
        }

        return requestPermissionsTask?.await() ?: false
    }

    private suspend fun isLocationServiceAvailable(): Boolean {
        try {
            val locationSettingsResponse = createCheckLocationSettingsTask().await()
            return locationSettingsResponse.locationSettingsStates?.isLocationUsable == true
        } catch (exception: Exception) {
            if (exception is ResolvableApiException) {
                return tryResolveLocationServiceException(exception.resolution)
            }
            return false
        }
    }

    private fun createCheckLocationSettingsTask(): Task<LocationSettingsResponse> {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
        val client: SettingsClient = LocationServices.getSettingsClient(appContext)
        return client.checkLocationSettings(builder.build())
    }

    private suspend fun tryResolveLocationServiceException(resolution: PendingIntent): Boolean {
        if (requestLocationServiceTask == null) {
            try {
                fragment?.let { fragment ->
                    val intentSenderRequest = IntentSenderRequest.Builder(resolution).build()
                    fragment.requestLocationService.launch(intentSenderRequest)
                }
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
        if (ActivityCompat.checkSelfPermission(appContext, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw GeolocationError(GeolocationError.Code.PERMISSION_DENIED, "Location permission denied")

        return fusedLocationClient.getCurrentLocation(LocationRequest.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token).await()
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
            sensorManager = webView.context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
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
        if (ActivityCompat.checkSelfPermission(appContext, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return

        setupSensors()
        if (watchLocationRequest == null) {
            watchLocationRequest = LocationRequest.create().apply {
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                interval = 1000 // 1 second
            }
        }
        watchLocationRequest?.let { locationRequest ->
            fusedLocationClient.requestLocationUpdates(locationRequest, watchCallback, Looper.getMainLooper())
        }
    }
    //endregion

    //region Response sending
    private fun sendPosition(position: GeolocationPosition, positionId: Int?, messageName: String) {
        val locationData = GeolocationRequestData(positionId.toString(), position).toJsonString()
        val encodedLocationData = Base64.encodeToString(locationData.toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocation('$messageName', '$encodedLocationData')"
        webView.evaluateJavascript(js, null)
    }

    private fun sendError(error: GeolocationError, positionId: Int, messageName: String) {
        val errorJson = jsonOf(
                "positionId" to positionId,
                "error" to error.toJson()
        )
        val errorResponse = Base64.encodeToString(errorJson.toString().toByteArray(), Base64.NO_WRAP)
        val js = "window.Bentley_ITMGeolocation('$messageName', '$errorResponse')"
        webView.evaluateJavascript(js, null)
    }
    //endregion
}
