/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk.messaging

import com.bentley.itwin.IModelJsHost
import com.bentley.itwin.NativeQueryResponseAction
import com.github.itwin.mobilesdk.ITMFailureCallback
import com.github.itwin.mobilesdk.ITMLogger
import com.github.itwin.mobilesdk.ITMQueryCallback
import com.github.itwin.mobilesdk.ITMSuccessCallback
import com.github.itwin.mobilesdk.error
import com.github.itwin.mobilesdk.jsonvalue.JSONValue
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.ERROR_KEY
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.RESPONSE_KEY
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Class for sending queries to backend JavaScript and handling messages sent by backend JavaScript.
 *
 * > __Note:__ Messages from the backend are one-way. Handlers registered with
 * [registerQueryHandler] are called on the main thread, and any result they produce is discarded.
 *
 * @param logger The [com.github.itwin.mobilesdk.ITMLogger] to use for logging. If this is `null`, no logging happens.
 */
class ITMBackendMessenger(logger: ITMLogger? = null) : ITMMessengerProtocol {
    /**
     * Transport that passes data to and from iTwin.js backend.
     */
    private val backendTransport: ITMBackendQueryTransport
        get() = implementation.transport as ITMBackendQueryTransport

    /**
     * Implementation of [ITMMessengerProtocol] to which this messenger delegates to.
     */
    private val implementation = ITMMessengerImpl("JS backend", "MBID", logger)

    /**
     * [Job] indicating that the backend is running and ready to receive messages. All
     * calls to [send] and [query] will wait for this to complete before sending the message.
     */
    internal val backendLaunchJob: CompletableJob
        get() = implementation.launchJob

    init {
        implementation.transport = ITMBackendQueryTransport(implementation)
    }

    /**
     * Logger used to log info about incoming and outgoing queries and errors during query handling.
     */
    var logger: ITMLogger?
        get() = implementation.logger
        set(value) {
            implementation.logger = value
        }

    /**
     * The [IModelJsHost] with which this [ITMBackendMessenger] communicates.
     */
    var host: IModelJsHost?
        get() = backendTransport.host
        set(value) {
            backendTransport.host = value
        }

    override fun <I, O> query(type: String, data: I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback?) {
        implementation.query(type, data, success, failure)
    }

    override fun <I, O> registerQueryHandler(type: String, callback: ITMQueryCallback<I, O>): ITMQueryHandler {
        return implementation.registerQueryHandler(type, callback)
    }

    override fun removeHandler(handler: ITMQueryHandler?) {
        implementation.removeHandler(handler)
    }

    /**
     * Must be called after the backend has successfully launched, indicating that the backend is
     * ready to receive queries.
     */
    fun backendLaunchSucceeded() {
        implementation.launchSucceeded()
    }

    /**
     * Indicates if the backend launch has completed.
     */
    val isBackendLaunchComplete: Boolean
        get() = implementation.isLaunchComplete

    /**
     * Must be called if the backend fails to launch. This prevents any queries from being sent to the backend.
     *
     * @param error The reason for the failure.
     */
    fun backendLaunchFailed(error: Throwable) {
        implementation.launchFailed(error)
    }
}

/**
 * [ITMQueryTransport] that communicates with backend JavaScript through [IModelJsHost].
 */
internal class ITMBackendQueryTransport(override val delegate: ITMQueryTransport.Delegate) : ITMQueryTransport {
    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    private val mainScope = MainScope()

    /**
     * Counter to increment and use when receiving queries. While the transport does not rely
     * on query ids for passing data, but they are still used for logging.
     */
    private val queryIdCounter = AtomicInteger(0)

    /**
     * The [IModelJsHost] with which this transport communicates.
     */
    var host: IModelJsHost? = null
        set(value) {
            field = value
            value?.setQueryCallback { type, data, callback -> onHostQuery(type, data, callback) }
        }

    override suspend fun sendQuery(queryId: Int, type: String, data: String?): ITMQueryResponse {
        return suspendCancellableCoroutine { continuation ->
            val host = host
            if (host == null) {
                continuation.resume(ITMQueryResponse.Failure(Error("Backend messenger does not have host set")))
                return@suspendCancellableCoroutine
            }

            host.queryBackend(type, data) { responseString, isError ->
                if (isError) {
                    // Error occurred before reaching the JS messenger, error message is a plain string.
                    continuation.resume(ITMQueryResponse.Failure(Exception(responseString)))
                    return@queryBackend
                }

                try {
                    continuation.resume(parseResponse(responseString))
                } catch (error: Throwable) {
                    delegate.logger?.error("ITMBackendMessenger.sendQuery parse exception: $error")
                    continuation.resume(ITMQueryResponse.Failure(error))
                }
            }
        }
    }

    /**
     * Converts a JSON response string received from the backend into a [ITMQueryResponse].
     *
     * @param responseString Response received from the backend.
     * @return an [ITMQueryResponse] representing the parsed response string.
     */
    private fun parseResponse(responseString: String): ITMQueryResponse {
        val responseMap = JSONValue.fromJSON(responseString).anyValue as Map<*, *>
        val error = responseMap[ERROR_KEY]
        return if (error != null) {
            ITMQueryResponse.Failure(Exception(error.toString()))
        } else {
            ITMQueryResponse.Success(if (responseMap.contains(RESPONSE_KEY)) responseMap[RESPONSE_KEY] else Unit)
        }
    }

    /**
     * Handles a query received from backend JavaScript.
     *
     * @param type The query type.
     * @param dataString Query data as a string, or an empty string if there is none.
     */
    private fun onHostQuery(type: String, dataString: String, callback: NativeQueryResponseAction) {
        val data = try {
            dataString.takeIf { it.isNotEmpty() }?.let { JSONValue.fromJSON(it) }
        } catch (error: Exception) {
            delegate.logger?.error("Invalid backend query '$type': $error")
            callback.error("Failed to parse query data")
            return
        }

        val queryId = queryIdCounter.incrementAndGet()
        mainScope.launch {
            val response = delegate.onQuery(queryId, type, data)
            callback.resolve(response)
        }
    }
}
