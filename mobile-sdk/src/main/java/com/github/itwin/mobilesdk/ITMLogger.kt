/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.util.Log
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * Class used by iTwin Mobile SDK to log information during runtime. Replace the [logger][ITMApplication.logger]
 * property on [ITMApplication] with a subclass of this class for custom logging. This default logger uses
 * [Log] functions for logging.
 */
@Suppress("unused")
open class ITMLogger {
    /**
     * The severity of a log message.
     */
    enum class Severity {
        Fatal,
        Error,
        Warning,
        Info,
        Debug,
        Trace;

        companion object {
            /**
             * Convert a string into a [Severity]. This has special cases to handle severities from
             * [ITMWebViewLogger.LogType].
             */
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

        /**
         * Get the severity name in all upper case.
         */
        val description get() = name.uppercase(Locale.ROOT)
    }

    /**
     * Logs the given message using a function from the [Log] class that is appropriate to the given
     * [severity], with a tag of `"ITMLogger"`.
     */
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
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        logger("ITMLogger", "%s: %s".format(LocalDateTime.now().format(formatter), message))
    }
}
