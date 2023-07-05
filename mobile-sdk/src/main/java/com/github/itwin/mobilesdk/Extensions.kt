/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")
package com.github.itwin.mobilesdk

import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*

/**
 * Convenience function convert a [Long] containing the number of milliseconds since the epoch into an
 * ISO 8601-formatted [String].
 *
 * @return A [String] containing an ISO 8601 format date.
 */
fun Long.epochMillisToISO8601(): String {
    return Instant.ofEpochMilli(this).toString()
}

/**
 * Convenience function to convert a [String] containing an ISO 8601 date into a [Date] object.
 *
 * @return The parsed [Date], or null if the receiver string could not be parsed into a valid [Date].
 */
fun String.iso8601ToDate(): Date? {
    try {
        return Date(Instant.parse(this).toEpochMilli())
    } catch (ex: Exception) {
        // Ignore
    }
    return try {
        @Suppress("SpellCheckingInspection")
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        if (this.endsWith('Z')) {
            format.timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(this)
    } catch (ex: Exception) {
        return null
    }
}

/**
 * Returns the value corresponding to the given [key] as a [String], or `null` if such a key is not
 * present in the map, or contains a value that is not a [String].
 */
fun <K, V> Map<K, V>.optString(key: K): String? {
    return this[key] as? String
}

/**
 * Returns the value corresponding to the given [key] as a [Boolean], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Boolean].
 */
fun <K, V> Map<K, V>.optBoolean(key: K): Boolean? {
    return this[key] as? Boolean
}

/**
 * Returns the value corresponding to the given [key] as an [Int], or `null` if such a key is not
 * present in the map, or contains a value that is not an [Int].
 */
fun <K, V> Map<K, V>.optInt(key: K): Int? {
    return this[key]?.let { it as? Int ?: (it as? Number)?.toInt() }
}

/**
 * Returns the value corresponding to the given [key] as a [Long], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Long].
 */
fun <K, V> Map<K, V>.optLong(key: K): Long? {
    return this[key]?.let { it as? Long ?: (it as? Number)?.toLong() }
}

/**
 * Returns the value corresponding to the given [key] as a [Float], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Float].
 */
fun <K, V> Map<K, V>.optFloat(key: K): Float? {
    return this[key]?.let { it as? Float ?: (it as? Number)?.toFloat() }
}

/**
 * Returns the value corresponding to the given [key] as a [Double], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Double].
 */
fun <K, V> Map<K, V>.optDouble(key: K): Double? {
    return this[key]?.let { it as? Double ?: (it as? Number)?.toDouble() }
}

/**
 * Verify that all entries in the receiver have a key type of `K` and a value type of `V`.
 * @return The receiver specialized with `K` and `V` if the key and value types match, otherwise
 * `null`.
 */
inline fun <reified K: Any, reified V: Any> Map<*,*>.checkEntriesAre(): Map<K, V>? {
    forEach {
        if (it.key !is K) return null
        if (it.value !is V) return null
    }
    @Suppress("UNCHECKED_CAST")
    return this as Map<K, V>
}

/**
 * Verify that all items in the receiver have a type of `T`.
 * @return The receiver specialized with `T` if the item types match, otherwise `null`.
 */
inline fun <reified T> List<*>.checkItemsAre(): List<T>? =
    if (all { it is T })
        @Suppress("UNCHECKED_CAST")
        this as List<T>
    else
        null
