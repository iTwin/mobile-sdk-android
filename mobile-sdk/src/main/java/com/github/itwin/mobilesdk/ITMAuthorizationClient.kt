package com.github.itwin.mobilesdk

import com.bentley.itwin.AuthTokenCompletionAction
import com.bentley.itwin.AuthorizationClient
import com.eclipsesource.json.JsonObject
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

data class ITMAuthSettings(val issuerUrl: String, val clientId: String, val redirectUrl: String, val scope: String)

class ITMAuthorizationClient(@Suppress("unused") val itmApplication: ITMApplication, configData: JsonObject): AuthorizationClient() {
    private val authSettings: ITMAuthSettings
    init {
        val issuerUrl = configData.getOptionalString("ITMAPPLICATION_ISSUER_URL") ?: "https://ims.bentley.com/"
        val clientId = configData.getOptionalString("ITMAPPLICATION_CLIENT_ID") ?: ""
        val redirectUrl = configData.getOptionalString("ITMAPPLICATION_REDIRECT_URI") ?: "imodeljs://app/signin-callback"
        val scope = configData.getOptionalString("ITMAPPLICATION_SCOPE") ?: "email openid profile organization itwinjs"
        authSettings = ITMAuthSettings(issuerUrl, clientId, redirectUrl, scope)
    }

    override fun getAccessToken(completion: AuthTokenCompletionAction) {
        MainScope().launch {
            // Right now the frontend asks for a token when trying to send a message to the backend.
            // That token is totally unused by the backend, so we can simply fail all requests that
            // happen before the frontend launch has completed.
            // We don't want to make any actual token requests until the user does something that
            // requires a token.
            if (itmApplication.messenger?.isFrontendLaunchComplete == true) {
                completion.resolve("Bearer <TOKEN GOES HERE>", "<ISO 8601 Date String GOES HERE>")
            } else {
                completion.resolve(null, null)
            }
        }
    }
}