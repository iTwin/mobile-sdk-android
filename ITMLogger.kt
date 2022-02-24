package org.itwinjs.mobilesdk

import android.util.Log
import java.util.*

open class ITMLogger {
    enum class Severity {
        Fatal,
        Error,
        Warning,
        Info,
        Debug,
        Trace;

        companion object {
            fun fromString(value: String): Severity? {
                var lowercaseValue = value.toLowerCase(Locale.ROOT)
                lowercaseValue = when (lowercaseValue) {
                    "log" -> "debug"
                    "assert" -> "fatal"
                    "warn" -> "warning"
                    else -> lowercaseValue
                }

                return values().firstOrNull { lowercaseValue == it.name.toLowerCase(Locale.ROOT) }
            }
        }

        val description get() = name.toUpperCase(Locale.ROOT)
    }

    open fun log(severity: Severity?, message: String) {
        val logger: (String?, String) -> Int = when (severity) {
            Severity.Fatal -> Log::e
            Severity.Error -> Log::e
            Severity.Warning -> Log::w
            Severity.Info -> Log::i
            Severity.Debug -> Log::d
            Severity.Trace -> Log::v
            else -> Log::e
        }
        logger("ITMLogger", message)
    }
}
