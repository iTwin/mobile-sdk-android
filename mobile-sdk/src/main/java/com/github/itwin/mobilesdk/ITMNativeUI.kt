/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import androidx.annotation.CallSuper
import kotlin.math.roundToInt

/**
 * Class for converting between a JSON dictionary in [WebView] coordinates and Kotlin representing a
 * rectangle in UI coordinates.
 *
 * @param value: The JSON value containing the rectangle. This must include `x`, `y`, `width`, and
 * `height` fields.
 * @param webView: The [WebView] that the rectangle is in.
 */
class ITMRect(value: Map<*, *>, webView: WebView) {
    /**
     * The x coordinate of the rectangle in [WebView] coordinates.
     */
    val x: Int

    /**
     * The y coordinate of the rectangle in [WebView] coordinates.
     */
    val y: Int

    /**
     * The width of the rectangle in [WebView] coordinates.
     */
    val width: Int

    /**
     * The height of the rectangle in [WebView] coordinates.
     */
    val height: Int

    init {
        val density = webView.resources.displayMetrics.density
        fun getField(fieldName: String) =
            ((value[fieldName] as Number).toFloat() * density).roundToInt()
        x = getField("x")
        y = getField("y")
        width = getField("width")
        height = getField("height")
    }
}

/**
 * Container class for custom [ITMNativeUIComponents][ITMNativeUIComponent].
 *
 * @param context The [Context] into which to display the UI.
 * @param webView The [WebView] making use of the native UI.
 * @param coMessenger The [ITMCoMessenger] used to communicate with [webView].
 */
open class ITMNativeUI(
    val context: Context,
    val webView: WebView,
    val coMessenger: ITMCoMessenger) {
    /**
     * The list of [ITMNativeUIComponent] objects managed by this [ITMNativeUI].
     */
    val components: MutableList<ITMNativeUIComponent> = mutableListOf()

    /**
     * Register the standard [ITMNativeUIComponent] subclasses that are part of mobile-sdk-android.
     *
     * This is called automatically by [ITMApplication.createNativeUI].
     */
    @CallSuper
    open fun registerStandardComponents() {
        components.add(ITMActionSheet(this))
        components.add(ITMAlert(this))
    }

    /**
     * Detach the receiver from its [Context] and remove all components.
     */
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