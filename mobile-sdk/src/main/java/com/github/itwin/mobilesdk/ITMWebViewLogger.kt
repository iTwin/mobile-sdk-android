/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * Logger that, when attached to a [WebView], redirects console messages from that web view to the
 * given [callback].
 *
 * @param webView The [WebView] from which console output should be redirected.
 * @param callback The callback function that is called when console output is received.
 * @param allowedOrigins Exact HTTP(S) origins authorized to forward main-frame console output.
 */
open class ITMWebViewLogger(
    protected val webView: WebView,
    protected val callback: (type: LogType, message: String) -> Unit,
    allowedOrigins: Set<String>) {
    constructor(webView: WebView, callback: (type: LogType, message: String) -> Unit) :
        this(webView, callback, ITMBridgeOriginPolicy.DEFAULT_ALLOWED_ORIGINS)

    private val trustedOrigins = ITMBridgeOriginPolicy.validatedOrigins(allowedOrigins)
    private var attached = false
    private var sessionId: String? = null
    private var rateWindowStart = 0L
    private var rateWindowCount = 0

    enum class LogType {
        Assert,
        Error,
        Warn,
        Info,
        Log,
        Trace,
    }

    companion object {
        private const val INJECTED_JS =
"""(function(sessionId, allowedOrigins) {
    if (window !== window.top || allowedOrigins.indexOf(window.location.origin) === -1) return;
    var bridge = window.Bentley_ITMConsoleLogger;
    if (!bridge || typeof bridge.postMessage !== "function") return;
    var stateName = "Bentley_ITMConsoleLoggerState";
    var previous = window[stateName];
    if (previous) {
        previous.sessionId = sessionId;
        return;
    }
    var state = { sessionId: sessionId, active: true, originals: {}, wrappers: {} };
    var forward = function(type, msg) {
        if (!state.active) return;
        try {
            var message = msg == null ? "" : String(msg);
            if (message.length > 16384) return;
            bridge.postMessage(JSON.stringify({ sessionId: state.sessionId, type: type, message: message }));
        } catch (error) {}
    };
    var injectLogger = function(type) {
        var originalLogger = console[type];
        var wrapper = function(msg) {
            originalLogger.apply(console, arguments);
            forward(type, msg);
        };
        state.originals[type] = originalLogger;
        state.wrappers[type] = wrapper;
        console[type] = wrapper;
    };

    injectLogger("error");
    injectLogger("warn");
    injectLogger("info");
    injectLogger("log");
    injectLogger("trace");

    var originalAssert = console.assert;
    state.originals.assert = originalAssert;
    state.wrappers.assert = function(expr, msg) {
        originalAssert.apply(console, arguments);
        if (expr) return;
        forward("assert", "Assertion Failed: ");
    };
    console.assert = state.wrappers.assert;

    var onError = function(event) { forward("error", event.message); };
    window.addEventListener("error", onError);
    state.detach = function() {
        state.active = false;
        Object.keys(state.originals).forEach(function(type) {
            if (console[type] === state.wrappers[type]) console[type] = state.originals[type];
        });
        window.removeEventListener("error", onError);
        delete window[stateName];
    };
    window[stateName] = state;
})"""
        private const val JS_INTERFACE_NAME = "Bentley_ITMConsoleLogger"
        private const val MAX_PAYLOAD_LENGTH = 32768
        private const val MAX_MESSAGE_LENGTH = 16384
        private const val MAX_MESSAGES_PER_SECOND = 100
    }

    init {
        if (trustedOrigins.isNotEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, JS_INTERFACE_NAME, trustedOrigins) { _, message, sourceOrigin, isMainFrame, _ ->
                if (attached && isMainFrame && ITMBridgeOriginPolicy.canonicalOrigin(sourceOrigin.toString()) in trustedOrigins) {
                    receive(message.data)
                }
            }
            attached = true
        } else if (trustedOrigins.isNotEmpty()) {
            Log.w("ITMWebViewLogger", "Native console forwarding disabled: WebView messaging is unavailable.")
        }
    }

    private fun receive(payload: String?) {
        if (sessionId == null || payload == null || payload.length > MAX_PAYLOAD_LENGTH) return
        val now = SystemClock.uptimeMillis()
        if (now - rateWindowStart >= 1000) {
            rateWindowStart = now
            rateWindowCount = 0
        }
        if (rateWindowCount >= MAX_MESSAGES_PER_SECOND) return
        rateWindowCount++
        val data = try {
            JSONObject(payload)
        } catch (_: JSONException) {
            return
        }
        if (data.opt("sessionId") != sessionId) return
        val typeName = data.opt("type") as? String ?: return
        val type = LogType.values().firstOrNull { it.name.equals(typeName, ignoreCase = true) } ?: return
        val message = data.opt("message") as? String ?: return
        if (message.length <= MAX_MESSAGE_LENGTH) callback(type, message)
    }

    /** Invalidate queued output when the main frame starts navigating. Call on the WebView thread. */
    fun onPageStarted() {
        sessionId = null
        rateWindowStart = 0
        rateWindowCount = 0
    }

    /**
     * Inject console redirection into an authorized main frame. Call on the WebView thread.
     */
    open fun inject() {
        if (!attached || ITMBridgeOriginPolicy.canonicalOrigin(webView.url.orEmpty()) !in trustedOrigins) return
        val newSessionId = UUID.randomUUID().toString()
        sessionId = newSessionId
        webView.evaluateJavascript("$INJECTED_JS(${JSONObject.quote(newSessionId)}, ${JSONArray(trustedOrigins.toList())});", null)
    }

    /**
     * Detach from [webView] and restore console methods. Call on the WebView thread.
     */
    fun detach() {
        if (!attached) return
        val oldSessionId = sessionId
        onPageStarted()
        attached = false
        WebViewCompat.removeWebMessageListener(webView, JS_INTERFACE_NAME)
        if (oldSessionId != null) {
            webView.evaluateJavascript(
                "(function() { var state = window.Bentley_ITMConsoleLoggerState; " +
                    "if (state && state.sessionId === ${JSONObject.quote(oldSessionId)}) state.detach(); })();",
                null
            )
        }
    }
}
