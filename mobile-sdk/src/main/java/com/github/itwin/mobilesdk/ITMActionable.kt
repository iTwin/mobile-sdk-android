package com.github.itwin.mobilesdk

import android.graphics.Color
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.eclipsesource.json.JsonArray
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
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
        /**
         * Returns a list of [Action]'s and the cancel action (if defined in the input json).
         *
         * @param actionsValue An array of [JsonObject] containing the actions.
         */
        fun readActions(actionsValue: JsonArray): Pair<List<Action>, Action?> {
            val actions: MutableList<Action> = mutableListOf()
            var cancelAction: Action? = null
            actionsValue.forEach { actionValue ->
                val action = Action(actionValue.asObject())
                if (action.style == Action.Style.Cancel) {
                    cancelAction = action
                } else {
                    actions += action
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
                fun fromString(style: String?) = style?.takeIf { it.isNotEmpty() }?.let { Style.valueOf(style.replaceFirstChar { it.uppercase() }) } ?: Default
            }
        }

        /**
         * Constructor using a [JsonObject]
         * @param json [JsonObject] containing required `name` and `title` values, as well as optionally a `style` value.
         */
        constructor(json: JsonObject): this(json["name"].asString(), json["title"].asString(), Style.fromString(json.get("style")?.asString()))

        /**
         * The value in [title], styled to be red if [style] is [Destructive][Style.Destructive].
         */
        val styledTitle: CharSequence
            get() = if (style == Action.Style.Destructive) {
                    val colorTitle = SpannableString(title)
                    colorTitle.setSpan(ForegroundColorSpan(Color.RED), 0, title.length, 0)
                    colorTitle
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
    protected fun resume(result: JsonValue) {
        removeUI()
        continuation?.resume(result)
        continuation = null
    }
}
