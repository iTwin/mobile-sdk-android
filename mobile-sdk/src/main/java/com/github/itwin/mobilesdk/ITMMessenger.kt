/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk

import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.github.itwin.mobilesdk.jsonvalue.JSONValue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject

typealias ITMSuccessCallback = (JSONValue?) -> Unit
typealias ITMFailureCallback = (Throwable) -> Unit
typealias ITMQueryCallback = (JSONValue?, success: ITMSuccessCallback?, failure: ITMFailureCallback?) -> Unit

/**
 * Class for sending and receiving messages to and from a [WebView][android.webkit.WebView] using the
 * `Messenger` class in `@itwin/mobile-sdk-core`.
 *
 * @param itmApplication The [ITMApplication] that will be sending and receiving messages.
 */
class ITMMessenger(private val itmApplication: ITMApplication) {
    /**
     * Empty interface used for message handlers.
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
            }, jsInterfaceName)
        }

    /**
     * [Job] indicating that the frontend running in the web view is ready to receive messages. All calls to
     * [send] and [query] will wait for this to complete before sending the message.
     */
    internal val frontendLaunchJob = Job()

    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    private val mainScope = MainScope()

    /**
     * Active queries that are waiting for a response from the web view. The key is the query ID that was
     * sent to the web view (which will be present in the response). The value is a [Pair] containing
     * optional success and failure callbacks.
     */
    private val pendingQueries: MutableMap<Int, Triple<String, ITMSuccessCallback?, ITMFailureCallback?>> = mutableMapOf()

    /**
     * Handlers waiting for queries from the web view. The key is the query name, and the value is the handler.
     */
    private val handlers: MutableMap<String, MessageHandler> = mutableMapOf()

    /**
     * Class for handling queries from the web view.
     *
     * @param type The query name to listen for.
     * @param itmMessenger The [ITMMessenger] listening for the query.
     * @param callback The [ITMQueryCallback] callback object for the query.
     */
    private class MessageHandler(val type: String, private val itmMessenger: ITMMessenger, private val callback: ITMQueryCallback) : ITMHandler {
        /**
         * Function that is called when a query is received of the specified [type].
         *
         * If you override this function without calling super, you must invoke the callback and call
         * [itmMessenger].[handleMessageSuccess] or [itmMessenger].[handleMessageFailure].
         *
         * @param queryId The query ID of the query.
         * @param type The type of the query.
         * @param data Optional arbitrary message data.
         */
        fun handleMessage(queryId: Int, type: String, data: JSONValue?) {
            itmMessenger.logQuery("Request JS -> Kotlin", queryId, type, data)
            callback.invoke(data, { result ->
                itmMessenger.handleMessageSuccess(queryId, type, result)
            }, { error ->
                itmMessenger.handleMessageFailure(queryId, type, error)
            })
        }
    }

