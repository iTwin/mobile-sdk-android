package com.bentley.itmnativeui

import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.Exception
import java.util.concurrent.atomic.AtomicInteger

typealias ITMQueryCallback = (JsonValue?, success: ((JsonValue?) -> Unit)?, failure: (() -> Unit)?) -> Unit
typealias ITMSuccessCallback = (JsonValue?) -> Unit
typealias ITMFailureCallback = (Exception) -> Unit

open class ITMMessenger(itmApplication: ITMApplication) {
    interface ITMListener
    private val webView = itmApplication.webView
    private val mainScope = MainScope()
    private val pendingQueries: MutableMap<Int, Pair<ITMSuccessCallback?, ITMFailureCallback?>> = mutableMapOf()
    private val listeners: MutableMap<String, MessageListener> = mutableMapOf()

    private class MessageListener(val type: String, private val itmMessenger: ITMMessenger, private val callback: ITMQueryCallback) : ITMListener {
        fun handleMessage(queryId: Int, data: JsonValue?) {
            callback.invoke(data, { result ->
                if (result != null) {
                    itmMessenger.handleMessageSuccess(queryId, result)
                }
            }, {
                itmMessenger.handleMessageFailure(queryId)
            })
        }
    }

    companion object {
        // queryIdCounter is static so that IDs would not be reused between ITMMessenger instances
        private val queryIdCounter = AtomicInteger(0)
        private val TAG = ITMMessenger::class.java.simpleName
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
                handleMessageFailure(queryId)
            }
        } catch (e: Exception) {
            // Note: We cannot use Bentley's NativeLogging here, because this class is going to be public.
            Log.e(TAG, "ITMMessenger.handleQuery exception: $e")
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
                    onFailure?.invoke(Exception(error.toString()))
                } ?: run {
                    onSuccess?.invoke(response[responseKey] ?: Json.NULL)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ITMMessenger.handleQueryResponse exception: $e")
        }
    }

    private fun handleMessageSuccess(queryId: Int, result: JsonValue) {
        mainScope.launch {
            val jsonString = result.toString()
            val dataString = Base64.encodeToString(jsonString.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$queryResponseName$queryId('$dataString')", null)
        }
    }

    private fun handleMessageFailure(queryId: Int) {
        mainScope.launch {
            webView?.evaluateJavascript("$queryResponseName$queryId()", null)
        }
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
            val dataString = Base64.encodeToString(data.toString().toByteArray(), Base64.NO_WRAP)
            val queryId = queryIdCounter.incrementAndGet()
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
}