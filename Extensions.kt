@file:Suppress("SpellCheckingInspection") // We don't want to add the DateFormat strings to the custom spelling dictionary.

package com.bentley.itmnativeui

import android.os.Build
import com.eclipsesource.json.JsonValue
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

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

fun String.iso8601ToDate(): Date? {
    try {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Date(Instant.parse(this).toEpochMilli())
        } else {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.getDefault())
            if (this.endsWith('Z')) {
                format.timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(this)
        }
    } catch (ex: Exception) {
        // Ignore
    }
    return try {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        if (this.endsWith('Z')) {
            format.timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(this)
    } catch (ex: Exception) {
        return null
    }
}