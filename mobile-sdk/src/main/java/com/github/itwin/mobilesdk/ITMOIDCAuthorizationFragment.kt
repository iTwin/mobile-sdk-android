/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import net.openid.appauth.*
import java.lang.Error
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


/**
 * [Fragment] used to present the signin UI for [ITMOIDCAuthorizationClient].
 *
 * In order to use this, you must add the following to the `defaultConfig` section of the `android` section
 * of your app's build.gradle:
 *
 * ```
 *     manifestPlaceholders = ['appAuthRedirectScheme': 'imodeljs']
 * ```
 */
open class ITMOIDCAuthorizationFragment : ITMAuthorizationFragment() {
    private val authSettings = (client as? ITMOIDCAuthorizationClient)?.authSettings
    private var authState: AuthState? = null
    private var authService: AuthorizationService? = null
    private var continuation: Continuation<AccessToken>? = null

    companion object {
        /**
         * Create an [ITMOIDCAuthorizationFragment] connected to the given [ITMOIDCAuthorizationClient].
         *
         * __Note:__ [oidcClient] is stored in the companion object so that any [ITMOIDCAuthorizationFragment]
         * objects created during the application life cycle will have access to it.
         *
         * @param oidcClient The [ITMOIDCAuthorizationClient] with which the new fragment is associated.
         * @return An [ITMOIDCAuthorizationFragment] connected to [oidcClient].
         */
        fun newInstance(oidcClient: ITMOIDCAuthorizationClient): ITMOIDCAuthorizationFragment {
            client = oidcClient
            return ITMOIDCAuthorizationFragment()
        }

        /**
         * Clear the active [client][ITMAuthorizationFragment.client] that is used when fragments are created.
         */
        fun clearClient() {
            client = null
        }
    }

    private suspend fun initAuthState(): AuthState {
        authState?.let { authState ->
            return authState
        }
        return suspendCoroutine { continuation ->
            val issuerUri = authSettings?.issuerUri ?: throw Error("No settings")
            AuthorizationServiceConfiguration.fetchFromIssuer(issuerUri) { config, error ->
                @Suppress("NAME_SHADOWING")
                error?.let { error ->
                    client?.itmApplication?.logger?.log(
                        ITMLogger.Severity.Warning,
                        "Error fetching OIDC service config: $error"
                    )
                    throw error
                } ?: config?.let { config ->
                    val authState = AuthState(config)
                    this.authState = authState
                    continuation.resume(authState)
                }
            }
        }
    }

    private fun resume(accessToken: AccessToken) {
        continuation?.resume(accessToken)
        continuation = null
    }

    private fun launchRequestAuthorization(authState: AuthState) {
        val authSettings = this.authSettings ?: return
        val authReqBuilder = AuthorizationRequest.Builder(
            authState.authorizationServiceConfiguration!!,
            authSettings.clientId,
            ResponseTypeValues.CODE,
            authSettings.redirectUri
        )
        authReqBuilder.setScope(authSettings.scope)
            .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
        if (authService == null) {
            authService = AuthorizationService(requireContext())
        }
        authService!!.getAuthorizationRequestIntent(authReqBuilder.build())?.let { intent ->
            requestAuthorization.launch(intent)
        } ?: resume(AccessToken())
    }

    /**
     * Coroutine to present a signin UI to the user and return an access token. If a cached token
     * exists, that should be directly returned instead of asking the user to sign in again.
     */
    override suspend fun getAccessToken(): AccessToken {
        return if (haveCachedToken()) {
            cachedToken!!
        } else {
            try {
                val authState = initAuthState()
                suspendCoroutine { continuation ->
                    this.continuation = continuation
                    launchRequestAuthorization(authState)
                }
            } catch (ex: Exception) {
                AccessToken()
            }
        }
    }

    private fun handleAuthorizationResponse(data: Intent) {
        val response = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)

        if (response == null) {
            resume(AccessToken())
            return
        }
        authState?.let { authState ->
            authState.update(response, ex)
            authService?.performTokenRequest(response.createTokenExchangeRequest()) { response, ex ->
                authState.update(response, ex)
                val accessToken = if (authState.isAuthorized) {
                    AccessToken("Bearer ${authState.accessToken}", authState.accessTokenExpirationTime?.epochMillisToISO8601())
                } else {
                    AccessToken()
                }
                cachedToken = accessToken
                resume(accessToken)
            }
        }
    }

    private var requestAuthorization = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { data ->
                handleAuthorizationResponse(data)
            } ?: resume(AccessToken())
        } else {
            resume(AccessToken())
        }
    }

    /**
     * Clean up by removing ourself from [client][ITMAuthorizationFragment.client].
     */
    override fun onDestroy() {
        super.onDestroy()
        client?.setAuthorizationFragment(null)
    }
}

/**
 * Convenience function convert a [Long] containing the number of milliseconds since the epoch into an
 * ISO 8601-formatted [String].
 *
 * @return A [String] containing an ISO 8601 format date.
 */
fun Long.epochMillisToISO8601(): String {
    return Instant.ofEpochMilli(this).toString()
}