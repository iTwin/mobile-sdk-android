/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package org.itwinjs.mobilesdk

import android.util.Log
import java.util.*

@Suppress("unused")
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
                var lowercaseValue = value.lowercase(Locale.ROOT)
                lowercaseValue = when (lowercaseValue) {
                    "log" -> "debug"
                    "assert" -> "fatal"
                    "warn" -> "warning"
                    else -> lowercaseValue
                }

                return values().firstOrNull { lowercaseValue == it.name.lowercase(Locale.ROOT) }
            }
        }

        val description get() = name.uppercase(Locale.ROOT)
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
