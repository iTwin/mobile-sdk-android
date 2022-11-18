/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.app.DatePickerDialog
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * [ITMNativeUIComponent] that presents a [DatePickerDialog].
 *
 * @param nativeUI The [ITMNativeUI] in which the [DatePickerDialog] will display.
 */
class ITMDatePicker(nativeUI: ITMNativeUI): ITMNativeUIComponent(nativeUI)  {
    init {
        handler = coMessenger.registerQueryHandler("Bentley_ITM_presentDatePicker") { value -> handleQuery(value) }
    }

    private fun getDateParam(params: JsonObject, field: String): Date? {
        return params.getOptionalString(field)?.iso8601ToDate()
    }

    private suspend fun handleQuery(jsonValue: JsonValue?): JsonValue {
        try {
            // Note: no input validation is intentional. If the input is malformed, it will trigger the exception handler, which will send
            // an error back to TypeScript.
            val params = jsonValue!!.asObject()
            return suspendCoroutine { continuation ->
                val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, monthOfYear, dayOfMonth ->
                    continuation.resume(Json.value("$year-${monthOfYear + 1}-$dayOfMonth"))
                }
                // TODO: Google recommends using a DatePicker in a custom DialogFragment to show in a specific location on large screens.
                // Our params object includes a sourceRect field that would allow for that to be accomplished.
                val dpd: DatePickerDialog
                val value = getDateParam(params, "value")
                if (value != null) {
                    val calendar = Calendar.getInstance()
                    calendar.timeInMillis = value.time
                    val year = calendar.get(Calendar.YEAR)
                    val month = calendar.get(Calendar.MONTH)
                    val day = calendar.get(Calendar.DAY_OF_MONTH)
                    dpd = DatePickerDialog(context, dateSetListener, year, month, day)
                } else {
                    dpd = DatePickerDialog(context)
                    dpd.setOnDateSetListener(dateSetListener)
                }
                getDateParam(params, "min")?.let { min ->
                    dpd.datePicker.minDate = min.time
                }
                getDateParam(params, "max")?.let { max ->
                    dpd.datePicker.maxDate = max.time
                }
                dpd.setCancelable(true)
                dpd.setOnCancelListener {
                    continuation.resume(Json.parse("null"))
                }
                dpd.show()
            }
        } catch (ex: Exception) {
            // Note: this is caught by ITMCoMessenger and tells the TypeScript caller that there was an error.
            throw Exception("Invalid input to Bentley_ITM_presentDatePicker")
        }
    }
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