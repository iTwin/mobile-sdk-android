/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

/**
 * Parent class for native UI components.
 *
 * __Note__: The [nativeUI] passed into the constructor can come from the [ITMNativeUI] constructor itself.
 * When this happens, it is illegal to down-cast to an [ITMNativeUI] subclass that you might implement.
 *
 * @param nativeUI The [ITMNativeUI] in which the component will display.
 */
open class ITMNativeUIComponent(@Suppress("MemberVisibilityCanBePrivate") protected val nativeUI: ITMNativeUI) {
    protected val context: Context = nativeUI.context
    protected val webView: WebView = nativeUI.webView
    protected val coMessenger: ITMCoMessenger = nativeUI.coMessenger
    var handler: ITMMessenger.ITMHandler? = null

    /**
     * Detach this UI component from the native UI (stop listening for messages).
     */
    open fun detach() {
        coMessenger.removeHandler(handler)
        handler = null
    }

    /**
     * Called by [nativeUI] when the device configuration changes in the application.
     *
     * @param newConfig The new device configuration.
     */
    open fun onConfigurationChanged(newConfig: Configuration) {}
}