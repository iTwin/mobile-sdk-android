package com.bentley.itmnativeui

import android.content.Context
import android.webkit.WebView

open class ITMComponent(protected val context: Context, protected val webView: WebView, protected val coMessenger: ITMCoMessenger) {
    var listener: ITMMessenger.ITMListener? = null

    fun detach() {
        coMessenger.removeListener(listener)
        listener = null
    }
}