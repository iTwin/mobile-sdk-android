/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts

open class ITMGeolocationFragment : Fragment() {
    private var geolocationManager: ITMGeolocationManager? = null

    val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            geolocationManager?.onLocationPermissionGranted()
        } else {
            geolocationManager?.onLocationPermissionDenied()
            Toast.makeText(requireContext(), getString(R.string.location_permissions_error_toast_text), Toast.LENGTH_LONG).show()
        }
    }

    val requestLocationService = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            geolocationManager?.onLocationServiceEnabled()
        } else {
            geolocationManager?.onLocationServiceEnableRequestDenied()
        }
    }

    @Suppress("unused")
    fun setGeolocationManager(geolocationManager: ITMGeolocationManager) {
        this.geolocationManager = geolocationManager
        geolocationManager.setGeolocationFragment(this)
    }

    override fun onStart() {
        super.onStart()
        geolocationManager?.resumeLocationUpdates()
    }

    override fun onStop() {
        super.onStop()
        geolocationManager?.stopLocationUpdates()
    }
}
