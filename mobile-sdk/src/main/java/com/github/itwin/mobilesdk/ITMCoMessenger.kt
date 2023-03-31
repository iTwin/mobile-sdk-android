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
class ITMCoMessenger(private val messenger: ITMMessenger) {
    /**
     * Convenience wrapper around [ITMMessenger.send].
     */
    @Suppress("unused")
    fun send(type: String, data: JsonValue? = null) {
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
    suspend fun query(type: String, data: JsonValue? = null): JsonValue? {
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
     * Add a coroutine-based handler for queries from the web view that do not include a response.
     *
     * @param type Query type.
     * @param callback Coroutine Function called when a message is received.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     */
    @Suppress("unused")
    fun registerMessageHandler(type: String, callback: suspend (JsonValue?) -> Unit): ITMMessenger.ITMHandler {
        return registerQueryHandler(type) { value ->
            callback.invoke(value)
            null
        }
    }

    /**
     * Add a coroutine-based handler for queries from the [web view][ITMApplication.webView].
     *
     * @param type Query type.
     * @param callback Coroutine function to respond to the query. Throws in the case of error, otherwise optionally return a value.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     */
    fun registerQueryHandler(type: String, callback: suspend (JsonValue?) -> JsonValue?): ITMMessenger.ITMHandler {
        return messenger.registerQueryHandler(type) { value, success, failure ->
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
     * Convenience wrapper around [ITMMessenger.removeHandler].
     */
    fun removeHandler(handler: ITMMessenger.ITMHandler?) {
        messenger.removeHandler(handler)
    }

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchSucceeded].
     */
    @Suppress("unused")
    fun frontendLaunchSucceeded() {
        messenger.frontendLaunchSucceeded()
    }

    /**
     * Convenience wrapper around [ITMMessenger.isFrontendLaunchComplete].
     */
    @Suppress("unused")
    val isFrontendLaunchComplete: Boolean
        get() = messenger.isFrontendLaunchComplete

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchFailed].
     */
    fun frontendLaunchFailed(exception: Exception) {
        messenger.frontendLaunchFailed(exception)
    }

    /**
     * Call to join the frontend launch job (wait for the frontend to launch).
     */
    @Suppress("unused")
    suspend fun frontendLaunchJoin() {
        messenger.frontendLaunchJob.join()
    }
}
