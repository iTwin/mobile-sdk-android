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
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.ERROR_KEY
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl.Companion.RESPONSE_KEY
import com.github.itwin.mobilesdk.messaging.ITMMessengerLoggingOptions
import com.github.itwin.mobilesdk.messaging.ITMMessengerProtocol
import com.github.itwin.mobilesdk.messaging.ITMQueryHandler
import com.github.itwin.mobilesdk.messaging.ITMQueryResponse
import com.github.itwin.mobilesdk.messaging.ITMQueryTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

typealias ITMSuccessCallback<T> = (T) -> Unit
typealias ITMFailureCallback = (Throwable) -> Unit
typealias ITMQueryCallback<I, O> = (I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback?) -> Unit

/**
 * Class for sending and receiving messages to and from a [WebView][android.webkit.WebView] using
 * the `Messenger` class in `@itwin/mobile-sdk-core`.
 *
 * @param logger The [ITMLogger] to use for logging. If this is `null`, no logging happens.
 */
class ITMMessenger(logger: ITMLogger? = null): ITMMessengerProtocol {
    /**
     * Empty interface used for message handlers. Alias to [ITMQueryHandler] kept for backwards compatibility.
     */
    typealias ITMHandler = ITMQueryHandler

    /**
     * Transport that passes data to and from frontend running in a WebView.
     */
    private val frontendTransport: ITMFrontendQueryTransport
        get() = implementation.transport as ITMFrontendQueryTransport

    /**
     * Implementation of [ITMMessengerProtocol] to which this messenger delegates to.
     */
    private val implementation = ITMMessengerImpl("JS", "WVID", logger)

