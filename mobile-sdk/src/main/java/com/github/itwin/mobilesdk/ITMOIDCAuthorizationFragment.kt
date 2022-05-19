/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.AlertDialog
import android.os.Build
import androidx.fragment.app.Fragment
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * [Fragment] used to present the signin UI for [ITMOIDCAuthorizationClient].
 *
 * @param oidcClient The [ITMOIDCAuthorizationClient] with which this fragment is associated.
 */
open class ITMOIDCAuthorizationFragment(private val oidcClient: ITMOIDCAuthorizationClient): ITMAuthorizationFragment(oidcClient) {
    /**
     * Coroutine to present a signin UI to the user and return an access token. If a cached token
     * exists, that should be directly returned instead of asking the user to sign in again.
     */
    override suspend fun getAccessToken(): AccessToken {
        return suspendCoroutine { continuation ->
            if (haveCachedToken()) {
                continuation.resume(cachedToken!!)
            } else {
                with(AlertDialog.Builder(context)) {
                    setTitle("Auth Stub")
                    setMessage("issuerUrl: " + oidcClient.authSettings.issuerUrl)
                    setPositiveButton("OK") { _, _ ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val expireInUtc = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1)
                            cachedToken = AccessToken(
                                "Bearer <TOKEN GOES HERE>",
                                expireInUtc.format(DateTimeFormatter.ISO_DATE_TIME)
                            )
                            continuation.resume(cachedToken!!)
                        } else {
                            continuation.resume(AccessToken())
                        }
                    }
                    setOnCancelListener {
                        continuation.resume(AccessToken())
                    }
                    show()
                }
            }
        }
    }

    /**
     * Clean up by removing ourself from [client].
     */
    override fun onDestroy() {
        super.onDestroy()
        client.setAuthorizationFragment(null)
    }
}