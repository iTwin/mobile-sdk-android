/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

/**
 * Parent class for native UI components.
 *
 * @param nativeUI The [ITMNativeUI] in which the component will display.
 */
open class ITMNativeUIComponent(protected val nativeUI: ITMNativeUI) {
    /**
     * Convenience member set to [nativeUI].[ITMNativeUI.context]
     */
    protected val context: Context = nativeUI.context

    /**
     * Convenience member set to [nativeUI].[ITMNativeUI.webView]
     */
    protected val webView: WebView = nativeUI.webView

    /**
     * Convenience member set to [nativeUI].[ITMNativeUI.coMessenger]
     */
    protected val coMessenger: ITMCoMessenger = nativeUI.coMessenger

    /**
     * The handler for this component's messages from [webView].
     */
    var handler: ITMMessenger.ITMHandler? = null

    /**
     * Detach this UI component from the native UI (stop listening for messages).
     *
     * > __Note:__ The standard [ITMNativeUIComponents][ITMNativeUIComponent] built into
     * mobile-sdk-android cannot be reattached; new ones must be created instead.
     */
    open fun detach() {
        coMessenger.removeHandler(handler)
        handler = null
    }

    /**
     * Called by [nativeUI] when the device configuration changes in the application.
     *
     * > __Note:__ The default implementation does nothing.
     *
     * @param newConfig The new device configuration.
     */
    open fun onConfigurationChanged(newConfig: Configuration) {}
}