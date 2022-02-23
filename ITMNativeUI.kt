package com.bentley.itmnativeui

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

open class ITMNativeUI(protected val context: Context, protected val webView: WebView, protected val coMessenger: ITMCoMessenger) {
    val components: MutableList<ITMComponent> = mutableListOf()

    init {
        components.add(ITMActionSheet(context, webView, coMessenger))
        components.add(ITMAlert(context, webView, coMessenger))
        components.add(ITMDatePicker(context, webView, coMessenger))
    }

    fun detach() {
        components.forEach { component ->
            component.detach()
        }
        components.clear()
    }

    fun onConfigurationChanged(newConfig: Configuration) {
        components.forEach { component ->
            component.onConfigurationChanged(newConfig)
        }
    }
}