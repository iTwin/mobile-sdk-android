/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.webkit.WebView
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import kotlin.math.roundToInt

/**
 * Class for converting between JSON dictionary in [WebView] coordinates and Kotlin representing a rectangle
 * in UI coordinates.
 *
 * @param value: The JSON value containing the rectangle. This must include `x`, `y`, `width`, and `height` fields.
 * @param webView: The [WebView] that the rectangle is in.
 */
class ITMRect(value: JsonValue, webView: WebView) {
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
        val sourceRect: JsonObject = value.asObject()
        fun getField(fieldName: String): Int {
            return (sourceRect[fieldName].asFloat() * density).roundToInt()
        }
        x = getField("x")
        y = getField("y")
        width = getField("width")
        height = getField("height")
    }
}

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
    val context: Context,
    val webView: WebView,
    val coMessenger: ITMCoMessenger) {
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