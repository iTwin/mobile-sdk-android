/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

import com.github.itwin.mobilesdk.ITMFailureCallback
import com.github.itwin.mobilesdk.ITMQueryCallback
import com.github.itwin.mobilesdk.ITMSuccessCallback

/**
 * Empty interface used for message handlers.
 * > __Note:__ This type is used so that the actual type of the handlers is opaque to the API
 * user.
 */
interface ITMQueryHandler

/**
 * Interface that defines methods for sending and receiving queries.
 */
interface ITMMessengerProtocol {
    /**
     * Send a message, and ignore any possible result.
     *
     * __Note__: The [I] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types.
     *
     * @param type Query type.
     * @param data Request data to send. If this is [Unit], no request data will be sent. The `send`
     * overload with no `data` parameter does this.
     */
    fun <I> send(type: String, data: I) {
        query<I, Unit>(type, data, null)
    }

    /**
     * Send a message with no data, and ignore any possible result.
     *
     * @param type Query type.
     */
    fun send(type: String) {
        send(type, Unit)
    }

    /**
     * Send query and send the result to success and/or failure callbacks.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the type you use for [O]
     * does not match the type of the returned data, [failure] will be called with an error
     * indicating that.
     *
     * @param type Query type.
     * @param data Optional request data to send. If this is [Unit], no request data will be sent.
     * The `query` overload with no `data` parameter does this.
     * @param success Success callback called with the result data.
     * @param failure Failure callback called when the query returns an error.
     */
    fun <I, O> query(type: String, data: I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback? = null)

    /**
     * Send query with no data and send the result to success and/or failure callbacks.
     *
     * __Note__: The [O] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the type you use for [O] does not match
     * the type of the returned data, [failure] will be called with an error indicating that.
     *
     * @param type Query type.
     * @param success Success callback called with the result data.
     * @param failure Failure callback called when the query returns an error.
     */
    fun <O> query(type: String, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback? = null) =
        query(type, Unit, success, failure)

    /**
     * Add a handler for incoming queries.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the incoming data does
     * not match the type specified for [I], an error response will be sent indicating that.
     *
     * @param type Query type.
     * @param callback Function called to respond to query. Call `success` param upon success, or
     * `failure` param upon error.
     *
     * @return The [ITMQueryHandler] value to subsequently pass into [removeHandler].
     *
     * @see [removeHandler]
     */
    fun <I, O> registerQueryHandler(type: String, callback: ITMQueryCallback<I, O>): ITMQueryHandler

    /**
     * Remove the specified [ITMQueryHandler].
     *
     * @param handler The handler to remove.
     *
     * @see [registerQueryHandler]
     */
    fun removeHandler(handler: ITMQueryHandler?)
}
