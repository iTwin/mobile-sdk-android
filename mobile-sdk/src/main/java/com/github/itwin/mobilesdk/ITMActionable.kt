/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Abstract superclass for [ITMAlert] and [ITMActionSheet].
 *
 * @param nativeUI The [ITMNativeUI] in which the UI will display.
 */
abstract class ITMActionable(nativeUI: ITMNativeUI): ITMNativeUIComponent(nativeUI) {
    protected var continuation: Continuation<String?>? = null
    companion object {
        /**
         * Returns a list of [Action]'s and the cancel action (if defined in the input json).
         *
         * @param tsActions A List of [Map] values containing the actions.
         */
        fun readActions(tsActions: List<*>): Pair<List<Action>, Action?> {
            // Note: Various things here can trigger an exception if invalid input is received. We
            // intentionally allow those to happen so that this function will throw an exception if
            // there is invalid input, which will then be caught by the caller, which generates an
            // error log and returns the exception to the TypeScript side.
            val actions: MutableList<Action> = mutableListOf()
            var cancelAction: Action? = null
            tsActions.forEach { actionValue ->
                (actionValue as Map<*, *>).ensureEntriesAre<String, String>().let {
                    val action = Action(it)
                    if (action.style == Action.Style.Cancel) {
                        cancelAction = action
                    } else {
                        actions += action
                    }
                }
            }
            return Pair(actions, cancelAction)
        }
    }

    /**
     * Class representing an action that the user can select.
     */
    data class Action(val name: String, val title: String, val style: Style = Style.Default) {
        enum class Style {
            Default,
            Cancel,
            Destructive;

            companion object {
                fun fromString(style: String?) =
                    style?.takeIf {
                        it.isNotEmpty()
                    }?.let {
                        Style.valueOf(style.replaceFirstChar { it.uppercase() })
                    } ?: Default
            }
        }

        /**
         * Constructor using a [Map]
         * @param map [Map] containing required `name` and `title` values, as well as optionally a `style` value.
         */
        constructor(map: Map<String, String>): this(map["name"] as String, map["title"] as String, Style.fromString(map["style"]))

        /**
         * The value in [title], styled to be red if [style] is [Destructive][Style.Destructive].
         */
        val styledTitle: CharSequence
            get() = if (style == Action.Style.Destructive) {
                SpannableString(title).apply {
                    setSpan(ForegroundColorSpan(Color.RED), 0, title.length, 0)
                }
            } else {
                title
            }
    }

    /**
     * Implemented by concrete sub-classes to stop showing their user interface. Called by [resume].
     */
    abstract fun removeUI()

    /**
     * Should be called by sub-classes when an action is selected or cancelled.
     */
    protected fun resume(result: String?) {
        removeUI()
        continuation?.resume(result)
        continuation = null
    }
}
