/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.Configuration
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.PopupMenu
import android.widget.RelativeLayout
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.roundToInt

/**
 * [ITMNativeUIComponent] that presents a [PopupMenu].
 *
 * This class is used by the `ActionSheet` TypeScript class in `@itwin/mobile-core`.
 *
 * @param context The [Context] in which to show the [PopupMenu].
 * @param webView The [WebView] that requested the action sheet.
 * @param coMessenger The [ITMCoMessenger] used for communication with [webView].
 */
class ITMActionSheet(context: Context, webView: WebView, coMessenger: ITMCoMessenger): ITMNativeUIComponent(context, webView, coMessenger) {
    private var viewGroup: ViewGroup? = null
    private var anchor: View? = null
    private var popupMenu: PopupMenu? = null

    class SourceRect(value: JsonValue, private val density: Float) {
        val x: Int
        val y: Int
        val width: Int
        val height: Int
        private val sourceRect: JsonObject = value.asObject()

        init {
            x = getField("x")
            y = getField("y")
            width = getField("width")
            height = getField("height")
        }

        private fun getField(fieldName: String): Int {
            return (sourceRect[fieldName].asFloat() * density).roundToInt()
        }
    }

    init {
        listener = coMessenger.addQueryListener("Bentley_ITM_presentActionSheet") { value -> handleQuery(value) }
    }

    private suspend fun handleQuery(value: JsonValue?): JsonValue {
        // TODO: Handle optional title and message, as well as style=Destructive, or officially not support those on Android?
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val params = value!!.asObject()
            val actions: MutableList<ITMAlert.Action> = mutableListOf()
            val cancelAction = ITMAlert.readActions(params["actions"].asArray(), actions)

            // NOTE: viewGroup will change every time the Model Web App is closed and reopened, so we do NOT want to grab the value
            // during our initialization.
            viewGroup = webView.parent as ViewGroup
            val density = webView.resources.displayMetrics.density
            val sourceRect = SourceRect(params["sourceRect"], density)
            anchor = View(context)
            anchor?.let { anchor ->
                anchor.alpha = 0.0f
            }
            val layoutParams = RelativeLayout.LayoutParams(sourceRect.width, sourceRect.height)
            layoutParams.leftMargin = sourceRect.x
            layoutParams.topMargin = sourceRect.y
            viewGroup?.addView(anchor, layoutParams)
            return suspendCoroutine { continuation ->
                var resumed = false
                with(PopupMenu(context, anchor)) {
                    setOnMenuItemClickListener { item ->
                        resumed = true
                        continuation.resume(Json.value(actions[item.itemId].name))
                        return@setOnMenuItemClickListener true
                    }
                    setOnDismissListener {
                        removeAnchor()
                        if (!resumed)
                            continuation.resume(Json.value(cancelAction?.name))
                    }
                    for ((index, action) in actions.withIndex()) {
                        menu.add(Menu.NONE, index, Menu.NONE, action.title)
                    }
                    show()
                    popupMenu = this
                }
            }
        } catch (ex: Exception) {
            removePopupMenu()
            removeAnchor()
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentActionSheet")
        }
    }

    private fun removePopupMenu() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    private fun removeAnchor() {
        anchor?.let { anchor ->
            viewGroup?.removeView(anchor)
        }
        anchor = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removePopupMenu()
        removeAnchor()
    }
}