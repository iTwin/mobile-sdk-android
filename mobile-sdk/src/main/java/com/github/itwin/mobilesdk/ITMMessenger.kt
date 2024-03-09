/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.github.itwin.mobilesdk.jsonvalue.JSONValue
import com.github.itwin.mobilesdk.jsonvalue.toJSON
import com.github.itwin.mobilesdk.jsonvalue.toJSONOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject

typealias ITMSuccessCallback<T> = (T) -> Unit
typealias ITMFailureCallback = (Throwable) -> Unit
typealias ITMQueryCallback<I, O> = (I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback?) -> Unit

/**
 * Class for sending and receiving messages to and from a [WebView][android.webkit.WebView] using
 * the `Messenger` class in `@itwin/mobile-sdk-core`.
 *
 * @property logger The [ITMLogger] to use for logging. If this is `null`, no logging happens.
 */
class ITMMessenger(var logger: ITMLogger? = null) {
    /**
     * Empty interface used for message handlers.
     * > __Note:__ This type is used so that the actual type of the handlers is opaque to the API
     * user.
     */
    interface ITMHandler

    /**
     * The [WebView][android.webkit.WebView] with which this [ITMMessenger] communicates.
     */
    var webView: WebView? = null
        set(value) {
            field = value
            webView?.addJavascriptInterface(object {
                @JavascriptInterface
                fun query(messageString: String) {
                    handleQuery(messageString)
                }

                @JavascriptInterface
                fun queryResponse(responseString: String) {
                    handleQueryResponse(responseString)
                }
            }, JS_INTERFACE_NAME)
        }

    /**
     * [Job] indicating that the frontend running in [webView] is ready to receive messages. All
     * calls to [send] and [query] will wait for this to complete before sending the message.
     */
    internal val frontendLaunchJob = Job()

    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    private val mainScope = MainScope()

    /**
     * Data class containing type along with optional success and failure callbacks used for values
     * in [pendingQueries].
     */
    private data class PendingQueryInfo(val type: String, val onSuccess: ITMSuccessCallback<Any?>?, val onFailure: ITMFailureCallback?)

    /**
     * Active queries that are waiting for a response from [webView]. The key is the query ID that
     * was sent to [webView] (which will be present in the response). The value is of type
     * [PendingQueryInfo].
     */
    private val pendingQueries: MutableMap<Int, PendingQueryInfo> = mutableMapOf()

    /**
     * Handlers waiting for queries from [webView]. The key is the query name, and the value is the
     * handler.
     */
    private val handlers: MutableMap<String, MessageHandler<*, *>> = mutableMapOf()

    /**
     * Class for handling queries from [webView].
     *
     * @param type The query name to listen for.
     * @param itmMessenger The [ITMMessenger] listening for the query.
     * @param callback The [ITMQueryCallback] callback object for the query.
     */
    private class MessageHandler <I, O> (
        val type: String,
        private val itmMessenger: ITMMessenger,
        private val callback: ITMQueryCallback<I, O>
    ) : ITMHandler {
        /**
         * Function that is called when a query is received from [webView] of the specified [type].
         *
         * > __Note:__ If the data contained in [data] cannot be typecast to [I], this will return
         * an error response to [webView].
         *
         * @param queryId The query ID of the query.
         * @param type The type of the query.
         * @param data Optional arbitrary message data.
         */
        fun handleMessage(queryId: Int, type: String, data: JSONValue?) {
            itmMessenger.logQuery("Request JS -> Kotlin", queryId, type, data)
            try {
                @Suppress("UNCHECKED_CAST")
                callback.invoke((data?.anyValue) as I, { result ->
                    itmMessenger.handleMessageSuccess(queryId, type, result)
                }, { error ->
                    itmMessenger.handleMessageFailure(queryId, type, error)
                })
            } catch (error: Throwable) {
                itmMessenger.handleMessageFailure(queryId, type, error)
            }
        }
    }

    //region Companion Object

