/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")
package com.github.itwin.mobilesdk.jsonvalue

import org.json.JSONArray
import org.json.JSONObject

/**
 * Class to represent a top-level JSON object. This can be an object, array, string, number,
 * boolean, or null.
 * @param value The value from which to create a [JSONValue]. Must be `null` or a supported type:
 * [Boolean], [String], [Number], [JSONArray], or [JSONObject]. If you need a [JSONValue] built from
 * any other type, use [toJSON].
 */
class JSONValue(value: Any? = null) {
    private var objectValue: JSONObject? = null
    private var arrayValue: JSONArray? = null
    private var stringValue: String? = null
    private var numberValue: Number? = null
    private var booleanValue: Boolean? = null
    private var isNullValue = false

    companion object {
        /**
         * Create a [JSONValue] from the given JSON string.
         * @param json JSON-compliant string representing the value.
         */
        fun fromJSON(json: String): JSONValue {
            val result = JSONValue()
            result.isNullValue = false
            try {
                result.objectValue = JSONObject(json)
                return result
            } catch (_: Exception) {} // Ignore
            try {
                result.arrayValue = JSONArray(json)
                return result
            } catch (_: Exception) {} // Ignore
            try {
                result.numberValue = json.toDouble()
                return result
            } catch (_: Exception) {} // Ignore
            val trimmed = json.trim()
            if (trimmed.length > 1 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                result.stringValue = trimmed.substring(1, trimmed.length - 1)
                return result
            }
            when (trimmed) {
                "true"  -> result.booleanValue = true
                "false" -> result.booleanValue = false
                "null"  -> result.isNullValue = true
                else    -> throw Exception("Invalid JSON")
            }
            return result
        }
    }

    /**
     * Indicates whether or not the receiver is `null`.
     */
    val isNull: Boolean
        get() = isNullValue

    /**
     * Indicates whether or not the receiver is an object.
     */
    val isObject: Boolean
        get() = objectValue != null

    /**
     * Indicates whether or not the receiver is an array.
     */
    val isArray: Boolean
        get() = arrayValue != null

    /**
     * Indicates whether or not the receiver is a [Number].
     */
    val isNumber: Boolean
        get() = numberValue != null

    /**
     * Indicates whether or not the receiver is a [Boolean].
     */
    val isBoolean: Boolean
        get() = booleanValue != null

    /**
     * Indicates whether or not the receiver is a [String].
     */
    val isString: Boolean
        get() = stringValue != null

    /**
     * The value represented by the receiver.
     */
    val value: Any?
        get() = booleanValue ?: numberValue ?: stringValue ?: arrayValue ?: objectValue

    /**
     * The value represented by the receiver, but using [Map] for object values and [List] for array
     * values.
     */
    val anyValue: Any?
        get() = when (value) {
            is JSONObject -> objectValue!!.toMap()
            is JSONArray  -> arrayValue!!.toList()
            else          -> value
        }

    /**
     * The receiver as a [JSONObject], or null if the receiver is not an object.
     */
    fun asObject(): JSONObject? {
        return objectValue
    }

    /**
     * The receiver as a [JSONArray], or null if the receiver is not an array.
     */
    fun asArray(): JSONArray? {
        return arrayValue
    }

    /**
     * The receiver as a [Number], or null if the receiver is not a numeric value.
     */
    fun asNumber(): Number? {
        return numberValue
    }

    /**
     * The receiver as an [Int], or null if the receiver is not a numeric value.
     */
    fun asInt(): Int? {
        return numberValue?.toInt()
    }

    /**
     * The receiver as a [Long], or null if the receiver is not a numeric value.
     */
    fun asLong(): Long? {
        return numberValue?.toLong()
    }

    /**
     * The receiver as a [Float], or null if the receiver is not a numeric value.
     */
    fun asFloat(): Float? {
        return numberValue?.toFloat()
    }

    /**
     * The receiver as a [Double], or null if the receiver is not a numeric value.
     */
    fun asDouble(): Double? {
        return numberValue?.toDouble()
    }

    /**
     * The receiver as a [Boolean], or null if the receiver is not a boolean value.
     */
    fun asBoolean(): Boolean? {
        return booleanValue
    }

    /**
     * The receiver as a [String], or null if the receiver is not a string value.
     */
    fun asString(): String? {
        return stringValue
    }

    /**
     * A JSON string representing the receiver.
     */
    override fun toString(): String {
        if (isNullValue) {
            return "null"
        }
        booleanValue?.let {
            if (it) {
                return "true"
            } else {
                return "false"
            }
        }
        numberValue?.let { return it.toString() }
        stringValue?.let { return "\"${it}\"" }
        arrayValue?.let { return it.toString() }
        objectValue?.let { return it.toString() }
        return "" // It should be impossible to get here
    }

    /**
     * A pretty-printed JSON string with 2-space indents representing the receiver.
     */
    fun toPrettyString(): String {
        objectValue?.let { return it.toString(2) }
        arrayValue?.let { return it.toString(2) }
        return toString()
    }

    /**
     * If the receiver is an object, returns the value of the specified key in the object. If the
     * receiver is not an object, or the key does not exist, throws an exception. This allows the
     * subscript operator to be directly used on [JSONValue].
     */
    operator fun get(key: String): Any = objectValue!!.get(key)

    /**
     * If the receiver is an object, maps `name` to `value`, clobbering any name/value mapping with
     * the same name.
     */
    fun put(key: String, value: Any?): JSONObject = objectValue!!.put(key, value)

