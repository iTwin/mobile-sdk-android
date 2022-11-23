/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bentley.itwin.AuthTokenCompletionAction
import com.bentley.itwin.AuthorizationClient
import com.eclipsesource.json.JsonObject
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import net.openid.appauth.*
import java.time.Instant
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * [AuthorizationClient] implementation that uses AppAuth-Android to present the login in a custom web browser tab.
 *
 * In order to use this, you must add a redirect scheme to the `defaultConfig` section of the `android` section
 * of your app's build.gradle. For example, something like this:
 *
 * ```
 *     manifestPlaceholders = ['appAuthRedirectScheme': 'com.bentley.sample.itwinstarter']
 * ```
 *
 * By using [ActivityResultCaller], the primary constructor is compatible with both [Fragment] and [ComponentActivity].
 *
 *
 */
open class ITMOIDCAuthorizationClient(private val itmApplication: ITMApplication, configData: JsonObject, resultCaller: ActivityResultCaller, private val context: Context) : AuthorizationClient() {
    private data class ITMAuthSettings(val issuerUri: Uri, val clientId: String, val redirectUri: Uri, val scope: String)
    private data class AccessToken(val token: String? = null, val expirationDate: String? = null)

    private val authSettings = parseConfigData(configData)
    private var authState: AuthState? = null
    private var authService: AuthorizationService? = null
    private var continuation: Continuation<AccessToken>? = null
    private var requestAuthorization: ActivityResultLauncher<Intent>
    private var cachedToken: AccessToken? = null
        get() {
            // only return the token if it hasn't expired
            return field?.takeIf { Date().time < (it.expirationDate?.iso8601ToDate()?.time ?: 0) }
        }

    /**
     * Constructor using a [ComponentActivity].
     */
    constructor(itmApplication: ITMApplication, configData: JsonObject, activity: ComponentActivity): this(itmApplication, configData, activity, activity) {
        addStopObserver(activity)
    }

    /**
     * Constructor using a [Fragment].
     */
    @Suppress("unused")
    constructor(itmApplication: ITMApplication, configData: JsonObject, fragment: Fragment): this(itmApplication, configData, fragment, fragment.requireContext()) {
        addStopObserver(fragment)
    }

    private fun addStopObserver(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                dispose()
            }
        })
    }

    init {
        requestAuthorization = resultCaller.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.takeIf { it.resultCode == Activity.RESULT_OK }?.data?.let { data ->
                handleAuthorizationResponse(data)
            } ?: resume(AccessToken())
        }
    }

    /**
     * Disposes of any open resources held by this class.
     */
    fun dispose() {
        authService?.dispose()
        authService = null
    }

    companion object {
        private fun parseConfigData(configData: JsonObject): ITMAuthSettings {
            val issuerUrl = configData.getOptionalString("ITMAPPLICATION_ISSUER_URL") ?: "https://ims.bentley.com/"
            val clientId = configData.getOptionalString("ITMAPPLICATION_CLIENT_ID") ?: ""
            val redirectUrl = configData.getOptionalString("ITMAPPLICATION_REDIRECT_URI") ?: "imodeljs://app/signin-callback"
            val scope = configData.getOptionalString("ITMAPPLICATION_SCOPE") ?: "email openid profile organization itwinjs"
            return ITMAuthSettings(Uri.parse(issuerUrl), clientId, Uri.parse(redirectUrl), scope)
        }
    }

    private suspend fun initAuthState(): AuthState {
        return authState ?: suspendCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(authSettings.issuerUri) { config, error ->
                error?.let {
                    itmApplication.logger.log(ITMLogger.Severity.Warning, "Error fetching OIDC service config: $it")
                    throw it
                }
                config?.let {
                    val authState = AuthState(it)
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
        authReqBuilder.setScope(authSettings.scope).setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
        if (authService == null) {
            authService = AuthorizationService(context)
        }
        authService?.getAuthorizationRequestIntent(authReqBuilder.build())?.let { intent ->
            requestAuthorization.launch(intent)
        } ?: resume(AccessToken())
    }

    /**
     * Coroutine to present a signin UI to the user and return an access token. If a cached token
     * exists, that should be directly returned instead of asking the user to sign in again.
     */
    private suspend fun getAccessToken(): AccessToken {
        return cachedToken ?: try {
            val authState = initAuthState()
            suspendCoroutine { continuation ->
                this.continuation = continuation
                launchRequestAuthorization(authState)
            }
        } catch (ex: Exception) {
            AccessToken()
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

    /**
     * Function that is called by the iTwin backend to get an access token, along with its expiration date.
     *
     * @param completion Action that will have resolve called with the token and expiration date, or null values if
     * a token is not available. Note that the `expirationDate` parameter of `resolve` is expected to be an ISO
     * 8601 date string.
     */
    override fun getAccessToken(completion: AuthTokenCompletionAction) {
        MainScope().launch {
            // Right now the frontend asks for a token when trying to send a message to the backend.
            // That token is totally unused by the backend, so we can simply fail all requests that happen before the frontend launch has completed.
            // We don't want to make any actual token requests until the user does something that requires a token.
            val accessToken = if (itmApplication.messenger.isFrontendLaunchComplete) getAccessToken() else AccessToken()
            completion.resolve(accessToken.token, accessToken.expirationDate)
        }
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