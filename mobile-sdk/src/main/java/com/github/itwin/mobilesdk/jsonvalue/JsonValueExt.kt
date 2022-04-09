/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk.jsonvalue

import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import com.eclipsesource.json.PrettyPrint
import java.io.StringWriter

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

fun JsonValue.asOptionalObject(defaultValue: JsonObject? = null): JsonObject? {
    return if (this.isObject) this.asObject() else defaultValue
}

fun JsonValue.toPrettyString(): String {
    val writer = StringWriter()
    writeTo(writer, PrettyPrint.indentWithSpaces(4))
    return writer.toString()
}