    /**
     * If the receiver is an array, returns the value at the specified index in the array. If the
     * receiver is not an array, throws an exception. This allows the subscript operator to be used
     * directly on [JSONValue].
     */
    operator fun get(index: Int): Any = arrayValue!!.get(index)

    /**
     * If the receiver is an array, sets the value at `index` to `value`, null-padding the array to
     * the required length if necessary..
     */
    fun put(index: Int, value: Any?): JSONArray = arrayValue!!.put(index, value)

    /**
     * If the receiver is an object, returns the optional value of the specified key in the object.
     * If the receiver is not an object, or the key does not exist, returns null.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    fun opt(key: String) = objectValue?.opt(key)

    /**
     * If the receiver is an object, returns the optional value of the specified key in the object
     * if that value is a String, or `null` otherwise.
     *
     * __Note__: This returns `null` if the lookup fails or the type is wrong, not empty string
     * like [JSONObject.optString].
     */
    fun optString(key: String) = opt(key) as? String

    /**
     * If the receiver is an object, returns the optional value of the specified key in the object
     * if that value is an Int, or `null` otherwise.
     *
     * __Note__: This returns `null` if the lookup fails or the type is wrong, not 0 like
     * [JSONObject.optInt].
     */
    fun optInt(key: String) = opt(key)?.let { it as? Int ?: (it as? Number)?.toInt() }

    /**
     * If the receiver is an object, returns the optional value of the specified key in the object
     * if that value is a Long, or `null` otherwise.
     *
     * __Note__: This returns `null` if the lookup fails or the type is wrong, not 0 like
     * [JSONObject.optLong].
     */
    fun optLong(key: String) = opt(key)?.let { it as? Long ?: (it as? Number)?.toLong() }

    /**
     * If the receiver is an object, returns the optional value of the specified key in the object
     * if that value is a Double, or `null` otherwise.
     *
     * __Note__: This returns `null` if the lookup fails or the type is wrong, not NaN like
     * [JSONObject.optDouble].
     */
    fun optDouble(key: String) = opt(key)?.let { it as? Double ?: (it as? Number)?.toDouble() }

    /**
     * If the receiver is an array, returns the optional value at the specified index in the array.
     * If the receiver is not an array, or there is no value at the specified index, returns null.
     */
    fun opt(index: Int) = arrayValue?.opt(index)

    init {
        when (value) {
            null, JSONObject.NULL -> isNullValue = true
            is Boolean            -> booleanValue = value
            is String             -> stringValue = value
            is Number             -> numberValue = value
            is JSONArray          -> arrayValue = value
            is JSONObject         -> objectValue = value
            else                  -> throw Exception("Unsupported type in JSONValue constructor")
        }
    }
}

/**
 * Returns true if the receiver is a string with a value of "yes", or false otherwise.
 */
fun JSONObject.isYes(propertyName: String): Boolean {
    return optString(propertyName).lowercase() == "yes"
}

/**
 * Converts the receiver to a map, converting all [JSONObject] values to maps, all [JSONArray]
 * values to lists, and all [JSONObject.NULL] values to `null`.
 */
fun JSONObject.toMap(): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    for (key in keys()) {
        result[key] = when (val value = this[key]) {
            is JSONArray    -> value.toList()
            is JSONObject   -> value.toMap()
            JSONObject.NULL -> null
            else            -> value
        }
    }
    return result
}

/**
 * Converts the receiver to a list, converting all [JSONObject] values to maps, all [JSONArray]
 * values to lists, and all [JSONObject.NULL] values to `null`.*
 */
fun JSONArray.toList(): List<Any?> {
    val result = mutableListOf<Any?>()
    for (i in 0 until length()) {
        val value = this[i]
        result.add(when (value) {
            is JSONArray    -> value.toList()
            is JSONObject   -> value.toMap()
            JSONObject.NULL -> null
            else            -> value
        })
    }
    return result
}

/**
 * Returns a new [JSONValue] containing an object value with the specified contents, given as a list
 * of pairs where the where the first value is the key and the second is the value.
 *
 * If multiple pairs have the same key, the resulting object will contain the value from the last
 * of those pairs.
 *
 * __Note__: This is conceptually the same as [mapOf], but creating a [JSONValue] instead of a
 * [Map].
 */
fun jsonOf(vararg pairs: Pair<String, *>): JSONValue {
    return toJSON(mapOf(*pairs))
}

/**
 * Converts a value to a [JSONValue].
 */
fun toJSON(value: Any?): JSONValue {
    return when (value) {
        null -> JSONValue()
        is Int, is Number, is Boolean, is String, is JSONArray, is JSONObject -> JSONValue(value)
        is Map<*, *> -> {
            val json = JSONObject()
            for ((k, v) in value) {
                if (k !is String) throw java.lang.Exception("JSON object keys must be Strings, got: $k")
                json.putOpt(k, toJSON(v).value)
            }
            JSONValue(json)
        }
        is Array<*> -> {
            val json = JSONArray()
            value.forEach { item -> json.put(toJSON(item).value) }
            JSONValue(json)
        }
        is List<*> -> {
            val json = JSONArray()
            value.forEach { item -> json.put(toJSON(item).value) }
            JSONValue(json)
        }
        is JSONValue -> value
        else -> throw java.lang.Exception("Cannot convert to JSON: $value")
    }
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