    companion object {
        /**
         * Whether or not logging of all messages is enabled.
         */
        var isLoggingEnabled = false

        /**
         * Whether or not full logging of all messages (with their optional bodies) is enabled.
         *
         * __WARNING:__ You should only enable this in debug builds, since message bodies may contain private information.
         */
        var isFullLoggingEnabled = false

        /**
         * Set containing query types that are not logged.
         */
        private val unloggedQueryTypes: MutableSet<String> = mutableSetOf()

        /**
         * Counter to increment and use when sending a message to the web view.
         *
         * __Note:__ This is static so that IDs would not be reused between ITMMessenger instances.
         */
        private val queryIdCounter = AtomicInteger(0)

        /**
         * JSON key used for the query ID parameter of messages.
         */
        private const val queryIdKey = "queryId"

        /**
         * JSON key used for the name parameter of received messages.
         */
        private const val nameKey = "name"

        /**
         * JSON key used for the message parameter of received messages.
         */
        private const val messageKey = "message"

        /**
         * JSON key used for the response parameter of sent messages.
         */
        private const val responseKey = "response"

        /**
         * JSON key used for the error parameter of sent messages.
         */
        private const val errorKey = "error"

        /**
         * The function name use in injected JavaScript when sending messages.
         */
        private const val queryName = "window.Bentley_ITMMessenger_Query"

        /**
         * The function name use in injected JavaScript when sending query responses.
         */
        private const val queryResponseName = "window.Bentley_ITMMessenger_QueryResponse"

        /**
         * The name of the JavascriptInterface class by the `Messenger` class in `@itwin/mobile-sdk-core`.
         */
        private const val jsInterfaceName = "Bentley_ITMMessenger"

        /**
         * Add a query type to the list of unlogged queries.
         *
         * Unlogged queries are ignored by [logQuery]. This is useful (for example) for queries that are
         * themselves intended to produce log output, to prevent double log output.
         *
         * @param type The type of the query for which logging is disabled.
         */
        @Suppress("MemberVisibilityCanBePrivate")
        fun addUnloggedQueryType(type: String) {
            unloggedQueryTypes.add(type)
        }

        /**
         * Remove a query type from the list of unlogged queries.
         *
         * See [addUnloggedQueryType].
         *
         * @param type The type of the query to remove.
         */
        fun removeUnloggedQueryType(type: String) {
            unloggedQueryTypes.remove(type)
        }
    }

    /**
     * Called when a query is received from the web view.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     */
    private fun handleQuery(messageString: String) {
        var queryId: Int? = null
        var name = "<Unknown>"
        try {
            // Note: if there is anything wrong with messageString, it will trigger an exception,
            // which will be logged and sent back to TS as an error.
            val request = JSONValue.fromJSON(messageString)
            queryId = (request[queryIdKey] as Number).toInt()
            name = request[nameKey] as String
            val handler = handlers[name]
            if (handler != null) {
                handler.handleMessage(queryId, name, JSONValue(request.opt(messageKey)))
            } else {
                @Suppress("SpellCheckingInspection")
                logError("Unhandled query [JS -> Kotlin] WVID$queryId: $name")
                handleUnhandledMessage(queryId)
            }
        } catch (error: Throwable) {
            logError("ITMMessenger.handleQuery exception: $error")
            queryId?.let { handleMessageFailure(it, name, error) }
        }
    }

    /**
     * Called when a query response is received from the web view.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     */
    private fun handleQueryResponse(responseString: String) {
        try {
            // Note: if there is anything wrong with responseString, it will trigger an exception,
            // which will be logged.
            val response = JSONValue.fromJSON(responseString)
            val queryId = (response[queryIdKey] as Number).toInt()
            pendingQueries.remove(queryId)?.let { (type, onSuccess, onFailure) ->
                try {
                    val error = response[errorKey]
                    logQuery("Error Response JS -> Kotlin", queryId, type, JSONValue(error))
                    onFailure?.invoke(Exception(error.toString()))
                } catch (_: Exception) {
                    // Note: this is actually the non-exceptional code path. If errorKey is present
                    // in the response, that means that there was a failure.
                    val data = JSONValue(response.opt(responseKey))
                    logQuery("Response JS -> Kotlin", queryId, type, data)
                    onSuccess?.invoke(data)
                }
            }
        } catch (error: Throwable) {
            logError("ITMMessenger.handleQueryResponse exception: $error")
        }
    }

