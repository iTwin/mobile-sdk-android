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
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.RelativeLayout
import kotlin.coroutines.suspendCoroutine

/**
 * [ITMNativeUIComponent] that presents a [PopupMenu].
 *
 * This class is used by the `presentActionSheet` TypeScript function in `@itwin/mobile-core`.
 *
 * > __Note:__ Due to the cross-platform nature of `@itwin/mobile-core`, functionality like this that
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
    private var relativeLayout: RelativeLayout? = null
    private var anchor: View? = null
    private var popupMenu: PopupMenu? = null
    private var cancelAction: Action? = null

    init {
        handler = coMessenger.registerQueryHandler("Bentley_ITM_presentActionSheet", ::handleQuery)
    }

    private fun toGravity(value: String?) = when (value) {
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

    private suspend fun handleQuery(params: Map<String, Any>): String? {
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val (actions, cancel) = readActions(params["actions"] as List<*>)
            cancelAction = cancel

            // If there is already an action sheet active, cancel it.
            resume(null)
            addAnchor(ITMRect(params["sourceRect"] as Map<*, *>, webView))
            return suspendCoroutine { continuation ->
                this.continuation = continuation
                val popupGravity = toGravity(params.getOptionalString("gravity"))
                with(PopupMenu(context, anchor, popupGravity)) {
                    popupMenu = this
                    setOnMenuItemClickListener {
                        removeAnchor()
                        popupMenu = null
                        resume(actions[it.itemId].name)
                        return@setOnMenuItemClickListener true
                    }
                    setOnDismissListener {
                        removeAnchor()
                        popupMenu = null
                        resume(cancelAction?.name)
                    }
                    params.getOptionalString("title")?.let {
                        with(menu.add(Menu.NONE, -1, Menu.NONE, it)) {
                            isEnabled = false
                        }
                    }
                    params.getOptionalString("message")?.let {
                        with(menu.add(Menu.NONE, -1, Menu.NONE, it)) {
                            isEnabled = false
                        }
                    }
                    for ((index, action) in actions.withIndex()) {
                        menu.add(Menu.NONE, index, Menu.NONE, action.styledTitle)
                    }
                    show()
                }
            }
        } catch (ex: Exception) {
            removeUI()
            removeAnchor()
            continuation = null
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentActionSheet")
        }
    }

    override fun removeUI() {
        popupMenu?.dismiss()
        popupMenu = null
        cancelAction = null
    }

    private fun addAnchor(sourceRect: ITMRect) {
        // NOTE: viewGroup will change every time the Model Web App is closed and reopened, so we do NOT want to grab the value
        // during our initialization.
        viewGroup = webView.parent as ViewGroup
        anchor = View(context).apply { alpha = 0.0f }
        val layoutParams = RelativeLayout.LayoutParams(sourceRect.width, sourceRect.height)
        layoutParams.leftMargin = sourceRect.x
        layoutParams.topMargin = sourceRect.y
        if (viewGroup !is RelativeLayout && viewGroup !is FrameLayout && viewGroup?.rootView is ViewGroup) {
            // We get here when running in React Native. If that happens, simply adding the anchor to viewGroup does not work
            // (the PopupMenu appears in the wrong place). Adding a RelativeLayout to the React Native ViewGroup also does not
            // work. Instead, create a full-screen RelativeLayout, add that to the root view, then add our anchor to the
            // full-screen view.
            relativeLayout = RelativeLayout(context).apply {
                // Adjust layoutParams to account for the fact that this RelativeLayout is full screen, and webView isn't
                // necessarily at 0,0 on the screen.
                // Note: I verified that this works in both portrait and landscape.
                val (x, y) = webView.screenLocation()
                layoutParams.leftMargin += x
                layoutParams.topMargin += y
                // Add the anchor to relativeLayout
                addView(anchor, layoutParams)
                val matchParent = RelativeLayout.LayoutParams.MATCH_PARENT
                val screenLayoutParams = RelativeLayout.LayoutParams(matchParent, matchParent)
                screenLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP, RelativeLayout.TRUE)
                screenLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_LEFT, RelativeLayout.TRUE)
                viewGroup = viewGroup?.rootView as? ViewGroup
                // Add the full-screen relativeLayout to viewGroup
                viewGroup?.addView(this, screenLayoutParams)
            }
        } else {
            viewGroup?.addView(anchor, layoutParams)
        }
    }

    private fun removeAnchor() {
        (relativeLayout ?: anchor)?.let {
            viewGroup?.removeView(it)
        }
        relativeLayout = null
        anchor = null
        viewGroup = null
    }

    /**
     * Cancels the action sheet when the device configuration changes (for example during an orientation change).
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resume(cancelAction?.name)
    }
}

private fun View.screenLocation(): IntArray {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return location
}
