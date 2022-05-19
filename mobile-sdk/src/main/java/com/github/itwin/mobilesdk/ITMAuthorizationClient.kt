package com.github.itwin.mobilesdk

import com.bentley.itwin.AuthorizationClient
import com.eclipsesource.json.JsonObject

abstract class ITMAuthorizationClient(
    @Suppress("unused") val itmApplication: ITMApplication,
    @Suppress("unused") protected val configData: JsonObject): AuthorizationClient() {
    protected var fragment: ITMAuthorizationFragment? = null
    open fun setAuthorizationFragment(value: ITMAuthorizationFragment?) {
        fragment = value
    }
}
