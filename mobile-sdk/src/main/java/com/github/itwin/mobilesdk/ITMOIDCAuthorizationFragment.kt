/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import net.openid.appauth.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
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
 *
 * @param oidcClient The [ITMOIDCAuthorizationClient] with which this fragment is associated.
 */
open class ITMOIDCAuthorizationFragment(oidcClient: ITMOIDCAuthorizationClient): ITMAuthorizationFragment(oidcClient) {
    private val authSettings = oidcClient.authSettings
    private var authState: AuthState? = null
    private var authService: AuthorizationService? = null
    private var continuation: Continuation<AccessToken>? = null

    private suspend fun initAuthState(): AuthState {
        authState?.let { authState ->
            return authState
        }
        return suspendCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(authSettings.issuerUri) { config, error ->
                @Suppress("NAME_SHADOWING")
                error?.let { error ->
                    client.itmApplication.logger.log(
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
     * Clean up by removing ourself from [client].
     */
    override fun onDestroy() {
        super.onDestroy()
        client.setAuthorizationFragment(null)
    }
}

fun Long.epochMillisToISO8601(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Instant.ofEpochMilli(this).toString()
    } else {
        val epochDate = Date(this)
        @Suppress("SpellCheckingInspection")
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
        return sdf.format(epochDate)
    }
}