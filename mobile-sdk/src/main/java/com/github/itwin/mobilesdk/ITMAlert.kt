/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.AlertDialog
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonValue
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import java.util.*
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
        handler = coMessenger.registerQueryHandler("Bentley_ITM_presentAlert") { value -> handleQuery(value) }
    }

    @Suppress("LongMethod")
    private suspend fun handleQuery(value: JsonValue?): JsonValue {
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val params = value!!.asObject()
            val (actions, cancelAction) = readActions(params["actions"].asArray())
            val title = params.getOptionalString("title")
            val message = params.getOptionalString("message")
            if (actions.isEmpty() && cancelAction == null) throw Exception("No actions")
            var index = 0
            var neutralAction: Action? = null
            var negativeAction: Action? = null
            var positiveAction: Action? = null
            var items: MutableList<CharSequence>? = null
            if (actions.size > 3) {
                items = mutableListOf()
                actions.forEach { action ->
                    items += action.styledTitle
                }
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
            // If there is already an alert active, cancel it.
            resume(Json.NULL)
            return suspendCoroutine { continuation ->
                this.continuation = continuation
                with(AlertDialog.Builder(context)) {
                    setTitle(title)
                    if (items != null) {
                        setItems(items.toTypedArray()) { _, index ->
                            resume(Json.value(actions[index].name))
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
                            resume(Json.value(cancelAction.name))
                        }
                    }
                    if (neutralAction != null) {
                        setNeutralButton(neutralAction.styledTitle) { _, _ ->
                            resume(Json.value(neutralAction.name))
                        }
                    }
                    if (negativeAction != null) {
                        setNegativeButton(negativeAction.styledTitle) { _, _ ->
                            resume(Json.value(negativeAction.name))
                        }
                    }
                    if (positiveAction != null) {
                        setPositiveButton(positiveAction.styledTitle) { _, _ ->
                            resume(Json.value(positiveAction.name))
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