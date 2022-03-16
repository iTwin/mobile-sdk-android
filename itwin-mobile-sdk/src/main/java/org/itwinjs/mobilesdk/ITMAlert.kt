/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package org.itwinjs.mobilesdk

import android.app.AlertDialog
import android.content.Context
import android.webkit.WebView
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonArray
import com.eclipsesource.json.JsonValue
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import org.itwinjs.mobilesdk.jsonvalue.*

class ITMAlert(context: Context, webView: WebView, coMessenger: ITMCoMessenger): ITMComponent(context, webView, coMessenger)  {
    companion object {
        fun readActions(actionsValue: JsonArray, actions: MutableList<Action>): Action? {
            var cancelAction: Action? = null
            actionsValue.forEach { actionValue ->
                val action = Action(actionValue)
                if (action.style == ActionStyle.Cancel) {
                    cancelAction = action
                } else {
                    actions += action
                }
            }
            return cancelAction
        }
    }

    enum class ActionStyle {
        Default,
        Cancel,
        Destructive
    }

    class Action(value: JsonValue) {
        val name: String
        val title: String
        val style: ActionStyle

        init {
            val action = value.asObject()
            name = action["name"].asString()
            title = action["title"].asString()
            style = ActionStyle.valueOf(
                action["style"].asString()
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
        }
    }

    init {
        listener = coMessenger.addQueryListener("Bentley_ITM_presentAlert") { value -> handleQuery(value) }
    }

    private suspend fun handleQuery(value: JsonValue?): JsonValue {
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val params = value!!.asObject()
            val actions: MutableList<Action> = mutableListOf()
            val cancelAction = readActions(params["actions"].asArray(), actions)
            val title = params.getOptionalString("title")
            val message = params.getOptionalString("message")
            if (actions.size > 3) throw Exception("Too many actions.")
            if (actions.size == 0 && cancelAction == null) throw Exception("No actions")
            var index = 0
            var neutralAction: Action? = null
            var negativeAction: Action? = null
            var positiveAction: Action? = null
            // TODO: Allow explicit control over which action goes to which button?
            if (actions.size == 3) {
                neutralAction = actions[index]
                ++index
            }
            if (actions.size >= 2) {
                negativeAction = actions[index]
                ++index
            }
            if (actions.size >= 1) {
                positiveAction = actions[index]
            }
            return suspendCoroutine { continuation ->
                with(AlertDialog.Builder(context)) {
                    setTitle(title)
                    setMessage(message)
                    setCancelable(cancelAction != null)
                    cancelAction?.let { cancelAction ->
                        setOnCancelListener {
                            continuation.resume(Json.value(cancelAction.name))
                        }
                    }
                    neutralAction?.let { action ->
                        setNeutralButton(action.title) { _, _ ->
                            continuation.resume(Json.value(action.name))
                        }
                    }
                    negativeAction?.let { action ->
                        setNegativeButton(action.title) { _, _ ->
                            continuation.resume(Json.value(action.name))
                        }
                    }
                    positiveAction?.let { action ->
                        setPositiveButton(action.title) { _, _ ->
                            continuation.resume(Json.value(action.name))
                        }
                    }
                    show()
                }
            }
        } catch (ex: Exception) {
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentActionSheet")
        }
    }
}