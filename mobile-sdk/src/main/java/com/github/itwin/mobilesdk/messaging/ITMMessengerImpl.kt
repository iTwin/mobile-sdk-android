/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

import com.github.itwin.mobilesdk.ITMFailureCallback
import com.github.itwin.mobilesdk.ITMLogger
import com.github.itwin.mobilesdk.ITMQueryCallback
import com.github.itwin.mobilesdk.ITMSuccessCallback
import com.github.itwin.mobilesdk.error
import com.github.itwin.mobilesdk.info
import com.github.itwin.mobilesdk.jsonvalue.JSONValue
import com.github.itwin.mobilesdk.jsonvalue.toJSON
import com.github.itwin.mobilesdk.jsonvalue.toJSONOrNull
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.ERROR_KEY
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.RESPONSE_KEY
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The response to a query, either sent to or received from the other side of an
 * [ITMQueryTransport].
 */
internal sealed class ITMQueryResponse {
    /**
     * Successful response.
     *
     * @property data The response data. This is [Unit] when the response has no data.
     */
    data class Success(val data: Any?) : ITMQueryResponse()

    /**
     * Error response.
     *
     * @property error The error.
     */
    data class Failure(val error: Throwable) : ITMQueryResponse()

    /**
     * Response indicating that there is no handler for the query.
     */
    object Unhandled : ITMQueryResponse()

    /**
     * Converts response to JSON data string that can be sent to JS code.
     */
    fun toJSONString(): String {
        return when (this) {
            is Success -> {
                val message = JSONObject()
                when (data) {
                    null, Unit -> {}
                    is JSONValue -> message.put(RESPONSE_KEY, data.value)
                    else -> message.put(RESPONSE_KEY, toJSON(data).value)
                }
                message.toString()
            }
            is Failure -> {
                val message = JSONObject()
                message.put(ERROR_KEY, if (error.message != null) JSONValue(error.message) else JSONObject())
                message.toString()
            }
            Unhandled -> "{\"unhandled\":true}"
        }
    }
}

/**
 * Messenger implementation that sends and receives queries using an [ITMQueryTransport].
 *
 * @property targetName The name of the other end of the messenger when logging.
 * @property idPrefix The prefix to use for query ids when logging.
 * @property logger The [com.github.itwin.mobilesdk.ITMLogger] to use for logging. If this is `null`, no logging happens.
 */
