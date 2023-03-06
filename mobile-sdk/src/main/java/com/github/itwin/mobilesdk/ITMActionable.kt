package com.github.itwin.mobilesdk

import android.widget.PopupMenu
import com.eclipsesource.json.JsonArray
import com.eclipsesource.json.JsonValue
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Abstract superclass for [ITMAlert] and [ITMActionSheet].
 *
 * @param nativeUI The [ITMNativeUI] in which the UI will display.
 */
abstract class ITMActionable(nativeUI: ITMNativeUI): ITMNativeUIComponent(nativeUI) {
    protected var continuation: Continuation<JsonValue>? = null
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

    /**
     * Class representing an action that the user can select.
     *
     * @param value [JsonValue] containing required `name` and `title` values, as well as optionally a
     * `style` value.
     */
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

    abstract fun removeUI()

    protected fun resume(result: JsonValue) {
        removeUI()
        continuation?.resume(result)
        continuation = null
    }
}
