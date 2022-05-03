/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts

/**
 * [Fragment] used by [ITMGeolocationManager] to present location access permissions requests to the user.
 */
open class ITMGeolocationFragment : Fragment() {
    private var geolocationManager: ITMGeolocationManager? = null

    /**
     * [ActivityResultLauncher][androidx.activity.result.ActivityResultLauncher] used to request location permissions.
     *
     * If you override this value, your launcher must call [onLocationPermissionGranted][ITMGeolocationManager.onLocationPermissionGranted]
     * or [onLocationPermissionDenied][ITMGeolocationManager.onLocationPermissionDenied] on [geolocationManager].
     */
    val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            geolocationManager?.onLocationPermissionGranted()
        } else {
            geolocationManager?.onLocationPermissionDenied()
            Toast.makeText(requireContext(), getString(R.string.location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * [ActivityResultLauncher][androidx.activity.result.ActivityResultLauncher] used to request location tracking.
     *
     * If you override this value, your launcher must call [onLocationServiceEnabled][ITMGeolocationManager.onLocationServiceEnabled]
     * or [onLocationServiceEnableRequestDenied][ITMGeolocationManager.onLocationServiceEnableRequestDenied] on [geolocationManager].
     */
    val requestLocationService = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            geolocationManager?.onLocationServiceEnabled()
        } else {
            geolocationManager?.onLocationServiceEnableRequestDenied()
        }
    }

    /**
     * Set the [ITMGeolocationManager] to which this fragment is attached.
     */
    @Suppress("unused")
    fun setGeolocationManager(geolocationManager: ITMGeolocationManager) {
        this.geolocationManager = geolocationManager
        geolocationManager.setGeolocationFragment(this)
    }

    /**
     * Calls [geolocationManager].[resumeLocationUpdates][ITMGeolocationManager.resumeLocationUpdates].
     */
    override fun onStart() {
        super.onStart()
        geolocationManager?.resumeLocationUpdates()
    }

    /**
     * Calls [geolocationManager].[stopLocationUpdates][ITMGeolocationManager.stopLocationUpdates].
     */
    override fun onStop() {
        super.onStop()
        geolocationManager?.stopLocationUpdates()
    }
}
