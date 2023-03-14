package com.github.itwin.mobilesdk

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

class ITMGeolocationRequester private constructor(resultCaller: ActivityResultCaller) {
    private lateinit var context: Context

    private var requestPermission: ActivityResultLauncher<String> = resultCaller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        requestPermissionsTask?.complete(isGranted)
        requestPermissionsTask = null
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.itm_location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
        }
    }

    private var requestLocationService: ActivityResultLauncher<IntentSenderRequest> = resultCaller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        requestLocationServiceTask?.complete(activityResult.resultCode == Activity.RESULT_OK)
        requestLocationServiceTask = null
    }

    private var requestPermissionsTask: CompletableDeferred<Boolean>? = null
    private var requestLocationServiceTask: CompletableDeferred<Boolean>? = null

    constructor(activity: ComponentActivity): this(activity as ActivityResultCaller) {
        this.context = activity
    }

    constructor(fragment: Fragment): this(fragment as ActivityResultCaller) {
        fragment.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                context = fragment.activity ?: fragment.requireContext()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                context = fragment.activity?.applicationContext ?: fragment.requireContext()
            }
        })
    }

    companion object Companion {
        fun isFineLocationPermissionGranted(context: Context): Boolean {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

        private fun createCheckLocationSettingsTask(context: Context): Task<LocationSettingsResponse> {
            // Call Activity overload if context is an Activity
            val settingsClient = (context as? Activity)?.let { LocationServices.getSettingsClient(it) } ?: LocationServices.getSettingsClient(context)
            val locationRequest = LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
            val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
            return settingsClient.checkLocationSettings(settingsRequest)
        }

        suspend fun isLocationServiceAvailable(context: Context, resolver: (suspend (intent: PendingIntent) -> Boolean)? = null): Boolean {
            return try {
                createCheckLocationSettingsTask(context).await().locationSettingsStates?.isLocationUsable == true
            } catch (exception: ResolvableApiException) {
                resolver?.invoke(exception.resolution) ?: false
            } catch (exception: Exception) {
                false
            }
        }

        suspend fun isPermissionGrantedAndServiceAvailable(context: Context): Boolean {
            return isFineLocationPermissionGranted(context) && isLocationServiceAvailable(context)
        }
    }

    suspend fun ensureLocationAvailability() {
        if (!requestLocationPermissionIfNeeded())
            throw ITMGeolocationManager.GeolocationError(ITMGeolocationManager.GeolocationError.Code.PERMISSION_DENIED, "Location permission denied")

        if (!isLocationServiceAvailable())
            throw ITMGeolocationManager.GeolocationError(ITMGeolocationManager.GeolocationError.Code.POSITION_UNAVAILABLE, "Location service is not available")
    }

    private suspend fun requestLocationPermissionIfNeeded(): Boolean {
        if (isFineLocationPermissionGranted(context)) {
            return true
        }

        if (requestPermissionsTask == null) {
            requestPermissionsTask = CompletableDeferred()
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return requestPermissionsTask?.await() ?: false
    }

    private suspend fun isLocationServiceAvailable(): Boolean {
        return Companion.isLocationServiceAvailable(context) {
            tryResolveLocationServiceException(it)
        }
    }

    private suspend fun tryResolveLocationServiceException(resolution: PendingIntent): Boolean {
        if (requestLocationServiceTask == null) {
            try {
                requestLocationServiceTask = CompletableDeferred()
                requestLocationService.launch(IntentSenderRequest.Builder(resolution).build())
            } catch (sendException: IntentSender.SendIntentException) {
                return false
            }
        }
        return requestLocationServiceTask?.await() ?: false
    }
}
