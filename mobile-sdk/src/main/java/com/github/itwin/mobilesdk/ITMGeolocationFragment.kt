/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.annotation.SuppressLint
import android.app.Activity
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts

/**
 * [Fragment] used by [ITMGeolocationManager] to present location access permissions requests to the user.
 */
open class ITMGeolocationFragment : Fragment() {
    init {
        @Suppress("LeakingThis")
        geolocationManager?.setGeolocationFragment(this)
    }

    companion object {
        // Because clearGeolocationManager is called from ITMApplication.onActivityDestroy, the following
        // isn't really leaking, despite the lint warning that we have to suppress.
        /**
         * The [ITMGeolocationManager] to which [ITMGeolocationFragment] instances attach.
         */
        @SuppressLint("StaticFieldLeak")
        private var geolocationManager: ITMGeolocationManager? = null

        /**
         * Create an [ITMGeolocationFragment] connected to the given [ITMGeolocationManager].
         *
         * __Note:__ [geolocationManager] is stored in the companion object so that any [ITMGeolocationFragment]
         * objects created during the application life cycle will have access to it.
         *
         * @param geolocationManager The [ITMGeolocationManager] with which the new fragment is associated.
         * @return An [ITMGeolocationFragment] connected to [geolocationManager].
         */
        fun newInstance(geolocationManager: ITMGeolocationManager): ITMGeolocationFragment {
            ITMGeolocationFragment.geolocationManager = geolocationManager
            return ITMGeolocationFragment()
        }

        /**
         * Clear the active [geolocationManager] that is used when fragments are created.
         */
        fun clearGeolocationManager() {
            geolocationManager = null
        }
    }

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
            Toast.makeText(requireContext(), getString(R.string.itm_location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
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

    /**
     * Removes this fragment from [geolocationManager].
     */
    override fun onDestroy() {
        super.onDestroy()
        geolocationManager?.setGeolocationFragment(null)
    }
}
