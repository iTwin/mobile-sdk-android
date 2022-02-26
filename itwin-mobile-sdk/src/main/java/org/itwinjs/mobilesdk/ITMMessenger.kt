package org.itwinjs.mobilesdk

import android.util.Base64
import android.webkit.JavascriptInterface
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

typealias ITMQueryCallback = (JsonValue?, success: ((JsonValue?) -> Unit)?, failure: (() -> Unit)?) -> Unit
typealias ITMSuccessCallback = (JsonValue?) -> Unit
typealias ITMFailureCallback = (Exception) -> Unit

open class ITMMessenger(private val ITMApplication: ITMApplication) {
    interface ITMListener
    private val webView = ITMApplication.webView
    private val frontendLaunchJob = Job()
    private val mainScope = MainScope()
    private val pendingQueries: MutableMap<Int, Pair<ITMSuccessCallback?, ITMFailureCallback?>> = mutableMapOf()
    private val listeners: MutableMap<String, MessageListener> = mutableMapOf()

    private class MessageListener(val type: String, private val ITMMessenger: ITMMessenger, private val callback: ITMQueryCallback) : ITMListener {
        fun handleMessage(queryId: Int, data: JsonValue?) {
            ITMMessenger.logQuery("Request JS -> Kotlin", queryId, type, data)
            callback.invoke(data, { result ->
                if (result != null) {
                    ITMMessenger.handleMessageSuccess(queryId, result)
                }
            }, {
                ITMMessenger.handleMessageFailure(queryId)
            })
        }
    }

    companion object {
        /**
         * Whether or not full logging of all messages (with their optional bodies) is enabled.
         * WARNING - You should only enable this in debug builds, since message bodies may contain private information.
         */
        var isFullLoggingEnabled = false

        // queryIdCounter is static so that IDs would not be reused between ITMMessenger instances
        private val queryIdCounter = AtomicInteger(0)
        private const val queryIdKey = "queryId"
        private const val nameKey = "name"
        private const val messageKey = "message"
        private const val responseKey = "response"
        private const val errorKey = "error"
        private const val queryName = "window.Bentley_ITMMessenger_Query"
        private const val queryResponseName = "window.Bentley_ITMMessenger_QueryResponse"
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

    private fun handleQuery(messageString: String) {
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
                logError("Unhandled query [JS -> Kotlin] WKID$queryId: $name")
                handleMessageFailure(queryId)
            }
        } catch (e: Exception) {
            logError("ITMMessenger.handleQuery exception: $e")
            queryId?.let { handleMessageFailure(it) }
        }
    }

    private fun handleQueryResponse(responseString: String) {
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

    private fun handleMessageSuccess(queryId: Int, result: JsonValue) {
        logQuery("Response Kotlin -> JS", queryId, null, result)
        mainScope.launch {
            val jsonString = result.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    private fun handleMessageFailure(queryId: Int) {
        logQuery("Error Response Kotlin -> JS", queryId, null, null)
        mainScope.launch {
            webView?.evaluateJavascript("$queryResponseName$queryId()", null)
        }
    }

    private fun logQuery(title: String, queryId: Int, type: String?, data: JsonValue?) {
        val prettyDataString = try {
            data?.toPrettyString()
        } finally {
        }

        logQuery(title, "WKID$queryId", type, prettyDataString)
    }

    /**
     * Log the given query using `logInfo`.
     * @param title title to show along with the logged message.
     * @param queryTag query identifier, prefix + query ID.
     * @param type type of the query.
     * @param prettyDataString pretty-printed JSON representation of the query data.
     */
    fun logQuery(title: String, queryTag: String, type: String?, prettyDataString: String?) {
        val typeString = type ?: "(Match ID from Request above)"
        if (isFullLoggingEnabled) {
            logInfo("ITMMessenger [$title] $queryTag: $typeString\n${prettyDataString ?: "null"}")
        } else {
            logInfo("ITMMessenger [$title] $queryTag: $typeString")
        }
    }

    /**
     * Log an error message using parent `ITMApplication` logger.
     * @param message error message to log.
     */
    fun logError(message: String) {
        ITMApplication.logger.log(ITMLogger.Severity.Error, message)
    }

    /**
     * Log an info message using parent `ITMApplication` logger.
     * @param message info message to log.
     */
    fun logInfo(message: String) {
        ITMApplication.logger.log(ITMLogger.Severity.Info, message)
    }

    /**
     * Send message to ModelWebApp, and ignore any possible result.
     */
    open fun send(type: String, data: JsonValue? = null) {
        return query(type, data, null)
    }

    /**
     * Send query to ModelWebApp and send result to success and/or failure callbacks.
     * @param type message type.
     * @param data optional request data to send.
     * @param success callback called with result data from ModelWebApp.
     * @param failure callback called when ModelWebApp returns an error from the query.
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
     * Add a listener for queries from ModelWebApp.
     * @param type message type.
     * @param callback function called when a message is received.
     * @return The [ITMMessenger.ITMListener] value to subsequently pass into [removeListener]
     */
    open fun addMessageListener(type: String, callback: ITMSuccessCallback): ITMListener {
        return addQueryListener(type) { value, _, _ ->
            callback.invoke(value)
        }
    }

    /**
     * Add a listener for queries from ModelWebApp.
     * @param type message type.
     * @param callback function called to respond to query. Call success param upon success, or failure param upon error.
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
     * Remove the specified [[ITMListener]].
     * @param listener the listener to remove.
     */
    open fun removeListener(listener: ITMListener?) {
        if (listener is MessageListener) {
            listeners.remove(listener.type)
        }
    }

    /**
     * Called after the frontend has successfully launched, indicating that any queries that are sent to TypeScript will be received.
     */
    open fun frontendLaunchSucceeded() {
        frontendLaunchJob.complete()
    }

    /**
     * Called if the frontend fails to launch. This prevents any queries from being sent to TypeScript.
     */
    open fun frontendLaunchFailed(exception: Exception) {
        frontendLaunchJob.completeExceptionally(exception)
    }
}
