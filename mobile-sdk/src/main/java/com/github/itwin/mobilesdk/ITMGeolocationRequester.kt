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

/**
 * [Context] extension function that checks that ACCESS_FINE_LOCATION has been granted.
 *
 * Note: The function name starts with "check" and ends with "Permission" in order for the linter
 * to "recognize" that this is a permission check. I couldn't find a way to add the appropriate
 * attributes to the function instead of relying on this naming workaround.
 */
fun Context.checkFineLocationPermission(): Boolean {
    return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

/**
 * Encapsulates the requesting of location permission and services. Used automatically by
 * [ITMGeolocationManager], and only needed when the lifetime of [ITMGeolocationRequester] is longer
 * than a single Activity or Fragment.
 *
 * Since this class calls [ActivityResultCaller.registerForActivityResult], it *must* be constructed
 * unconditionally, as part of initialization path, typically as a field initializer of an Activity or Fragment.
 */
internal class ITMGeolocationRequester private constructor(resultCaller: ActivityResultCaller) {
    private lateinit var context: Context

    private var requestPermission = resultCaller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        requestPermissionsTask?.complete(isGranted)
        requestPermissionsTask = null
        if (!isGranted) {
            Toast.makeText(context, context.getString(R.string.itm_location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
        }
    }

    private var requestLocationService = resultCaller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        requestLocationServiceTask?.complete(activityResult.resultCode == Activity.RESULT_OK)
        requestLocationServiceTask = null
    }

    private var requestPermissionsTask: CompletableDeferred<Boolean>? = null
    private var requestLocationServiceTask: CompletableDeferred<Boolean>? = null

    private fun unregister() {
        requestPermission.unregister()
        requestLocationService.unregister()
    }

    /**
     * Constructor using a [ComponentActivity] as the [ActivityResultCaller] and [Context].
     */
    constructor(activity: ComponentActivity): this(activity as ActivityResultCaller) {
        this.context = activity
        activity.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                unregister()
            }
        })
    }

    /**
     * Constructor using a [Fragment] as the as the [ActivityResultCaller], and the fragment's
     * activity or context will be used as the [Context].
     */
    constructor(fragment: Fragment): this(fragment as ActivityResultCaller) {
        fragment.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                context = fragment.activity ?: fragment.requireContext()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                context = fragment.activity?.applicationContext ?: fragment.requireContext()
                unregister()
            }
        })
    }

    companion object Companion {
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

        suspend fun ensureLocationAvailability(context: Context) {
            if (!context.checkFineLocationPermission())
                throwPermissionDeniedError()

            if (!isLocationServiceAvailable(context))
                throwServiceUnavailableError()
        }

        private fun throwServiceUnavailableError() {
            throw ITMGeolocationManager.GeolocationError(ITMGeolocationManager.GeolocationError.Code.POSITION_UNAVAILABLE, "Location service is not available")
        }

        private fun throwPermissionDeniedError() {
            throw ITMGeolocationManager.GeolocationError(ITMGeolocationManager.GeolocationError.Code.PERMISSION_DENIED, "Location permission denied")
        }
    }

    suspend fun ensureLocationAvailability() {
        if (!requestLocationPermissionIfNeeded())
            throwPermissionDeniedError()

        if (!isLocationServiceAvailable())
            throwServiceUnavailableError()
    }

    private suspend fun requestLocationPermissionIfNeeded(): Boolean {
        if (context.checkFineLocationPermission()) {
            return true
        }

        val task = requestPermissionsTask ?: CompletableDeferred<Boolean>().also {
            requestPermissionsTask = it
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return task.await()
    }

    private suspend fun isLocationServiceAvailable(): Boolean {
        return Companion.isLocationServiceAvailable(context) {
            tryResolveLocationServiceException(it)
        }
    }

    private suspend fun tryResolveLocationServiceException(resolution: PendingIntent): Boolean {
        val task = requestLocationServiceTask ?: CompletableDeferred<Boolean>().also {
            try {
                requestLocationServiceTask = it
                requestLocationService.launch(IntentSenderRequest.Builder(resolution).build())
            } catch (sendException: IntentSender.SendIntentException) {
                return false
            }
        }
        return task.await()
    }
}