    companion object {
        /**
         * Whether or not logging of all messages is enabled.
         */
        var isLoggingEnabled = false

        /**
         * Whether or not full logging of all messages (with their optional bodies) is enabled.
         *
         * __WARNING:__ You should only enable this in debug builds, since message bodies may
         * contain private information.
         */
        var isFullLoggingEnabled = false

        /**
         * Set containing query types that are not logged.
         *
         * @see [addUnloggedQueryType]
         * @see [removeUnloggedQueryType]
         */
        private val unloggedQueryTypes: MutableSet<String> = mutableSetOf()

        /**
         * Counter to increment and use when sending a message to [webView].
         *
         * __Note:__ This is static so that IDs will not be reused between ITMMessenger instances.
         */
        private val queryIdCounter = AtomicInteger(0)

        /**
         * JSON key used for the query ID parameter of messages.
         */
        private const val QUERY_ID_KEY = "queryId"

        /**
         * JSON key used for the name parameter of received messages.
         */
        private const val NAME_KEY = "name"

        /**
         * JSON key used for the message parameter of received messages.
         */
        private const val MESSAGE_KEY = "message"

        /**
         * JSON key used for the response parameter of sent messages.
         */
        private const val RESPONSE_KEY = "response"

        /**
         * JSON key used for the error parameter of sent messages.
         */
        private const val ERROR_KEY = "error"

        /**
         * The function name use in injected JavaScript when sending messages.
         */
        private const val QUERY_NAME = "window.Bentley_ITMMessenger_Query"

        /**
         * The function name use in injected JavaScript when sending query responses.
         */
        private const val QUERY_RESPONSE_NAME = "window.Bentley_ITMMessenger_QueryResponse"

        /**
         * The name of the JavascriptInterface class used by the `Messenger` class in
         * `@itwin/mobile-sdk-core`.
         */
        private const val JS_INTERFACE_NAME = "Bentley_ITMMessenger"

        /**
         * Add a query type to the list of unlogged queries.
         *
         * Unlogged queries are ignored by [logQuery]. This is useful (for example) for queries that
         * are themselves intended to produce log output, to prevent double log output.
         *
         * @param type The type of the query for which logging is disabled.
         *
         * @see [removeUnloggedQueryType]
         */
        fun addUnloggedQueryType(type: String) {
            unloggedQueryTypes.add(type)
        }

        /**
         * Remove a query type from the list of unlogged queries.
         *
         * @param type The type of the query to remove.
         *
         * @see [addUnloggedQueryType]
         */
        fun removeUnloggedQueryType(type: String) {
            unloggedQueryTypes.remove(type)
        }
    }

    //endregion
    //region Private functions

    /**
     * Called when a query is received from [webView].
     *
     * > __Note:__ If [messageString] is malformed, an error will be logged. The error will also be
     * sent back to [webView] as long as [messageString] contains a valid `queryId` field. If there
     * is no handler for the message, and error will be logged about that, as well as sent back to
     * [webView].
     *
     * @param messageString The JSON message string sent by [webView].
     */
    private fun handleQuery(messageString: String) {
        var queryId: Int? = null
        var name = "<Unknown>"
        try {
            // Note: if there is anything wrong with messageString, it will trigger an exception,
            // which will be logged and sent back to TS as an error.
            val request = JSONValue.fromJSON(messageString)
            queryId = (request[QUERY_ID_KEY] as Number).toInt()
            name = request[NAME_KEY] as String
            val handler = handlers[name]
            if (handler != null) {
                handler.handleMessage(queryId, name, toJSON(request.opt(MESSAGE_KEY)))
            } else {
                logError("Unhandled query [JS -> Kotlin] WVID$queryId: $name")
                handleUnhandledMessage(queryId)
            }
        } catch (error: Throwable) {
            logError("ITMMessenger.handleQuery exception: $error")
            queryId?.let { handleMessageFailure(it, name, error) }
        }
    }

