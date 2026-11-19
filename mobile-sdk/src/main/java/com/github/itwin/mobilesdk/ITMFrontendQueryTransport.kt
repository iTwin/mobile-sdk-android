/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.github.itwin.mobilesdk.jsonvalue.toJSON
import com.github.itwin.mobilesdk.jsonvalue.toMap
import com.github.itwin.mobilesdk.messaging.ITMQueryResponse
import com.github.itwin.mobilesdk.messaging.ITMQueryTransport
import kotlinx.coroutines.*
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID

internal class ITMFrontendQueryTransport(
    override val delegate: ITMQueryTransport.Delegate,
    initialAllowedOrigins: Set<String>,
    private val sessionEnded: (Throwable, Boolean) -> Unit
) : ITMQueryTransport {
    private class DocumentSession(val origin: String) {
        val id = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val pending = mutableMapOf<Int, CompletableDeferred<ITMQueryResponse>>()
        val incoming = mutableSetOf<Int>()
        var documentId: String? = null
        var replyProxy: JavaScriptReplyProxy? = null
        var rateStart = 0L
        var rateCount = 0
    }

    private var session: DocumentSession? = null
    private var attachedWebView: WebView? = null
    private var resetLaunchOnAttach = false
    var allowedOrigins = ITMBridgeOriginPolicy.validatedOrigins(initialAllowedOrigins)
        private set
    private var loading = false
    private var hasDocument = false
    private var lastDiagnostic = -1000L

    private companion object {
        const val BRIDGE_NAME = "Bentley_ITMMessenger"
        const val MAX_PENDING = 128
        const val MAX_QUERIES_PER_SECOND = 100
        val DOCUMENT_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")
    }

    var webView: WebView?
        get() = attachedWebView
        set(value) {
            if (value == null) detach() else attach(value, allowedOrigins)
        }

    fun attach(view: WebView, origins: Set<String>) {
        val validated = ITMBridgeOriginPolicy.validatedOrigins(origins)
        check(validated.isEmpty() || WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            "Messenger requires WebView WEB_MESSAGE_LISTENER support."
        }
        if (attachedWebView === view && allowedOrigins == validated) return
        if (attachedWebView != null) detach()
        if (resetLaunchOnAttach) sessionEnded(IllegalStateException("Messenger document replaced."), true)
        resetLaunchOnAttach = true
        allowedOrigins = validated
        if (validated.isEmpty()) {
            sessionEnded(IllegalStateException("Messenger has no trusted origins."), false)
            return
        }
        attachedWebView = view
        hasDocument = false
        // WebMessageListener objects are injected into the next loaded document, not the current one.
        loading = true
        WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, validated) { source, message, origin, mainFrame, proxy ->
            val current = session
            if (source !== attachedWebView || !mainFrame || loading || current == null ||
                ITMBridgeOriginPolicy.canonicalOrigin(origin.toString()) != current.origin ||
                ITMBridgeOriginPolicy.canonicalOrigin(source.url.orEmpty()) != current.origin) {
                diagnostic("Rejected unauthorized Messenger caller.")
            } else {
                receive(message.data, current, proxy)
            }
        }
    }

    fun detach() {
        resetLaunchOnAttach = true
        invalidateSession()
        attachedWebView?.let { WebViewCompat.removeWebMessageListener(it, BRIDGE_NAME) }
        attachedWebView = null
    }

    fun onPageStarted(view: WebView) {
        if (view !== attachedWebView) return
        if (loading && session == null) return
        loading = true
        if (session != null) {
            invalidateSession(awaitingLaunch = true)
        } else if (hasDocument) {
            sessionEnded(IllegalStateException("Messenger document session ended."), true)
        }
    }

    fun onPageFinished(view: WebView) {
        if (view !== attachedWebView) return
        loading = false
        if (session != null) return
        val url = view.url ?: return
        hasDocument = true
        val origin = ITMBridgeOriginPolicy.canonicalOrigin(url)
        if (origin == null || origin !in allowedOrigins) {
            diagnostic("Messenger frontend origin is not authorized.")
            sessionEnded(IllegalStateException("Messenger frontend origin is not authorized."), false)
            return
        }
        val current = DocumentSession(origin)
        session = current
        view.evaluateJavascript(
            "(function() { if (window !== window.top) return false; " +
                "window.Bentley_ITMMessengerNativeSession = ${JSONObject.quote(current.id)}; " +
                "if (typeof window.Bentley_ITMMessengerInitialize !== 'function') return false; " +
                "window.Bentley_ITMMessengerInitialize(${JSONObject.quote(current.id)}); return true; })();"
        ) { result ->
            if (session === current && result != "true") {
                diagnostic("Messenger requires a session-aware mobile-sdk-core frontend.")
            }
        }
    }

    private fun invalidateSession(awaitingLaunch: Boolean = false) {
        val previous = session
        // Reply proxies target their originating frame, never the replacement document.
        if (previous?.replyProxy != null) send(previous, JSONObject().put("operation", "closed"))
        session = null
        val error = IllegalStateException("Messenger document session ended.")
        previous?.scope?.cancel()
        previous?.pending?.values?.forEach { it.completeExceptionally(error) }
        previous?.pending?.clear()
        sessionEnded(error, awaitingLaunch)
    }

    private fun diagnostic(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastDiagnostic < 1000) return
        lastDiagnostic = now
        Log.e("ITMMessenger", message)
    }

    private fun receive(payload: String?, current: DocumentSession, proxy: JavaScriptReplyProxy) {
        if (payload == null) {
            diagnostic("Rejected missing Messenger payload.")
            return
        }
        try {
            val parser = JSONTokener(payload)
            val request = parser.nextValue() as? JSONObject
            require(request != null && parser.nextClean() == '\u0000')
            require(request.opt("sessionId") == current.id)
            val documentId = request.opt("documentId") as? String
            require(documentId != null && DOCUMENT_ID_PATTERN.matches(documentId))
            val operation = request.opt("operation") as? String
            if (operation == "ready") {
                require(request.length() == 3 && current.documentId == null)
                current.documentId = documentId
                current.replyProxy = proxy
                send(current, JSONObject().put("operation", "ready"))
                return
            }
            require(current.documentId == documentId && current.replyProxy != null)
            if (operation == "closed") {
                require(request.length() == 3)
                invalidateSession()
                return
            }
            val number = request.opt("queryId") as? Number
            require(number != null && number.toDouble() >= 0 && number.toDouble() <= Int.MAX_VALUE &&
                number.toDouble() == number.toInt().toDouble())
            val queryId = number.toInt()
            when (operation) {
                "query" -> {
                    require(request.keys().asSequence().all { it in setOf("operation", "sessionId", "documentId", "queryId", "name", "message") })
                    val name = request.opt("name") as? String
                    require(name != null && name.isNotEmpty())
                    handleQuery(current, queryId, name, request)
                }
                "queryResponse" -> {
                    require(request.keys().asSequence().all { it in setOf("operation", "sessionId", "documentId", "queryId", "response", "error", "unhandled") })
                    val response = when {
                        request.has("error") -> ITMQueryResponse.Failure(Exception(request.get("error").toString()))
                        request.opt("unhandled") == true -> ITMQueryResponse.Unhandled
                        else -> ITMQueryResponse.Success(if (request.has("response")) request.toMap()["response"] else Unit)
                    }
                    current.pending.remove(queryId)?.complete(response)
                }
                else -> diagnostic("Rejected unknown Messenger operation.")
            }
        } catch (_: JSONException) {
            diagnostic("Rejected malformed Messenger payload.")
        } catch (_: IllegalArgumentException) {
            diagnostic("Rejected invalid Messenger envelope.")
        }
    }

    private fun handleQuery(current: DocumentSession, queryId: Int, name: String, request: JSONObject) {
        if (queryId in current.incoming) {
            diagnostic("Rejected duplicate Messenger query.")
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - current.rateStart >= 1000) {
            current.rateStart = now
            current.rateCount = 0
        }
        if (current.incoming.size >= MAX_PENDING || current.rateCount >= MAX_QUERIES_PER_SECOND) {
            sendResponse(current, queryId, ITMQueryResponse.Failure(IllegalStateException("Messenger request limit reached.")).toJSONString())
            return
        }
        current.rateCount++
        current.incoming.add(queryId)
        current.scope.launch {
            try {
                val data = if (request.has("message")) {
                    val message = request.get("message")
                    toJSON(if (message === JSONObject.NULL) null else message)
                } else null
                val response = delegate.onQuery(queryId, name, data)
                ensureActive()
                sendResponse(current, queryId, response)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                diagnostic("Messenger query handling failed.")
                sendResponse(current, queryId, ITMQueryResponse.Failure(error).toJSONString())
            } finally {
                current.incoming.remove(queryId)
            }
        }
    }

    private fun sendResponse(current: DocumentSession, queryId: Int, response: String) {
        val encoded = Base64.encodeToString(response.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        send(current, JSONObject().put("operation", "queryResponse").put("queryId", queryId).put("message", encoded))
    }

    private fun send(current: DocumentSession, message: JSONObject) {
        if (session !== current) return
        val proxy = current.replyProxy ?: return
        message.put("sessionId", current.id).put("documentId", current.documentId)
        val payload = message.toString()
        try {
            proxy.postMessage(payload)
        } catch (_: IllegalStateException) {
            diagnostic("Messenger response channel failed.")
            session = null
            current.scope.cancel()
            val error = IllegalStateException("Messenger response channel failed.")
            current.pending.values.forEach { it.completeExceptionally(error) }
            current.pending.clear()
            sessionEnded(error, false)
        }
    }

    override suspend fun sendQuery(queryId: Int, type: String, data: String?): ITMQueryResponse {
        val current = session ?: return ITMQueryResponse.Failure(IllegalStateException("Messenger has no authorized document."))
        if (current.replyProxy == null) return ITMQueryResponse.Failure(IllegalStateException("Messenger document is not ready."))
        if (current.pending.size >= MAX_PENDING) return ITMQueryResponse.Failure(IllegalStateException("Messenger request limit reached."))
        val deferred = CompletableDeferred<ITMQueryResponse>()
        current.pending[queryId] = deferred
        try {
            send(current, JSONObject().put("operation", "query").put("queryId", queryId).put("name", type)
                .put("message", Base64.encodeToString(data.orEmpty().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
            return deferred.await()
        } finally {
            current.pending.remove(queryId)
        }
    }
}
