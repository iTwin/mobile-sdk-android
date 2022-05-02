/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

open class ITMNativeUIComponent(
    protected val context: Context,
    protected val webView: WebView,
    @Suppress("MemberVisibilityCanBePrivate") protected val coMessenger: ITMCoMessenger) {
    var listener: ITMMessenger.ITMListener? = null

    fun detach() {
        coMessenger.removeListener(listener)
        listener = null
    }

    open fun onConfigurationChanged(newConfig: Configuration) {}
}