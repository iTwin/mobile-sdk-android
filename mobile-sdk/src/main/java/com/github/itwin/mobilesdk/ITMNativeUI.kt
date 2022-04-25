/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

open class ITMNativeUI(
    @Suppress("CanBeParameter") protected val context: Context,
    protected val webView: WebView,
    @Suppress("CanBeParameter") protected val coMessenger: ITMCoMessenger) {
    @Suppress("MemberVisibilityCanBePrivate") val components: MutableList<ITMComponent> = mutableListOf()

    init {
        components.add(ITMActionSheet(context, webView, coMessenger))
        components.add(ITMAlert(context, webView, coMessenger))
        components.add(ITMDatePicker(context, webView, coMessenger))
    }

    @Suppress("unused")
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