package com.github.itwin.mobilesdk

import androidx.fragment.app.Fragment
import java.util.*

/**
 * Abstract base class for a [Fragment] used to present the signin UI for [ITMAuthorizationClient].
 *
 * __Note:__ The constructor automatically attaches this instance to [client].
 */
abstract class ITMAuthorizationFragment : Fragment() {
    companion object {
        /**
         * The [ITMAuthorizationClient] with which each fragment is associated.
         */
        var client: ITMAuthorizationClient? = null
    }
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
    protected var cachedToken: AccessToken? = null

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

    init {
        @Suppress("LeakingThis")
        client?.setAuthorizationFragment(this)
    }

    /**
     * Coroutine to present a signin UI to the user and return an access token. If a cached token
     * exists, that should be directly returned instead of asking the user to sign in again.
     */
    abstract suspend fun getAccessToken(): AccessToken
}