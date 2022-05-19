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
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * [Fragment] used to present the signin UI for [ITMOIDCAuthorizationClient].
 *
 * @param client The [ITMOIDCAuthorizationClient] with which this fragment is associated.
 */
open class ITMOIDCAuthorizationFragment(private val client: ITMOIDCAuthorizationClient): Fragment() {
    /**
     * Data class to hold a token string and its expiration date.
     *
     * @property token The token string. Should have a "Bearer " prefix, followed by a Base64-encoded token.
     * @property expirationDate The expiration date of the token, in ISO 8601 format.
     */
    data class AccessToken(val token: String? = null, val expirationDate: String? = null)

    /**
     * The last cached [AccessToken], or null if one hasn't been fetched.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected var cachedToken: AccessToken? = null

    init {
        @Suppress("LeakingThis")
        client.setAuthorizationFragment(this)
    }

    /**
     * Determine if we have a non-expired cached token.
     *
     * @return true if we have a non-expired cached token, or false otherwise.
     */
    open fun haveCachedToken(): Boolean {
        cachedToken?.expirationDate?.let { expirationDateString ->
            val expirationDate = expirationDateString.iso8601ToDate() ?: return false
            return Date().time < expirationDate.time
        }
        return false
    }

    /**
     * Coroutine to present a signin UI to the user and return an access token. If a cached token
     * exists, that should be directly returned instead of asking the user to sign in again.
     */
    open suspend fun getAccessToken(): AccessToken {
        return suspendCoroutine { continuation ->
            if (haveCachedToken()) {
                continuation.resume(cachedToken!!)
            } else {
                with(AlertDialog.Builder(context)) {
                    setTitle("Auth Stub")
                    setMessage("issuerUrl: " + client.authSettings.issuerUrl)
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