    /**
     * Called by a [MessageHandler] to indicate success.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     *
     * @param queryId The query ID for the message.
     * @param type The type of the message.
     * @param result The arbitrary result to send back to the web view.
     */
    private fun handleMessageSuccess(queryId: Int, type: String, result: JSONValue?) {
        logQuery("Response Kotlin -> JS", queryId, type, result)
        mainScope.launch {
            val message = JSONObject()
            if (result != null)
                message.put("response", result.value)
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    /**
     * Called when a query is received whose query name does not have a registered handler.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     *
     * @param queryId The query ID for the message.
     */
    private fun handleUnhandledMessage(queryId: Int) {
        mainScope.launch {
            val jsonString = "{\"unhandled\":true}"
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }


    /**
     * Called when a query produces an error. The error will be sent back to the web view.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     *
     * @param queryId The query ID for the message.
     * @param type The type of the message.
     * @param error The error to send back to the web view.
     */
    private fun handleMessageFailure(queryId: Int, type: String, error: Throwable) {
        logQuery("Error Response Kotlin -> JS", queryId, type, null)
        mainScope.launch {
            val message = JSONObject()
            message.put("error", if (error.message != null) JSONValue(error.message) else JSONObject())
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    /**
     * Called to log a query. Converts [data] into a string and then calls [logQuery].
     */
    private fun logQuery(title: String, queryId: Int, type: String, data: JSONValue?) {
        val prettyDataString = try {
            data?.toPrettyString()
        } finally {
        }

        @Suppress("SpellCheckingInspection")
        logQuery(title, "WVID$queryId", type, prettyDataString)
    }

    /**
     * Log the given query using `logInfo` if [isLoggingEnabled] is set to true, or nothing otherwise.
     *
     * @param title Title to show along with the logged message.
     * @param queryTag Query identifier, prefix + query ID.
     * @param type Type of the query.
     * @param prettyDataString Pretty-printed JSON representation of the query data. If [isFullLoggingEnabled]
     * is set to false, this value is ignored.
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
     * Log an error message using [itmApplication] logger.
     *
     * @param message Error message to log.
     */
    private fun logError(message: String) {
        itmApplication.logger.log(ITMLogger.Severity.Error, message)
    }

    /**
     * Log an info message using [itmApplication] logger.
     *
     * @param message Info message to log.
     */
    private fun logInfo(message: String) {
        itmApplication.logger.log(ITMLogger.Severity.Info, message)
    }

    /**
     * Send a message to the web view, and ignore any possible result.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     */
    fun send(type: String, data: JSONValue? = null) {
        return query(type, data, null)
    }

    /**
     * Send query to the web view and send result to success and/or failure callbacks.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     * @param success Success callback called with result data from the web view.
     * @param failure Failure callback called when the web view returns an error from the query.
     */
    fun query(type: String, data: JSONValue?, success: ITMSuccessCallback?, failure: ITMFailureCallback? = null) {
        // Ensure that evaluateJavascript() is called from main scope
        mainScope.launch {
            frontendLaunchJob.join()
            val queryId = queryIdCounter.incrementAndGet()
            logQuery("Request Kotlin -> JS", queryId, type, data)
            val dataString = Base64.encodeToString(data.toString().toByteArray(), Base64.NO_WRAP)
            pendingQueries[queryId] = Triple(type, success, failure)
            webView?.evaluateJavascript("$queryName('$type', $queryId, '$dataString')", null)
        }
    }

    /**
     * Add a handler for queries from the web view that include a response.
     *
     * @param type Query type.
     * @param callback Function called to respond to query. Call success param upon success, or failure param upon error.
     *
     * @return The [ITMMessenger.ITMHandler] value to subsequently pass into [removeHandler]
     */
    fun registerQueryHandler(type: String, callback: ITMQueryCallback): ITMHandler {
        val handler = MessageHandler(type, this) { data, success, failure ->
            callback.invoke(data, success, failure)
        }
        handlers[type] = handler
        return handler
    }

    /**
     * Remove the specified [ITMHandler].
     *
     * @param handler The handler to remove.
     */
    fun removeHandler(handler: ITMHandler?) {
        if (handler is MessageHandler) {
            handlers.remove(handler.type)
        }
    }

    /**
     * Called after the frontend has successfully launched, indicating that any queries that are sent to the web view
     * that are waiting to be sent can be sent.
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
     * Called if the frontend fails to launch. This prevents any queries from being sent to the web view.
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        frontendLaunchJob.completeExceptionally(error)
    }
}