    /**
     * Called when a query response is received from [webView].
     *
     * This routes the response to the appropriate handler for the original query that is being
     * responded to.
     *
     * > __Note:__ If [responseString] is malformed, this will log an error. Since this gets called
     * in response to a response message being sent by [webView], and [webView] isn't expecting any
     * response to its response, there is nothing further that can be done.
     *
     * @param responseString The JSON response string sent by [webView].
     */
    @Suppress("NestedBlockDepth")
    private fun handleQueryResponse(responseString: String) {
        try {
            // Note: if there is anything wrong with responseString, it will trigger an exception,
            // which will be logged.
            val response = JSONValue.fromJSON(responseString).anyValue as Map<*, *>
            val queryId = (response[QUERY_ID_KEY] as Number).toInt()
            pendingQueries.remove(queryId)?.let { (type, onSuccess, onFailure) ->
                try {
                    val error = response[ERROR_KEY]
                    if (error != null) {
                        logQuery("Error Response JS -> Kotlin", queryId, type, toJSON(error))
                        onFailure?.invoke(Exception(error.toString()))
                    } else {
                        val data = if (response.contains(RESPONSE_KEY)) response[RESPONSE_KEY] else Unit
                        logQuery("Response JS -> Kotlin", queryId, type, toJSONOrNull(data))
                        onSuccess?.invoke(data)
                    }
                } catch (error: Throwable) {
                    logError("ITMMessenger.handleQueryResponse exception: $error")
                    onFailure?.invoke(error)
                }
            }
        } catch (error: Throwable) {
            // Note: the only way it should be possible to get here is if invalid data is sent from
            // TypeScript (by not using Messenger.query). But if we do get here, responseString
            // does not contain valid data, so there's nothing we can do.
            logError("ITMMessenger.handleQueryResponse exception: $error")
        }
    }

    /**
     * Called by a [MessageHandler] to indicate success. This sends the message's response back to
     * [webView].
     *
     * @param queryId The query ID for the message.
     * @param type The type of the message.
     * @param result The arbitrary result to send back to [webView]. If this value is not of type
     * [Unit] and cannot be converted to JSON, this will throw an exception, which will then
     * result in an error being set back to [webView].
     */
    private fun <O> handleMessageSuccess(queryId: Int, type: String, result: O) {
        val resultJSON = if (result is Unit) null else toJSON(result)
        logQuery("Response Kotlin -> JS", queryId, type, resultJSON)
        mainScope.launch {
            val message = JSONObject()
            if (resultJSON != null)
                message.put("response", resultJSON.value)
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$QUERY_RESPONSE_NAME$queryId('$dataString')", null)
        }
    }

    /**
     * Called when a query is received whose query name does not have a registered handler. This
     * sends a response to [webView] indicating that the message is unhandled.
     *
     * @param queryId The query ID for the message.
     */
    private fun handleUnhandledMessage(queryId: Int) {
        mainScope.launch {
            val jsonString = "{\"unhandled\":true}"
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$QUERY_RESPONSE_NAME$queryId('$dataString')", null)
        }
    }


