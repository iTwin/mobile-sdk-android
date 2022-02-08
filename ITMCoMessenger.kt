package com.bentley.itmnativeui

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
    open fun send(type: String, data: JsonValue? = null) {
        messenger.send(type, data)
    }

    /**
     * Send query to ModelWebApp and receive result to coroutine. Errors thrown.
     * @param type message type.
     * @param data optional request data to send.
     */
    open suspend fun query(type: String, data: JsonValue? = null): JsonValue? {
        return suspendCoroutine { block ->
            try {
                messenger.query(type, data, { data ->
                    block.resume(data)
                }, { error ->
                    throw error
                })
            } catch (error: Exception) {
                block.resumeWithException(error)
            }
        }
    }

    /**
     * Convenience wrapper around [[ITMMessenger.addMessageListener]]
     */
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
                    failure?.invoke()
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
}