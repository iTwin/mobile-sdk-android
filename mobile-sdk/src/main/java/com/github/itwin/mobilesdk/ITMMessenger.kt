/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk

import android.util.Base64
import android.webkit.JavascriptInterface
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import com.github.itwin.mobilesdk.jsonvalue.toPrettyString
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

typealias ITMQueryCallback = (JsonValue?, success: ((JsonValue?) -> Unit)?, failure: ((Exception) -> Unit)?) -> Unit
typealias ITMSuccessCallback = (JsonValue?) -> Unit
typealias ITMFailureCallback = (Exception) -> Unit

/**
 * Class for sending and receiving messages to and from a [WebView][android.webkit.WebView] using the
 * `Messenger` class in `@itwin/mobile-sdk-core`.
 *
 * @param itmApplication The [ITMApplication] that will be sending and receiving messages.
 */
open class ITMMessenger(private val itmApplication: ITMApplication) {
    /**
     * Empty interface used for message listeners.
     */
    interface ITMListener

    /**
     * The [WebView][android.webkit.WebView] with which this [ITMMessenger] communicates.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected val webView = itmApplication.webView

    /**
     * [Job] indicating that the frontend running in the web view is ready to receive messages. All calls to
     * [send] and [query] will wait for this to complete before sending the message.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected val frontendLaunchJob = Job()

    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected val mainScope = MainScope()

    /**
     * Active queries that are waiting for a response from the web view. The key is the query ID that was
     * sent to the web view (which will be present in the response). The value is a [Pair] containing
     * optional success and failure callbacks.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected val pendingQueries: MutableMap<Int, Pair<ITMSuccessCallback?, ITMFailureCallback?>> = mutableMapOf()

    /**
     * Listeners waiting for queries from the web view. The key is the query name, and the value is the listener.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected val listeners: MutableMap<String, MessageListener> = mutableMapOf()

    /**
     * Class for handling queries from the web view.
     *
     * @param type The query name to listen for.
     * @param itmMessenger The [ITMMessenger] listening for the query.
     * @param callback The [ITMQueryCallback] callback object for the query.
     */
    protected open class MessageListener(val type: String, private val itmMessenger: ITMMessenger, private val callback: ITMQueryCallback) : ITMListener {
        /**
         * Function that is called when a query is received of the specified [type].
         *
         * If you override this function without calling super, you must invoke the callback and call
         * [itmMessenger].[handleMessageSuccess] or [itmMessenger].[handleMessageFailure].
         *
         * @param queryId The query ID of the query.
         * @param data Optional arbitrary message data.
         */
        open fun handleMessage(queryId: Int, data: JsonValue?) {
            itmMessenger.logQuery("Request JS -> Kotlin", queryId, type, data)
            callback.invoke(data, { result ->
                if (result != null) {
                    itmMessenger.handleMessageSuccess(queryId, result)
                }
            }, { error ->
                itmMessenger.handleMessageFailure(queryId, error)
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
         * Counter to increment and use when sending a message to the web view.
         *
         * __Note:__ This is static so that IDs would not be reused between ITMMessenger instances.
         */
        protected val queryIdCounter = AtomicInteger(0)

        /**
         * JSON key used for the query ID parameter of messages.
         */
        protected const val queryIdKey = "queryId"

        /**
         * JSON key used for the name parameter of received messages.
         */
        protected const val nameKey = "name"

        /**
         * JSON key used for the message parameter of received messages.
         */
        protected const val messageKey = "message"

        /**
         * JSON key used for the response parameter of sent messages.
         */
        protected const val responseKey = "response"

        /**
         * JSON key used for the error parameter of sent messages.
         */
        protected const val errorKey = "error"

        /**
         * The function name use in injected JavaScript when sending messages.
         */
        protected const val queryName = "window.Bentley_ITMMessenger_Query"

        /**
         * The function name use in injected JavaScript when sending query responses.
         */
        private const val queryResponseName = "window.Bentley_ITMMessenger_QueryResponse"

        /**
         * The name of the JavascriptInterface class by the `Messenger` class in `@itwin/mobile-sdk-core`.
         */
        private const val jsInterfaceName = "Bentley_ITMMessenger"
    }

    init {
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
     * Called when a query is received from the web view.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     */
    protected open fun handleQuery(messageString: String) {
        var queryId: Int? = null
        try {
            val requestValue = Json.parse(messageString)
            if (!requestValue.isObject)
                return
            val request = requestValue.asObject()
            if (!request[nameKey].isString || !request[queryIdKey].isNumber)
                return
            queryId = request[queryIdKey].asInt()
            val name = request[nameKey].asString()
            val listener = listeners[name]
            if (listener != null) {
                listener.handleMessage(queryId, request[messageKey])
            } else {
                @Suppress("SpellCheckingInspection")
                logError("Unhandled query [JS -> Kotlin] WVID$queryId: $name")
                handleUnhandledMessage(queryId)
            }
        } catch (e: Exception) {
            logError("ITMMessenger.handleQuery exception: $e")
            queryId?.let { handleMessageFailure(it, e) }
        }
    }

    /**
     * Called when a query response is received from the web view.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     */
    protected open fun handleQueryResponse(responseString: String) {
        try {
            val responseValue = Json.parse(responseString)
            if (!responseValue.isObject || !responseValue.asObject()[queryIdKey].isNumber)
                return

            val response = responseValue.asObject()
            val queryId = response[queryIdKey].asInt()
            pendingQueries.remove(queryId)?.let {
                val (onSuccess, onFailure) = it

                response[errorKey]?.also { error ->
                    logQuery("Error Response JS -> Kotlin", queryId, null, error)
                    onFailure?.invoke(Exception(error.toString()))
                } ?: run {
                    val data = response[responseKey] ?: Json.NULL
                    logQuery("Response JS -> Kotlin", queryId, null, data)
                    onSuccess?.invoke(data)
                }
            }
        } catch (e: Exception) {
            logError("ITMMessenger.handleQueryResponse exception: $e")
        }
    }

    /**
     * Called by a [MessageListener] to indicate success.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     *
     * @param queryId The query ID for the message.
     * @param result The arbitrary result to send back to the web view.
     */
    protected open fun handleMessageSuccess(queryId: Int, result: JsonValue) {
        logQuery("Response Kotlin -> JS", queryId, null, result)
        mainScope.launch {
            val message = Json.`object`()
            message["response"] = result
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    /**
     * Called when a query is received whose query name does not have a registered listener.
     *
     * __Note:__ If you plan to override this without calling super, you need to inspect this source code.
     *
     * @param queryId The query ID for the message.
     */
    protected open fun handleUnhandledMessage(queryId: Int) {
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
     * @param error The error to send back to the web view.
     */
    protected open fun handleMessageFailure(queryId: Int, error: Exception) {
        logQuery("Error Response Kotlin -> JS", queryId, null, null)
        mainScope.launch {
            val message = Json.`object`()
            message["error"] = error.message
            val jsonString = message.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    /**
     * Called to log a query. Converts [data] into a string and then calls [logQuery].
     */
    protected open fun logQuery(title: String, queryId: Int, type: String?, data: JsonValue?) {
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
    protected open fun logQuery(title: String, queryTag: String, type: String?, prettyDataString: String?) {
        if (!isLoggingEnabled) return
        val typeString = type ?: "(Match ID from Request above)"
        if (isFullLoggingEnabled) {
            logInfo("ITMMessenger [$title] $queryTag: $typeString\n${prettyDataString ?: "null"}")
        } else {
            logInfo("ITMMessenger [$title] $queryTag: $typeString")
        }
    }

    /**
     * Log an error message using [itmApplication] logger.
     *
     * @param message Error message to log.
     */
    protected open fun logError(message: String) {
        itmApplication.logger.log(ITMLogger.Severity.Error, message)
    }

    /**
     * Log an info message using [itmApplication] logger.
     *
     * @param message Info message to log.
     */
    protected open fun logInfo(message: String) {
        itmApplication.logger.log(ITMLogger.Severity.Info, message)
    }

    /**
     * Send a message to the web view, and ignore any possible result.
     *
     * @param type Query type.
     * @param data Optional request data to send.
     */
    open fun send(type: String, data: JsonValue? = null) {
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
    open fun query(type: String, data: JsonValue?, success: ITMSuccessCallback?, failure: ITMFailureCallback? = null) {
        // Ensure that evaluateJavascript() is called from main scope
        mainScope.launch {
            frontendLaunchJob.join()
            val queryId = queryIdCounter.incrementAndGet()
            logQuery("Request Kotlin -> JS", queryId, type, data)
            val dataString = Base64.encodeToString(data.toString().toByteArray(), Base64.NO_WRAP)
            pendingQueries[queryId] = Pair(success, failure)
            webView?.evaluateJavascript("$queryName('$type', $queryId, '$dataString')", null)
        }
    }

    /**
     * Add a listener for queries from the web view that do not include a response.
     *
     * @param type Query type.
     * @param callback Function called when a message is received.
     *
     * @return The [ITMMessenger.ITMListener] value to subsequently pass into [removeListener]
     */
    open fun addMessageListener(type: String, callback: ITMSuccessCallback): ITMListener {
        return addQueryListener(type) { value, _, _ ->
            callback.invoke(value)
        }
    }

    /**
     * Add a listener for queries from the web view that include a response.
     *
     * @param type Query type.
     * @param callback Function called to respond to query. Call success param upon success, or failure param upon error.
     *
     * @return The [ITMMessenger.ITMListener] value to subsequently pass into [removeListener]
     */
    open fun addQueryListener(type: String, callback: ITMQueryCallback): ITMListener {
        val listener = MessageListener(type, this) { data, success, failure ->
            callback.invoke(data, success, failure)
        }
        listeners[type] = listener
        return listener
    }

    /**
     * Remove the specified [ITMListener].
     *
     * @param listener The listener to remove.
     */
    open fun removeListener(listener: ITMListener?) {
        if (listener is MessageListener) {
            listeners.remove(listener.type)
        }
    }

    /**
     * Called after the frontend has successfully launched, indicating that any queries that are sent to the web view
     * that are waiting to be sent can be sent.
     */
    open fun frontendLaunchSucceeded() {
        frontendLaunchJob.complete()
    }

    /**
     * Indicates if the frontend launch has completed.
     */
    open val isFrontendLaunchComplete: Boolean
        get() = frontendLaunchJob.isCompleted

    /**
     * Called if the frontend fails to launch. This prevents any queries from being sent to the web view.
     *
     * @param exception The reason for the failure.
     */
    open fun frontendLaunchFailed(exception: Exception) {
        frontendLaunchJob.completeExceptionally(exception)
    }
}
