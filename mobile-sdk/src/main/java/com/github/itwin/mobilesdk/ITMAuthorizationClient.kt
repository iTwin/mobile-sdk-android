package com.github.itwin.mobilesdk

import com.bentley.itwin.AuthorizationClient
import com.eclipsesource.json.JsonObject

/**
 * Abstract subclass of [AuthorizationClient] that includes a fragment for presenting a signin UI to
 * the user. If you return an instance of a subclass of this class from [ITMApplication.createAuthorizationClient],
 * [ITMApplication.createAuthorizationFragment] to create a related [ITMAuthorizationFragment].
 *
 * @param itmApplication The [ITMApplication] that will be needing authorization.
 * @param configData A JSON object containing app-specific config data that may be needed by the
 * authorization client.
 */
abstract class ITMAuthorizationClient(
    @Suppress("unused") val itmApplication: ITMApplication,
    @Suppress("unused") protected val configData: JsonObject): AuthorizationClient() {

    /**
     * The [ITMAuthorizationFragment] that is used to present the signin UI to the user.
     */
    protected var fragment: ITMAuthorizationFragment? = null

    /**
     * Sets [fragment] to the given value.
     *
     * You must call this with `null` in the [onDestroy][androidx.fragment.app.Fragment.onDestroy]
     * of your [ITMAuthorizationFragment].
     *
     * @param value The new value for [fragment].
     */
    open fun setAuthorizationFragment(value: ITMAuthorizationFragment?) {
        fragment = value
    }
}
