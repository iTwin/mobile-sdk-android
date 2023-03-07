/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.res.Configuration
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.RelativeLayout
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import kotlin.coroutines.suspendCoroutine

/**
 * [ITMNativeUIComponent] that presents a [PopupMenu].
 *
 * This class is used by the `presentActionSheet` TypeScript function in `@itwin/mobile-core`.
 *
 * __Note:__ Due to the cross-platform nature of `@itwin/mobile-core`, functionality like this that
 * is designed to use native underlying features runs into a possible confusion over different naming
 * on iOS vs. Android. On iOS, the `presentActionSheet` TypeScript function results in an alert
 * controller with a style of `actionSheet`. Functionally, that uses a popover on iPads and full screen
 * on iPhones. Android doesn't have the exact equivalent, but its [PopupMenu] provides comparable
 * functionality. We decided that naming this class to match the name of the TypeScript function
 * made more sense than naming it ITMPopupMenu.
 *
 * @param nativeUI The [ITMNativeUI] in which the [PopupMenu] will display.
 */
class ITMActionSheet(nativeUI: ITMNativeUI): ITMActionable(nativeUI) {
    private var viewGroup: ViewGroup? = null
    private var anchor: View? = null
    private var popupMenu: PopupMenu? = null
    private var cancelAction: Action? = null

    init {
        handler = coMessenger.registerQueryHandler("Bentley_ITM_presentActionSheet") { value -> handleQuery(value) }
    }

    private suspend fun handleQuery(value: JsonValue?): JsonValue {
        // TODO: Handle optional title and message, as well as style=Destructive, or officially not support those on Android?
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val params = value!!.asObject()
            val (actions, cancel) = readActions(params["actions"].asArray())
            this.cancelAction = cancel

            // NOTE: viewGroup will change every time the Model Web App is closed and reopened, so we do NOT want to grab the value
            // during our initialization.
            viewGroup = webView.parent as ViewGroup
            val sourceRect = ITMRect(params["sourceRect"], webView)
            anchor = View(context)
            anchor?.let { anchor ->
                anchor.alpha = 0.0f
            }
            val layoutParams = RelativeLayout.LayoutParams(sourceRect.width, sourceRect.height)
            layoutParams.leftMargin = sourceRect.x
            layoutParams.topMargin = sourceRect.y
            viewGroup?.addView(anchor, layoutParams)
            // If there is already an action sheet active, cancel it.
            resume(Json.NULL)
            return suspendCoroutine { continuation ->
                this.continuation = continuation
                val popupGravity = params.getOptionalString("gravity")?.toGravity() ?: Gravity.NO_GRAVITY
                with(PopupMenu(context, anchor, popupGravity)) {
                    setOnMenuItemClickListener { item ->
                        removeAnchor()
                        popupMenu = null
                        cancelAction = null
                        resume(Json.value(actions[item.itemId].name))
                        return@setOnMenuItemClickListener true
                    }
                    setOnDismissListener {
                        removeAnchor()
                        popupMenu = null
                        resume(Json.value(cancelAction?.name))
                    }
                    for ((index, action) in actions.withIndex()) {
                        menu.add(Menu.NONE, index, Menu.NONE, action.title)
                    }
                    show()
                    popupMenu = this
                }
            }
        } catch (ex: Exception) {
            removeUI()
            removeAnchor()
            cancelAction = null
            continuation = null
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentActionSheet")
        }
    }

    override fun removeUI() {
        popupMenu?.dismiss()
        popupMenu = null
    }

    private fun removeAnchor() {
        anchor?.let { anchor ->
            viewGroup?.removeView(anchor)
        }
        anchor = null
    }

    /**
     * Cancels the action sheet when the device configuration changes (for example during an orientation change).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        removeUI()
        removeAnchor()
        resume(Json.value(cancelAction?.name))
        cancelAction = null
    }
}

private fun String.toGravity(): Int {
    return when (this) {
        "top" -> Gravity.TOP
        "bottom" -> Gravity.BOTTOM
        "left" -> Gravity.LEFT
        "right" -> Gravity.RIGHT
        "topLeft" -> Gravity.TOP or Gravity.LEFT
        "topRight" -> Gravity.TOP or Gravity.RIGHT
        "bottomLeft" -> Gravity.BOTTOM or Gravity.LEFT
        "bottomRight" -> Gravity.BOTTOM or Gravity.RIGHT
        else -> Gravity.NO_GRAVITY
    }
}