    /**
     * Called when a query produces an error. The error will be sent back to [webView].
     *
     * @param queryId The query ID for the message.
     * @param type The type of the message.
     * @param error The error to send back to [webView].
     */
    private fun handleMessageFailure(queryId: Int, type: String, error: Throwable) {
        logQuery("Error Response Kotlin -> JS", queryId, type, null)
        mainScope.launch {
            val message = JSONObject()
            message.put("error", if (error.message != null) JSONValue(error.message) else JSONObject())
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$QUERY_RESPONSE_NAME$queryId('$dataString')", null)
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
        val prettyDataString = try {
            data?.toPrettyString() ?: "<void>"
        } catch (_: Throwable) {
            "<error>"
        }
        logQuery(title, "WVID$queryId", type, prettyDataString)
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
        if (!isLoggingEnabled || unloggedQueryTypes.contains(type)) return
        if (isFullLoggingEnabled) {
            logInfo("ITMMessenger [$title] $queryTag: $type\n${prettyDataString ?: "null"}")
        } else {
            logInfo("ITMMessenger [$title] $queryTag: $type")
        }
    }

    /**
     * Log an error message using [logger]. If [logger] is `null`, nothing is logged.
     *
     * @param message Error message to log.
     */
    private fun logError(message: String) {
        logger?.log(ITMLogger.Severity.Error, message)
    }

    /**
     * Log an info message using [logger]. If [logger] is `null`, nothing is logged.
     *
     * @param message Info message to log.
     */
    private fun logInfo(message: String) {
        logger?.log(ITMLogger.Severity.Info, message)
    }

    //endregion
    //region Public API

    /**
     * Send a message to [webView], and ignore any possible result.
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
     * Send a message with no data to [webView], and ignore any possible result.
     *
     * @param type Query type.
     */
    fun send(type: String) {
        send(type, Unit)
    }

    /**
     * Send query to [webView] and send the result to success and/or failure callbacks.
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the type you use for [O]
     * does not match the type of the data returned by [webView], [failure] will be called with an
     * error indicating that.
     *
     * @param type Query type.
     * @param data Optional request data to send. If this is [Unit], no request data will be sent.
     * The `query` overload with no `data` parameter does this.
     * @param success Success callback called with result data from [webView].
     * @param failure Failure callback called when [webView] returns an error from the query.
     */
    fun <I, O> query(type: String, data: I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback? = null) {
        // Ensure that evaluateJavascript() is called from main scope
        mainScope.launch {
            // Wait until the TS code is ready to receive messages.
            frontendLaunchJob.join()
            val queryId = queryIdCounter.incrementAndGet()
            val dataValue = toJSONOrNull(data)
            logQuery("Request Kotlin -> JS", queryId, type, dataValue)
            val dataString = Base64.encodeToString((dataValue?.toString().orEmpty()).toByteArray(), Base64.NO_WRAP)
            try {
                @Suppress("UNCHECKED_CAST")
                pendingQueries[queryId] = PendingQueryInfo(type, success as? ITMSuccessCallback<Any?>, failure)
                webView?.evaluateJavascript("$QUERY_NAME('$type', $queryId, '$dataString')", null)
            } catch (error: Throwable) {
                failure?.invoke(error)
            }
        }
    }

    /**
     * Send query with no data to [webView] and send the result to success and/or failure callbacks.
     *
     * __Note__: The [O] type must be JSON-compatible. JSON-compatible types are documented in
     * [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always use [List] for
     * array-like types and [Map] for object-like types. If the type you use for [O] does not match
     * the type of the data returned by [webView], [failure] will be called with an error indicating
     * that.
     *
     * @param type Query type.
     * @param success Success callback called with result data from [webView].
     * @param failure Failure callback called when [webView] returns an error from the query.
     */
    fun <O> query(type: String, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback? = null) =
        query(type, Unit, success, failure)

    /**
     * Add a handler for queries from [webView].
     *
     * __Note__: Both the [I] and [O] types must be JSON-compatible. JSON-compatible types are
     * documented in [toJson][com.github.itwin.mobilesdk.jsonvalue.toJSON]. Additionally, always
     * use [List] for array-like types and [Map] for object-like types. If the data sent from the
     * web view does not match the type specified for [I], an error response will be sent to the web
     * view indicating that.
     *
     * @param type Query type.
     * @param callback Function called to respond to query. Call `success` param upon success, or
     * `failure` param upon error.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler].
     *
     * @see [removeHandler]
     */
    fun <I, O> registerQueryHandler(type: String, callback: ITMQueryCallback<I, O>): ITMHandler {
        val handler = MessageHandler<I, O>(type, this) { data, success, failure ->
            callback.invoke(data, success, failure)
        }
        handlers[type] = handler
        return handler
    }

    /**
     * Remove the specified [ITMHandler].
     *
     * @param handler The handler to remove.
     *
     * @see [registerQueryHandler]
     */
    fun removeHandler(handler: ITMHandler?) {
        if (handler is MessageHandler<*, *>) {
            handlers.remove(handler.type)
        }
    }

    /**
     * Must be called after the frontend has successfully launched, indicating that the frontend is
     * ready to receive queries.
     */
    fun frontendLaunchSucceeded() {
        frontendLaunchJob.complete()
    }

    /**
     * Indicates if the frontend launch has completed.
     */
    val isFrontendLaunchComplete: Boolean
        get() = frontendLaunchJob.isCompleted

    /**
     * Must be called if the frontend fails to launch. This prevents any queries from being sent to
     * the web view.
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        frontendLaunchJob.completeExceptionally(error)
    }

    //endregion
}
