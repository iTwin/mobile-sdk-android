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
fun Long.epochMillisToISO8601() = Instant.ofEpochMilli(this).toString()

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
fun <K, V> Map<K, V>.getOptionalString(key: K) = this[key] as? String

/**
 * Returns the value corresponding to the given [key] as a [Boolean], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Boolean].
 */
fun <K, V> Map<K, V>.getOptionalBoolean(key: K) = this[key] as? Boolean

/**
 * Returns the value corresponding to the given [key] as an [Int], or `null` if such a key is not
 * present in the map, or contains a value that is not an [Int].
 */
fun <K, V> Map<K, V>.getOptionalInt(key: K) =
    this[key]?.let { it as? Int ?: (it as? Number)?.toInt() }

/**
 * Returns the value corresponding to the given [key] as a [Long], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Long].
 */
fun <K, V> Map<K, V>.getOptionalLong(key: K) =
    this[key]?.let { it as? Long ?: (it as? Number)?.toLong() }

/**
 * Returns the value corresponding to the given [key] as a [Float], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Float].
 */
fun <K, V> Map<K, V>.getOptionalFloat(key: K) =
    this[key]?.let { it as? Float ?: (it as? Number)?.toFloat() }

/**
 * Returns the value corresponding to the given [key] as a [Double], or `null` if such a key is not
 * present in the map, or contains a value that is not a [Double].
 */
fun <K, V> Map<K, V>.getOptionalDouble(key: K) =
    this[key]?.let { it as? Double ?: (it as? Number)?.toDouble() }

/**
 * Verify that all entries in the receiver have a key type of `K` and a value type of `V`.
 * @return The receiver specialized with `K` and `V` if the key and value types match, otherwise
 * `null`.
 */
inline fun <reified K: Any, reified V: Any> Map<*,*>.checkEntriesAre(): Map<K, V>? =
    this.takeIf { _ ->
        all { it.key is K && it.value is V }
    }?.let {
        @Suppress("UNCHECKED_CAST")
        it as Map<K, V>
    }

/**
 * Ensure that all entries in the receiver have a key type of `K` and a value type of `V`.
 * @throws Throwable If the entries don't have the proper types, this throws while failing to
 * convert `null` to `Map<K, V>`.
 * @return The receiver specialized with `K` and `V`.
 */
inline fun <reified K: Any, reified V: Any> Map<*,*>.ensureEntriesAre(): Map<K, V> =
    checkEntriesAre<K, V>() as Map<K, V>

/**
 * Verify that all items in the receiver have a type of `T`.
 * @return The receiver specialized with `T` if the item types match, otherwise `null`.
 */
inline fun <reified T> List<*>.checkItemsAre(): List<T>? =
    this.takeIf { _ ->
        all { it is T }
    }?.let {
        @Suppress("UNCHECKED_CAST")
        it as List<T>
    }

/**
 * Ensure that all items in the receiver have a type of `T`.
 * @throws Throwable If the items don't have the proper type, this throws while failing to convert
 * `null` to `List<T>`.
 * @return The receiver specialized with `T` if the item types match, otherwise `null`.
 */
inline fun <reified T> List<*>.ensureItemsAre(): List<T> =
    checkItemsAre<T>() as List<T>

/**
 * Execute a block of code if all other parameters are non-null.
 *
 * This is intended to replace nested [let] calls.
 *
 * There is another overload with three parameters plus [block].
 *
 * **Example:**
 * ```kt
 * val a: Int? = 42
 * val b: Int? = 13
 * letAll(a, b) { a, b -> a + b }?.let {
 *   // Prints 55
 *   print(it)
 * }
 * ```
 */
inline fun <T1, T2, R> letAll(p1: T1?, p2: T2?, block: (T1, T2) -> R?): R? {
    return if (p1 != null && p2 != null) {
        block(p1, p2)
    } else {
        null
    }
}

/**
 * Execute a block of code if all other parameters are non-null.
 *
 * This is intended to replace nested [let] calls.
 *
 * See the overload with two parameters plus [block] for example usage.
 */
inline fun <T1, T2, T3, R> letAll(p1: T1?, p2: T2?, p3: T3?, block: (T1, T2, T3) -> R?): R? {
    return if (p1 != null && p2 != null && p3 != null) {
        block(p1, p2, p3)
    } else {
        null
    }
}

/**
 * Execute a block of code and return its return value if it doesn't throw an exception, or null if
 * it does throw an exception.
 */
inline fun <T, R> T.catchToNull(block: T.() -> R): R? {
    return runCatching(block).getOrNull()
}

/**
 * Execute a block of code and return its return value if it doesn't throw an exception, or null if
 * it does throw an exception.
 */
inline fun <R> catchToNull(block: () -> R): R? {
    return runCatching(block).getOrNull()
}