internal class ITMMessengerImpl(
    val targetName: String,
    val idPrefix: String,
    override var logger: ITMLogger?
): ITMMessengerProtocol, ITMQueryTransport.Delegate {
    /**
     * Transport that passes data to and from other end of this messenger.
     */
    lateinit var transport: ITMQueryTransport

    /**
     * [CompletableDeferred] indicating that the other end of this messenger is ready to receive messages. All
     * calls to [query] will wait for this to complete before sending the message. If launch fails, pending
     * queries will fail with the launch exception.
     */
    val launchDeferred = CompletableDeferred<Unit>()

    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    private val mainScope = MainScope()

    /**
     * Handlers waiting for incoming queries. The key is the query name, and the value is the
     * handler.
     */
    private val handlers: MutableMap<String, MessageHandler<*, *>> = ConcurrentHashMap()

    private val isLoggingEnabled: Boolean
        get() = ITMMessengerLoggingOptions.isLoggingEnabled

    private val isFullLoggingEnabled: Boolean
        get() = ITMMessengerLoggingOptions.isFullLoggingEnabled

    /**
     * Checks if a given query type is set as unlogged query type.
     */
    private fun shouldSkipLogging(type: String) = ITMMessengerLoggingOptions.getUnloggedQueryTypes().contains(type)

    companion object {
        /**
         * JSON key used for the response parameter of messages.
         */
        const val RESPONSE_KEY = "response"

        /**
         * JSON key used for the error parameter of messages.
         */
        const val ERROR_KEY = "error"

        /**
         * Counter to increment and use when sending a query.
         */
        private val queryIdCounter = AtomicInteger(0)
    }

    /**
     * Class for handling incoming queries.
     *
     * @param type The query name to listen for.
     * @param callback The [ITMQueryCallback] callback object for the query.
     */
    private class MessageHandler <I, O> (
        val type: String,
        private val callback: ITMQueryCallback<I, O>
    ) : ITMQueryHandler {
        /**
         * Function that is called when a query of the specified [type] is received.
         *
         * > __Note:__ If the data contained in [data] cannot be typecast to [I], this will send
         * an error response.
         *
         * @param data Optional arbitrary message data.
         */
        fun handleMessage(data: JSONValue?, onSuccess: (JSONValue?) -> Unit, onFailure: (Throwable) -> Unit) {
            try {
                @Suppress("UNCHECKED_CAST")
                callback.invoke((data?.anyValue) as I, { result ->
                    onSuccess(if (result is Unit) null else toJSON(result))
                }, { error ->
                    onFailure(error)
                })
            } catch (error: Throwable) {
                onFailure(error)
            }
        }
    }

    //region Private functions

    /**
     * Called when a query is received from [transport]. If there is no handler for the query, an
     * error is logged and an unhandled response is sent back.
     *
     * @param queryId The query ID.
     * @param type The query type.
     * @param data Optional query data.
     */
    private suspend fun handleQuery(queryId: Int, type: String, data: JSONValue?): ITMQueryResponse {
        val handler = handlers[type]
        if (handler == null) {
            logger?.error("Unhandled query [$targetName -> Kotlin] $idPrefix$queryId: $type")
            return ITMQueryResponse.Unhandled
        }

        logQuery("Request $targetName -> Kotlin", queryId, type, data)
        return suspendCoroutine { continuation ->
            handler.handleMessage(
                data,
                onSuccess = { response ->
                    logQuery("Response Kotlin -> $targetName", queryId, type, response)
                    continuation.resume(ITMQueryResponse.Success(response))
                },
                onFailure = { error ->
                    logQuery("Error Response Kotlin -> $targetName", queryId, type, null)
                    continuation.resume(ITMQueryResponse.Failure(error))
                },
            )
        }
    }

    /**
     * Called when a query response is received from [transport]. This routes the response to the
     * callbacks of the original query.
     *
     * @param queryId The ID of the query being responded to.
     * @param response The response.
     */
    private fun handleQueryResponse(
        queryId: Int,
        type: String,
        response: ITMQueryResponse,
        onSuccess: ITMSuccessCallback<Any?>?,
        onFailure: ITMFailureCallback?
    ) {
        try {
            when (response) {
                is ITMQueryResponse.Success -> {
                    logQuery("Response $targetName -> Kotlin", queryId, type, toJSONOrNull(response.data))
                    onSuccess?.invoke(response.data)
                }
                is ITMQueryResponse.Failure -> {
                    logQuery("Error Response $targetName -> Kotlin", queryId, type, toJSONOrNull(response.error.message))
                    onFailure?.invoke(response.error)
                }
                ITMQueryResponse.Unhandled -> {
                    logQuery("Unhandled Response $targetName -> Kotlin", queryId, type, null)
                    onFailure?.invoke(Exception("Unhandled query: $type"))
                }
            }
        } catch (error: Throwable) {
            logger?.error("Messenger handleQueryResponse() exception: $error")
            onFailure?.invoke(error)
        }
    }

    /**
     * Called to log a query. Converts [data] into a string and then calls the other overload of
     * `logQuery`.
     *
     * @param title Title to show along with the logged message.
     * @param queryId Query identifier.
     * @param type Type of the query.
     * @param data Query data. If [isFullLoggingEnabled] is set to false, this value is ignored.
     */
    private fun logQuery(title: String, queryId: Int, type: String, data: JSONValue?) {
        if (!isLoggingEnabled || shouldSkipLogging(type)) return
        val prettyDataString = try {
            data?.toPrettyString() ?: "<void>"
        } catch (_: Throwable) {
            "<error>"
        }
        logQuery(title, "$idPrefix$queryId", type, prettyDataString)
    }

    /**
     * Log the given query using `logInfo` if [isLoggingEnabled] is set to true, or nothing
     * otherwise.
     *
     * @param title Title to show along with the logged message.
     * @param queryTag Query identifier, prefix + query ID, e.g. "WVID42".
     * @param type Type of the query.
     * @param prettyDataString Pretty-printed JSON representation of the query data. If
     * [isFullLoggingEnabled] is set to false, this value is ignored.
     */
    private fun logQuery(title: String, queryTag: String, type: String, prettyDataString: String?) {
        if (!isLoggingEnabled || shouldSkipLogging(type)) return
        if (isFullLoggingEnabled) {
            logger?.info("ITMMessenger [$title] $queryTag: $type\n${prettyDataString ?: "null"}")
        } else {
            logger?.info("ITMMessenger [$title] $queryTag: $type")
        }
    }

    //endregion
    //region Public API

    /**
     * Must be called after the other end of this messenger has successfully launched, indicating
     * that it is ready to receive queries.
     */
    fun launchSucceeded() {
        launchDeferred.complete(Unit)
    }

    /**
     * Indicates if the launch of the other end of this messenger has completed.
     */
    val isLaunchComplete: Boolean
        get() = launchDeferred.isCompleted

    /**
     * Must be called if the other end of this messenger fails to launch. This prevents any queries
     * from being sent.
     *
     * @param error The reason for the failure.
     */
    fun launchFailed(error: Throwable) {
        launchDeferred.completeExceptionally(error)
    }

    /**
     * Suspends until the launch of the other end of this messenger has completed.
     */
    suspend fun awaitLaunch() {
        launchDeferred.await()
    }

    override fun <I, O> query(type: String, data: I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback?) {
        mainScope.launch {
            // Wait until the other side is ready to receive messages.
            awaitLaunch()
            val queryId = queryIdCounter.incrementAndGet()
            val dataValue = toJSONOrNull(data)
            logQuery("Request Kotlin -> $targetName", queryId, type, dataValue)

            try {
                val response = transport.sendQuery(queryId, type, dataValue?.toString())
                @Suppress("UNCHECKED_CAST")
                handleQueryResponse(queryId, type, response, success as? ITMSuccessCallback<Any?>, failure)
            } catch (error: Throwable) {
                failure?.invoke(error)
            }
        }
    }

    override fun <I, O> registerQueryHandler(type: String, callback: ITMQueryCallback<I, O>): ITMQueryHandler {
        val handler = MessageHandler(type) { data, success, failure ->
            callback.invoke(data, success, failure)
        }
        handlers[type] = handler
        return handler
    }

    override fun removeHandler(handler: ITMQueryHandler?) {
        if (handler is MessageHandler<*, *>) {
            handlers.remove(handler.type)
        }
    }

    override suspend fun onQuery(queryId: Int, type: String, data: JSONValue?): String {
        return handleQuery(queryId, type, data).toJSONString()
    }

    //endregion
}
