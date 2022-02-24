package org.itwinjs.mobilesdk

import android.app.DatePickerDialog
import android.content.Context
import android.webkit.WebView
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import com.eclipsesource.json.JsonValue
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ITMDatePicker(context: Context, webView: WebView, coMessenger: ITMCoMessenger): ITMComponent(context, webView, coMessenger)  {
    init {
        listener = coMessenger.addQueryListener("Bentley_ITM_presentDatePicker") { value -> handleQuery(value) }
    }

    private fun getDateParam(params: JsonObject, field: String): Date? {
        params.getOptionalString(field)?.let { dateString ->
            return dateString.iso8601ToDate()
        }
        return null
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