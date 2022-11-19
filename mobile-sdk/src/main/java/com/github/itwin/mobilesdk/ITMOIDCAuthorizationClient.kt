/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.net.Uri
import com.bentley.itwin.AuthTokenCompletionAction
import com.eclipsesource.json.JsonObject
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

data class ITMAuthSettings(val issuerUri: Uri, val clientId: String, val redirectUri: Uri, val scope: String)

/**
 * [ITMAuthorizationClient] subclass that is used to perform authorization via OIDC in conjunction with
 * [ITMOIDCAuthorizationFragment] to present the UI to the user.
 *
 * In order to use this, you must add the following to the `defaultConfig` section of the `android` section
 * of your app's build.gradle:
 *
 * ```
 *     manifestPlaceholders = ['appAuthRedirectScheme': 'imodeljs']
 * ```
 *
 * @param itmApplication The [ITMApplication] that will be needing authorization.
 * @param configData A JSON object containing at least an `ITMAPPLICATION_CLIENT_ID` value, and optionally
 * `ITMAPPLICATION_ISSUER_URL`, `ITMAPPLICATION_REDIRECT_URI`, and/or `ITMAPPLICATION_SCOPE` values. If
 * `ITMAPPLICATION_CLIENT_ID` is not present this initializer will fail. This is be populated by
 * [ITMApplication.loadITMAppConfig].
 */
open class ITMOIDCAuthorizationClient(itmApplication: ITMApplication, configData: JsonObject):
    ITMAuthorizationClient(itmApplication, configData) {
    val authSettings: ITMAuthSettings
    init {
        val issuerUrl = configData.getOptionalString("ITMAPPLICATION_ISSUER_URL") ?: "https://ims.bentley.com/"
        val clientId = configData.getOptionalString("ITMAPPLICATION_CLIENT_ID") ?: ""
        val redirectUrl = configData.getOptionalString("ITMAPPLICATION_REDIRECT_URI") ?: "imodeljs://app/signin-callback"
        val scope = configData.getOptionalString("ITMAPPLICATION_SCOPE") ?: "email openid profile organization itwinjs"
        authSettings = ITMAuthSettings(Uri.parse(issuerUrl), clientId, Uri.parse(redirectUrl), scope)
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
            // That token is totally unused by the backend, so we can simply fail all requests that
            // happen before the frontend launch has completed.
            // We don't want to make any actual token requests until the user does something that
            // requires a token.
            val accessToken = fragment?.takeIf { itmApplication.messenger.isFrontendLaunchComplete }?.getAccessToken() ?: ITMAuthorizationFragment.AccessToken()
            completion.resolve(accessToken.token, accessToken.expirationDate)
        }
    }
}