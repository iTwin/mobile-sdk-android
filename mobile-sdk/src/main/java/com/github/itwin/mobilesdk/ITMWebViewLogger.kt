/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.*

/**
 * Logger that, when attached to a [WebView], redirects console messages from that web view to the
 * given [callback].
 *
 * @param webView The [WebView] from which console output should be redirected.
 * @param callback The callback function that is called when console output is received.
 */
open class ITMWebViewLogger(
    protected val webView: WebView,
    protected val callback: (type: LogType, message: String) -> Unit) {
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
"""(function() {
    var injectLogger = function(type) {
        var originalLogger = console[type];
        console[type] = function(msg) {
            originalLogger.apply(console, arguments);
            if (msg == null) msg = "";
            window.Bentley_ITMConsoleLogger.log(type, msg);
        };
    };

    injectLogger("error");
    injectLogger("warn");
    injectLogger("info");
    injectLogger("log");
    injectLogger("trace");

    var originalAssert = console.assert;
    console.assert = function(expr, msg) {
        originalAssert.apply(console, arguments);
        if (expr) return;
        if (msg == null) msg = "";
        window.Bentley_ITMConsoleLogger.log("assert", "Assertion Failed: ");
    }

    window.addEventListener("error", function (e) {
        window.Bentley_ITMConsoleLogger.log("error", e.message);
        return false;
    });
})();"""
        private const val JS_INTERFACE_NAME = "Bentley_ITMConsoleLogger"
    }

    init {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun log(typeString: String, message: String) {
                val type = try {
                    LogType.valueOf(typeString.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.ROOT
                        ) else it.toString()
                    })
                } catch (ex: Exception) {
                    LogType.Error
                }
                callback(type, message)
            }
        }, JS_INTERFACE_NAME)
    }

    /**
     * Inject the appropriate JavaScript code into [webView] to handle the console redirection.
     */
    open fun inject() {
        webView.evaluateJavascript(INJECTED_JS, null)
    }

    /**
     * Detach from [webView].
     */
    fun detach() {
        webView.removeJavascriptInterface(JS_INTERFACE_NAME)
    }
}