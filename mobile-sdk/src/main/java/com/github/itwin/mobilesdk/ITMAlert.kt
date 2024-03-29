/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.AlertDialog
import kotlin.coroutines.suspendCoroutine

/**
 * [ITMNativeUIComponent] that presents an [AlertDialog].
 *
 * This class is used by the `presentAlert` TypeScript function in `@itwin/mobile-core`.
 *
 * @param nativeUI The [ITMNativeUI] in which the [AlertDialog] will display.
 */
class ITMAlert(nativeUI: ITMNativeUI): ITMActionable(nativeUI)  {
    private var alertDialog: AlertDialog? = null

    init {
        handler = coMessenger.registerQueryHandler("Bentley_ITM_presentAlert", ::handleQuery)
    }

    private fun toAlertActionsOrItems(actions: List<Action>): Pair<Array<Action?>, List<CharSequence>?> {
        var index = 0
        var neutralAction: Action? = null
        var negativeAction: Action? = null
        var positiveAction: Action? = null
        var items: List<CharSequence>? = null
        if (actions.size > 3) {
            items = actions.map { it.styledTitle }
        } else {
            // Note: The mapping of actions to buttons is documented in mobile-sdk-core.
            if (actions.size == 3) {
                neutralAction = actions[index]
                ++index
            }
            if (actions.size >= 2) {
                negativeAction = actions[index]
                ++index
            }
            if (actions.isNotEmpty()) {
                positiveAction = actions[index]
            }
        }
        return Pair(arrayOf(neutralAction, negativeAction, positiveAction), items)
    }

    private suspend fun handleQuery(params: Map<String, *>): String? {
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val (actions, cancelAction) = readActions(params["actions"] as List<*>)
            val title = params.getOptionalString("title")
            val message = params.getOptionalString("message")
            if (actions.isEmpty() && cancelAction == null) throw Exception("No actions")
            val (alertActions, items) = toAlertActionsOrItems(actions)
            val (neutralAction, negativeAction, positiveAction) = alertActions
            // If there is already an alert active, cancel it.
            resume(null)
            return suspendCoroutine { continuation ->
                this.continuation = continuation
                with(AlertDialog.Builder(context)) {
                    setTitle(title)
                    if (items != null) {
                        setItems(items.toTypedArray()) { _, index ->
                            resume(actions[index].name)
                        }
                    } else {
                        // Items and Message are mutually exclusive. If items are needed (more than three
                        // actions), ignore the message. Note that on iOS a message can be shown with any
                        // number of actions, so it is not invalid to have more than three items and a message.
                        setMessage(message)
                    }
                    setCancelable(cancelAction != null)
                    if (cancelAction != null) {
                        setOnCancelListener {
                            resume(cancelAction.name)
                        }
                    }
                    if (neutralAction != null) {
                        setNeutralButton(neutralAction.styledTitle) { _, _ ->
                            resume(neutralAction.name)
                        }
                    }
                    if (negativeAction != null) {
                        setNegativeButton(negativeAction.styledTitle) { _, _ ->
                            resume(negativeAction.name)
                        }
                    }
                    if (positiveAction != null) {
                        setPositiveButton(positiveAction.styledTitle) { _, _ ->
                            resume(positiveAction.name)
                        }
                    }
                    alertDialog = show()
                }
            }
        } catch (ex: Exception) {
            removeUI()
            continuation = null
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentActionSheet")
        }
    }

    override fun removeUI() {
        alertDialog?.dismiss()
        alertDialog = null
    }
}