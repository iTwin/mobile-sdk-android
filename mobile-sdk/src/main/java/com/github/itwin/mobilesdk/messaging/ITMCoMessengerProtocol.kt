/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

/**
 * Interface that defines methods for sending and receiving queries using Kotlin coroutines.
 */
interface ITMCoMessengerProtocol {
    /**
     * Convenience wrapper around [ITMMessengerProtocol.send].
     *
     * @param type Query type.
     * @param data Optional request data to send.
     */
    fun <I> send(type: String, data: I)

    /**
     * Convenience wrapper around [ITMMessengerProtocol.send]
     *
     * @param type Query type.
     */
    fun send(type: String)

    /**
     * Send query and receive the result using a coroutine. Errors thrown.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the type you use for [O]
     * does not match the type of the returned data, an exception will be thrown indicating that.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     *
     * @return The result of the query.
     */
    suspend fun <I, O> query(type: String, data: I): O

    /**
     * Send query with no data and receive the result using a coroutine. Errors thrown.
     *
     * __Note__: The [O] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the type you use for [O] does not match
     * the type of the returned data, an exception will be thrown indicating that.
     *
     * @param type Query type.
     *
     * @return The result of the query.
     */
    suspend fun <O> query(type: String): O {
        return query(type, Unit)
    }

    /**
     * Add a coroutine-based handler for queries that do not expect a response and do not include
     * input data.
     *
     * @param type Query type.
     * @param callback Coroutine Function called when a message is received.
     *
     * @return The [ITMQueryHandler] value to subsequently pass into [removeHandler].
     */
    fun registerMessageHandler(type: String, callback: suspend () -> Unit): ITMQueryHandler = registerQueryHandler<Unit, Unit>(type) {
        callback.invoke()
    }

    /**
     * Add a coroutine-based handler for queries that do not include a response.
     *
     * __Note__: The [I] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the incoming data does not match the
     * type specified for [I], an error response will be sent indicating that.
     *
     * @param type Query type.
     * @param callback Coroutine Function called when a message is received.
     *
     * @return The [ITMQueryHandler] value to subsequently pass into [removeHandler].
     */
    fun <I> registerMessageHandler(type: String, callback: suspend (I) -> Unit): ITMQueryHandler = registerQueryHandler<I, Unit>(type) { value ->
        callback.invoke(value)
    }

    /**
     * Add a coroutine-based handler for queries.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the incoming data does
     * not match the type specified for [I], an error response will be sent indicating that.
     *
     * @param type Query type.
     * @param callback Coroutine function to respond to the query. Throws in the case of error,
     * otherwise optionally return a value.
     *
     * @return The [ITMQueryHandler] value to subsequently pass into [removeHandler].
     */
    fun <I, O> registerQueryHandler(type: String, callback: suspend (I) -> O): ITMQueryHandler

    /**
     * Convenience wrapper around [ITMMessengerProtocol.removeHandler].
     *
     * @param handler The handler to remove.
     */
    fun removeHandler(handler: ITMQueryHandler?)
}
