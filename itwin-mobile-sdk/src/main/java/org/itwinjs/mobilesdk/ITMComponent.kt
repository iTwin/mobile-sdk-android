package org.itwinjs.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

open class ITMComponent(protected val context: Context, protected val webView: WebView, protected val coMessenger: ITMCoMessenger) {
    var listener: ITMMessenger.ITMListener? = null

    fun detach() {
        coMessenger.removeListener(listener)
        listener = null
    }

    open fun onConfigurationChanged(newConfig: Configuration) {}
}