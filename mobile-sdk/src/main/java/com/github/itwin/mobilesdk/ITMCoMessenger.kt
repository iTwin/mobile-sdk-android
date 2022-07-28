/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import com.eclipsesource.json.JsonValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * A wrapper around ITMMessenger that uses Kotlin Coroutines.
 *
 * @param messenger The [ITMMessenger] that this wraps.
 */
open class ITMCoMessenger(private val messenger: ITMMessenger) {
    /**
     * Convenience wrapper around [ITMMessenger.send].
     */
    @Suppress("unused")
    open fun send(type: String, data: JsonValue? = null) {
        messenger.send(type, data)
    }

    /**
     * Send query to the [web view][ITMApplication.webView] and receive the result using a coroutine. Errors thrown.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     *
     * @return The result from the web app.
     */
    @Suppress("unused")
    open suspend fun query(type: String, data: JsonValue? = null): JsonValue? {
        return suspendCoroutine { continuation ->
            try {
                messenger.query(type, data, { data ->
                    continuation.resume(data)
                }, { error ->
                    continuation.resumeWithException(error)
                })
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    }

    /**
     * Convenience wrapper around [ITMMessenger.addMessageListener]
     */
    @Suppress("unused")
    open fun addMessageListener(type: String, callback: ITMSuccessCallback): ITMMessenger.ITMListener {
        return messenger.addMessageListener(type, callback)
    }

    /**
     * Add a coroutine-based listener for queries from the [web view][ITMApplication.webView].
     *
     * @param type Query type.
     * @param callback Coroutine function to respond to the query. Throws in the case of error, otherwise optionally return a value.
     *
     * @return The [ITMMessenger.ITMListener] value to subsequently pass into [removeListener].
     */
    open fun addQueryListener(type: String, callback: suspend (JsonValue?) -> JsonValue?): ITMMessenger.ITMListener {
        return messenger.addQueryListener(type) { value, success, failure ->
            MainScope().launch {
                try {
                    val result = callback.invoke(value)
                    success?.invoke(result)
                } catch (error: Exception) {
                    failure?.invoke(error)
                }
            }
        }
    }

    /**
     * Convenience wrapper around [ITMMessenger.removeListener].
     */
    open fun removeListener(listener: ITMMessenger.ITMListener?) {
        messenger.removeListener(listener)
    }

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchSucceeded].
     */
    @Suppress("unused")
    open fun frontendLaunchSucceeded() {
        messenger.frontendLaunchSucceeded()
    }

    /**
     * Convenience wrapper around [ITMMessenger.isFrontendLaunchComplete].
     */
    @Suppress("unused")
    open val isFrontendLaunchComplete: Boolean
        get() = messenger.isFrontendLaunchComplete

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchFailed].
     */
    open fun frontendLaunchFailed(exception: Exception) {
        messenger.frontendLaunchFailed(exception)
    }
}
