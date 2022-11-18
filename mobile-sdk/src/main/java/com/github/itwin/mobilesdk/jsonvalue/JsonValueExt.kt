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
    return this.takeIf { isObject }?.asObject()?.get(key)
}

fun JsonValue.getOptionalString(propertyName: String): String? {
    return this[propertyName]?.takeIf { it.isString }?.asString()
}

fun JsonValue.getOptionalBoolean(propertyName: String): Boolean? {
    return this[propertyName]?.takeIf { it.isBoolean }?.asBoolean()
}

fun JsonValue.getOptionalLong(propertyName: String): Long? {
    return this[propertyName]?.takeIf { it.isNumber }?.asLong()
}

fun JsonValue.getOptionalObject(propertyName: String): JsonObject? {
    return this[propertyName]?.takeIf { it.isObject }?.asObject()
}

fun JsonValue.isYes(propertyName: String): Boolean {
    return getOptionalString(propertyName)?.lowercase() == "yes"
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
