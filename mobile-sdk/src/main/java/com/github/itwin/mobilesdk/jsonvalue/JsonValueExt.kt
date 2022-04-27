/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk.jsonvalue

import com.eclipsesource.json.*
import java.io.StringWriter
import java.lang.Exception

operator fun JsonValue.get(key: String): JsonValue? {
    if (!this.isObject)
        return null

    return this.asObject()[key]
}

fun JsonValue.getOptionalString(propertyName: String, defaultValue: String? = null): String? {
    return if (this[propertyName]?.isString == true) this[propertyName]!!.asString() else defaultValue
}

fun JsonValue.getOptionalBoolean(propertyName: String, defaultValue: Boolean? = null): Boolean? {
    return if (this[propertyName]?.isBoolean == true) this[propertyName]!!.asBoolean() else defaultValue
}

fun JsonValue.getOptionalLong(propertyName: String, defaultValue: Long? = null): Long? {
    return if (this[propertyName]?.isNumber == true) this[propertyName]!!.asLong() else defaultValue
}

fun JsonValue.getOptionalObject(propertyName: String, defaultValue: JsonObject? = null): JsonObject? {
    return if (this[propertyName]?.isObject == true) this[propertyName]!!.asObject() else defaultValue
}

fun JsonValue.isYes(propertyName: String): Boolean {
    val value = getOptionalString(propertyName) ?: return false
    return value.lowercase() == "yes"
}

fun JsonValue.asOptionalObject(defaultValue: JsonObject? = null): JsonObject? {
    return if (this.isObject) this.asObject() else defaultValue
}

fun JsonValue.toPrettyString(): String {
    val writer = StringWriter()
    writeTo(writer, PrettyPrint.indentWithSpaces(4))
    return writer.toString()
}

//region Helpers

fun jsonOf(vararg pairs: Pair<String, *>): JsonObject {
    return jsonOf(mapOf(*pairs)) as JsonObject
}

fun jsonOf(value: Any?): JsonValue {
    return when (value) {
        null -> Json.value(null)
        is Int -> Json.value(value)
        is Long -> Json.value(value)
        is Float -> Json.value(value)
        is Double -> Json.value(value)
        is Boolean -> Json.value(value)
        is String -> Json.value(value)
        is Map<*, *> -> {
            val json = JsonObject()
            for ((k, v) in value) {
                if (k !is String) throw Exception("JSON object keys must be Strings, got: $k")
                json.add(k, jsonOf(v))
            }
            json
        }
        is Array<*> -> {
            val json = JsonArray()
            value.forEach { item -> json.add(jsonOf(item)) }
            json
        }
        is List<*> -> {
            val json = JsonArray()
            value.forEach { item -> json.add(jsonOf(item)) }
            json
        }
        is JsonValue -> value
        else -> throw Exception("Cannot convert to JSON: $value")
    }
}

//endregion