    init {
        val transport = ITMFrontendQueryTransport(implementation)
        implementation.transport = transport
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
     * The [WebView][android.webkit.WebView] with which this [ITMMessenger] communicates.
     */
    var webView: WebView?
        get() = frontendTransport.webView
        set(value) {
            frontendTransport.webView = value
        }

    /**
     * [Job] indicating that the frontend running in [webView] is ready to receive messages. All
     * calls to [send] and [query] will wait for this to complete before sending the message.
     */
    internal val frontendLaunchJob: Job
        get() = implementation.launchDeferred

    //region Companion Object

    companion object {
        /**
         * Whether or not logging of all messages is enabled.
         *
         * > __Note:__ This is shared with [com.github.itwin.mobilesdk.messaging.ITMBackendMessenger].
         */
        var isLoggingEnabled: Boolean
            get() = ITMMessengerLoggingOptions.isLoggingEnabled
            set(value) {
                ITMMessengerLoggingOptions.isLoggingEnabled = value
            }

        /**
         * Whether or not full logging of all messages (with their optional bodies) is enabled.
         *
         * __WARNING:__ You should only enable this in debug builds, since message bodies may
         * contain private information.
         *
         * > __Note:__ This is shared with [com.github.itwin.mobilesdk.messaging.ITMBackendMessenger].
         */
        var isFullLoggingEnabled: Boolean
            get() = ITMMessengerLoggingOptions.isFullLoggingEnabled
            set(value) {
                ITMMessengerLoggingOptions.isFullLoggingEnabled = value
            }

        /**
         * Add a query type to the list of unlogged queries.
         *
         * @see [ITMMessengerLoggingOptions.addUnloggedQueryType]
         */
        fun addUnloggedQueryType(type: String) = ITMMessengerLoggingOptions.addUnloggedQueryType(type)

        /**
         * Remove a query type from the list of unlogged queries.
         *
         * @see [ITMMessengerLoggingOptions.removeUnloggedQueryType]
         */
        fun removeUnloggedQueryType(type: String) = ITMMessengerLoggingOptions.removeUnloggedQueryType(type)
    }

    //endregion
    //region Public API

    /**
     * Must be called after the frontend has successfully launched, indicating that the frontend is
     * ready to receive queries.
     */
    fun frontendLaunchSucceeded() {
        implementation.launchSucceeded()
    }

    /**
     * Indicates if the frontend launch has completed.
     */
    val isFrontendLaunchComplete: Boolean
        get() = implementation.isLaunchComplete

    /**
     * Must be called if the frontend fails to launch. This prevents any queries from being sent to
     * the web view.
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        implementation.launchFailed(error)
    }

    override fun <I, O> query(
        type: String,
        data: I,
        success: ITMSuccessCallback<O>?,
        failure: ITMFailureCallback?
    ) {
        implementation.query(type, data, success, failure)
    }

    override fun <I, O> registerQueryHandler(
        type: String,
        callback: ITMQueryCallback<I, O>
    ): ITMQueryHandler {
        return implementation.registerQueryHandler(type, callback)
    }

    override fun removeHandler(handler: ITMQueryHandler?) {
        implementation.removeHandler(handler)
    }

    //endregion
}

/**
 * [com.github.itwin.mobilesdk.messaging.ITMQueryTransport] that communicates with a [WebView] using the `Messenger` class in
 * `@itwin/mobile-sdk-core`.
 */
internal class ITMFrontendQueryTransport(override val delegate: ITMQueryTransport.Delegate) : ITMQueryTransport {
    /**
     * Active queries that are waiting for a response. The key is the query ID that was sent (which
     * will be present in the response). The value is a deferred that will be completed upon receiving a response.
     */
    private val pendingQueries: MutableMap<Int, CompletableDeferred<JSONValue>> = ConcurrentHashMap()

    /**
     * Convenience property with a value of [MainScope()][MainScope]
     */
    private val mainScope = MainScope()

    /**
     * The [WebView] with which this transport communicates.
     */
    var webView: WebView? = null
        set(value) {
            field = value
            value?.addJavascriptInterface(object {
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

    companion object {
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
    }

    override suspend fun sendQuery(queryId: Int, type: String, data: String?): ITMQueryResponse {
        val responseDeferred = CompletableDeferred<JSONValue>()
            .also { pendingQueries[queryId] = it }

        val dataString = Base64.encodeToString((data.orEmpty()).toByteArray(), Base64.NO_WRAP)
        webView?.evaluateJavascript("$QUERY_NAME('$type', $queryId, '$dataString')", null)

        val response = responseDeferred.await()
        pendingQueries.remove(queryId)

        val responseMap = response.anyValue as Map<*, *>
        val error = responseMap[ERROR_KEY]
        return if (error != null) {
            ITMQueryResponse.Failure(Exception(error.toString()))
        } else {
            ITMQueryResponse.Success(if (responseMap.contains(RESPONSE_KEY)) responseMap[RESPONSE_KEY] else Unit)
        }
    }

    private fun sendResponse(queryId: Int, response: String) {
        mainScope.launch {
            val dataString = Base64.encodeToString(response.toByteArray(), Base64.NO_WRAP)
            webView?.evaluateJavascript("$QUERY_RESPONSE_NAME$queryId('$dataString')", null)
        }
    }

    /**
     * Called when a query is received from [webView].
     *
     * > __Note:__ If [messageString] is malformed, an error will be logged. The error will also be
     * sent back to [webView] as long as [messageString] contains a valid `queryId` field.
     *
     * @param messageString The JSON message string sent by [webView].
     */
    private fun handleQuery(messageString: String) {
        mainScope.launch {
            var queryId: Int? = null
            try {
                // Note: if there is anything wrong with messageString, it will trigger an exception,
                // which will be logged and sent back to TS as an error.
                val request = JSONValue.fromJSON(messageString)
                queryId = (request[QUERY_ID_KEY] as Number).toInt()
                val name = request[NAME_KEY] as String
                val response = delegate.onQuery(queryId, name, toJSON(request.opt(MESSAGE_KEY)))
                sendResponse(queryId, response)
            } catch (error: Throwable) {
                delegate.logger?.error("ITMMessenger.handleQuery exception: $error")
                queryId?.let { sendResponse(it, ITMQueryResponse.Failure(error).toJSONString()) }
            }
        }
    }

    /**
     * Called when a query response is received from [webView].
     *
     * > __Note:__ If [responseString] is malformed, this will log an error. Since this gets called
     * in response to a response message being sent by [webView], and [webView] isn't expecting any
     * response to its response, there is nothing further that can be done.
     *
     * @param responseString The JSON response string sent by [webView].
     */
    private fun handleQueryResponse(responseString: String) {
        try {
            // Note: if there is anything wrong with responseString, it will trigger an exception,
            // which will be logged.
            val response = JSONValue.fromJSON(responseString)
            val queryId = (response[QUERY_ID_KEY] as Number).toInt()
            pendingQueries[queryId]?.complete(response)
        } catch (error: Throwable) {
            // Note: the only way it should be possible to get here is if invalid data is sent from
            // TypeScript (by not using Messenger.query). But if we do get here, responseString
            // does not contain valid data, so there's nothing we can do.
            delegate.logger?.error("ITMMessenger.handleQueryResponse exception: $error")
        }
    }
}
