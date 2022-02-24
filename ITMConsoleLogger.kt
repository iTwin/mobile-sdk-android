package org.itwinjs.mobilesdk

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.*

open class ITMConsoleLogger(protected val webView: WebView, protected val callback: (type: LogType, message: String) -> Unit) {
    enum class LogType {
        Assert,
        Error,
        Warn,
        Info,
        Log,
        Trace,
    }

    companion object {
        private const val injectedJs =
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
        private const val jsInterfaceName = "Bentley_ITMConsoleLogger"
    }

    init {
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun log(typeString: String, message: String) {
                val type = try {
                    LogType.valueOf(typeString.capitalize(Locale.ROOT))
                } catch (ex: Exception) {
                    LogType.Error
                }
                callback(type, message)
            }
        }, jsInterfaceName)
    }

    fun inject() {
        webView.evaluateJavascript(injectedJs, null)
    }

    fun detach() {
        webView.removeJavascriptInterface(jsInterfaceName)
    }
}