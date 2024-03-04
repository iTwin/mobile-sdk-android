/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")
package com.github.itwin.mobilesdk

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
     *
     * @param type Query type.
     * @param data Optional request data to send.
     */
    fun <I> send(type: String, data: I) {
        messenger.send(type, data)
    }

    /**
     * Convenience wrapper around [ITMMessenger.send]
     *
     * @param type Query type.
     */
    fun send(type: String) {
        messenger.send(type)
    }

    /**
     * Send query to the [web view][ITMApplication.webView] and receive the result using a
     * coroutine. Errors thrown.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the type you use for [O]
     * does not match the type of the data returned by the web view, an exception will be thrown
     * indicating that.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     *
     * @return The result from the web app.
     */
    suspend fun <I, O> query(type: String, data: I): O {
        return suspendCoroutine { continuation ->
            try {
                messenger.query<I, O>(type, data, { data ->
                    continuation.resume(data)
                }, { error ->
                    continuation.resumeWithException(error)
                })
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }

    /**
     * Send query with no data to the [web view][ITMApplication.webView] and receive the result
     * using a coroutine. Errors thrown.
     *
     * __Note__: The [O] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the type you use for [O] does not match
     * the type of the data returned by the web view, an exception will be thrown indicating that.
     *
     * @param type Query type.
     *
     * @return The result from the web app.
     */
    suspend fun <O> query(type: String): O {
        return query(type, Unit)
    }

    /**
     * Add a coroutine-based handler for queries from the web view that do not expect a response and
     * do not include input data.
     *
     * @param type Query type.
     * @param callback Coroutine Function called when a message is received.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     */
    fun registerMessageHandler(type: String, callback: suspend () -> Unit) = registerQueryHandler<Unit, Unit>(type) {
        callback.invoke()
    }

    /**
     * Add a coroutine-based handler for queries from the web view that do not include a response.
     *
     * __Note__: The [I] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the data sent from the web view does
     * not match the type specified for [I], an error response will be sent to the web view
     * indicating that.
     *
     * @param type Query type.
     * @param callback Coroutine Function called when a message is received.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     */
    fun <I> registerMessageHandler(type: String, callback: suspend (I) -> Unit) =
        registerQueryHandler<I, Unit>(type) { value ->
            callback.invoke(value)
        }

    /**
     * Add a coroutine-based handler for queries from the [web view][ITMApplication.webView].
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the data sent from the
     * web view does not match the type specified for [I], an error response will be sent to the web
     * view indicating that.
     *
     * @param type Query type.
     * @param callback Coroutine function to respond to the query. Throws in the case of error,
     * otherwise optionally return a value.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     */
    fun <I, O> registerQueryHandler(type: String, callback: suspend (I) -> O) =
        messenger.registerQueryHandler<I, O>(type) { value, success, failure ->
            MainScope().launch {
                try {
                    val result = callback.invoke(value)
                    success?.invoke(result)
                } catch (error: Throwable) {
                    failure?.invoke(error)
                }
            }
        }

    /**
     * Convenience wrapper around [ITMMessenger.removeHandler].
     *
     * @param handler The handler to remove.
     */
    fun removeHandler(handler: ITMMessenger.ITMHandler?) {
        messenger.removeHandler(handler)
    }

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchSucceeded].
     */
    fun frontendLaunchSucceeded() {
        messenger.frontendLaunchSucceeded()
    }

    /**
     * Convenience wrapper around [ITMMessenger.isFrontendLaunchComplete].
     */
    val isFrontendLaunchComplete: Boolean
        get() = messenger.isFrontendLaunchComplete

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchFailed].
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        messenger.frontendLaunchFailed(error)
    }

    /**
     * Call to join the frontend launch job (wait for the frontend to launch).
     */
    suspend fun frontendLaunchJoin() {
        messenger.frontendLaunchJob.join()
    }
}
