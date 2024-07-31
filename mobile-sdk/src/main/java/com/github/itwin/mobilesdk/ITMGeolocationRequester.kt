/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
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
fun Context.checkFineLocationPermission() =
    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

/**
 * Encapsulate the requesting of location permission and services. Used automatically by
 * [ITMGeolocationManager], and only needed when the lifetime of [ITMGeolocationRequester] is longer
 * than a single Activity or Fragment.
 *
 * Since this class calls [ActivityResultCaller.registerForActivityResult], it *must* be constructed
 * unconditionally, as part of initialization path, typically as a field initializer of an Activity
 * or Fragment.
 *
 * @param resultCaller The [ActivityResultCaller] to associate with.
 */
internal class ITMGeolocationRequester private constructor(resultCaller: ActivityResultCaller) {
    private lateinit var context: Context
    private var customErrorHandler: ((Context, String) -> Unit)? = null

    private val requestPermission = resultCaller.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        requestPermissionsTask?.complete(isGranted)
        requestPermissionsTask = null
        if (!isGranted) {
            if (customErrorHandler != null) {
                customErrorHandler?.invoke(context, context.getString(R.string.itm_location_permissions_error_toast_text))
            } else {
                Toast.makeText(context, context.getString(R.string.itm_location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
            }
        }
    }

    private val requestLocationService = resultCaller.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        requestLocationServiceTask?.complete(it.resultCode == Activity.RESULT_OK)
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
    constructor(activity: ComponentActivity, customErrorHandler: ((Context, String) -> Unit)? = null) : this(activity as ActivityResultCaller) {
        this.context = activity
        this.customErrorHandler = customErrorHandler
        activity.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                unregister()
            }

            override fun onResume(owner: LifecycleOwner) {
                // WORKAROUND
                // When running in React Native, the ActivityResultLauncher callback doesn't happen. However, once the
                // user has made a choice, this onResume executes. So, if the task is non-null when we get here, pass
                // it a result based on the current state.
                requestPermissionsTask?.complete(context.checkFineLocationPermission())
                requestPermissionsTask = null
            }
        })
    }

    /**
     * Constructor using a [Fragment] as the as the [ActivityResultCaller], and the fragment's
     * activity or context will be used as the [Context].
     */
    constructor(fragment: Fragment, customErrorHandler: ((Context, String) -> Unit)? = null) : this(fragment as ActivityResultCaller) {
        this.customErrorHandler = customErrorHandler
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

    companion object {
        private fun createCheckLocationSettingsTask(context: Context): Task<LocationSettingsResponse> {
            // Call Activity overload if context is an Activity
            val settingsClient = (context as? Activity)?.let { LocationServices.getSettingsClient(it) } ?: LocationServices.getSettingsClient(context)
            val locationRequest = LocationRequest.Builder(1000).setPriority(Priority.PRIORITY_HIGH_ACCURACY).build()
            val settingsRequest = LocationSettingsRequest.Builder().addLocationRequest(locationRequest).build()
            return settingsClient.checkLocationSettings(settingsRequest)
        }

        /**
         * Check via [context] if location service is available and ask [resolver] to resolve
         * problems if the check throws an exception and [resolver] is non-null.
         */
        suspend fun isLocationServiceAvailable(context: Context, resolver: (suspend (intent: PendingIntent) -> Boolean)? = null) =
            try {
                createCheckLocationSettingsTask(context).await().locationSettingsStates?.isLocationUsable == true
            } catch (exception: ResolvableApiException) {
                resolver?.invoke(exception.resolution) ?: false
            } catch (exception: Exception) {
                false
            }

        /**
         * Ensure that location is available and throw an exception if it is not.
         */
        suspend fun ensureLocationAvailability(context: Context) {
            if (!context.checkFineLocationPermission())
                throwPermissionDeniedError()

            if (!isLocationServiceAvailable(context))
                throwServiceUnavailableError()
        }

        private fun throwServiceUnavailableError() {
            throw ITMGeolocationManager.GeolocationError(
                ITMGeolocationManager.GeolocationError.Code.POSITION_UNAVAILABLE,
                "Location service is not available"
            )
        }

        private fun throwPermissionDeniedError() {
            throw ITMGeolocationManager.GeolocationError(
                ITMGeolocationManager.GeolocationError.Code.PERMISSION_DENIED,
                "Location permission denied"
            )
        }
    }

    /**
     * Ensure that location is available and throw an exception if it is not. This may prompt the
     * user to allow location lookup.
     */
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

    private suspend fun isLocationServiceAvailable() =
        Companion.isLocationServiceAvailable(context) {
            tryResolveLocationServiceException(it)
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
