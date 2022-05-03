/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView

/**
 * Container class for custom [ITMNativeUIComponents][ITMNativeUIComponent].
 *
 * @property components The list of [ITMNativeUIComponent] objects managed by this [ITMNativeUI].
 *
 * @param context The [Context] into which to display the UI.
 * @param webView The [WebView] making use of the native UI.
 * @param coMessenger The [ITMCoMessenger] used to communicate with [webView].
 */
open class ITMNativeUI(
    @Suppress("CanBeParameter") val context: Context,
    val webView: WebView,
    @Suppress("CanBeParameter") val coMessenger: ITMCoMessenger) {
    @Suppress("MemberVisibilityCanBePrivate") val components: MutableList<ITMNativeUIComponent> = mutableListOf()

    init {
        @Suppress("LeakingThis")
        components.add(ITMActionSheet(this))
        @Suppress("LeakingThis")
        components.add(ITMAlert(this))
        @Suppress("LeakingThis")
        components.add(ITMDatePicker(this))
    }

    /**
     * Call to detach the receiver from its [Context].
     */
    @Suppress("unused")
    open fun detach() {
        components.forEach { component ->
            component.detach()
        }
        components.clear()
    }

    /**
     * Call when the device configuration changes in the application.
     *
     * @param newConfig The new device configuration.
     */
    open fun onConfigurationChanged(newConfig: Configuration) {
        components.forEach { component ->
            component.onConfigurationChanged(newConfig)
        }
    }
}