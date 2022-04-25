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
 */
open class ITMCoMessenger(private val messenger: ITMMessenger) {
    /**
     * Convenience wrapper around [[ITMMessenger.send]]
     */
    @Suppress("unused")
    open fun send(type: String, data: JsonValue? = null) {
        messenger.send(type, data)
    }

    /**
     * Send query to ModelWebApp and receive result to coroutine. Errors thrown.
     * @param type message type.
     * @param data optional request data to send.
     */
    @Suppress("unused")
    open suspend fun query(type: String, data: JsonValue? = null): JsonValue? {
        return suspendCoroutine { block ->
            try {
                messenger.query(type, data, { data ->
                    block.resume(data)
                }, { error ->
                    block.resumeWithException(error)
                })
            } catch (error: Exception) {
                block.resumeWithException(error)
            }
        }
    }

    /**
     * Convenience wrapper around [[ITMMessenger.addMessageListener]]
     */
    @Suppress("unused")
    open fun addMessageListener(type: String, callback: ITMSuccessCallback): ITMMessenger.ITMListener {
        return messenger.addMessageListener(type, callback)
    }

    /**
     * Add a coroutine-based listener for queries from ModelWebApp.
     * @param type message type.
     * @param callback coroutine function to respond to query. Throw in the case of error, otherwise optionally return a value.
     * @return The [ITMMessenger.ITMListener] value to subsequently pass into [removeListener]
     */
    open fun addQueryListener(type: String, callback: suspend (JsonValue?) -> JsonValue?): ITMMessenger.ITMListener? {
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
     * Convenience wrapper around [[ITMMessenger.removeListener]]
     */
    open fun removeListener(listener: ITMMessenger.ITMListener?) {
        messenger.removeListener(listener)
    }

    /**
     * Wrapper around [[ITMMessenger.frontendLaunchSucceeded]]
     */
    @Suppress("unused")
    open fun frontendLaunchSucceeded() {
        messenger.frontendLaunchSucceeded()
    }

    /**
     * Wrapper around [[ITMMessenger.frontendLaunchFailed]]
     */
    open fun frontendLaunchFailed(exception: Exception) {
        messenger.frontendLaunchFailed(exception)
    }
}
