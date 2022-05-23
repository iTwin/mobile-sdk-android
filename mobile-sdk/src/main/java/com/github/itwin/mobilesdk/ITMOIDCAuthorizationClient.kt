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

    override fun getAccessToken(completion: AuthTokenCompletionAction) {
        MainScope().launch {
            // Right now the frontend asks for a token when trying to send a message to the backend.
            // That token is totally unused by the backend, so we can simply fail all requests that
            // happen before the frontend launch has completed.
            // We don't want to make any actual token requests until the user does something that
            // requires a token.
            if (itmApplication.messenger?.isFrontendLaunchComplete == true) {
                val accessToken = fragment?.getAccessToken()
                if (accessToken != null) {
                    completion.resolve(accessToken.token, accessToken.expirationDate)
                } else {
                    completion.resolve(null, null)
                }
            } else {
                completion.resolve(null, null)
            }
        }
    }
}