/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Implementation of a wrappers around an [ITMMessengerProtocol] that use Kotlin Coroutines.
 *
 * @param messenger The [ITMMessengerProtocol] that this wraps.
 */
internal class ITMCoMessengerImpl(private val messenger: ITMMessengerProtocol): ITMCoMessengerProtocol {
    override fun <I> send(type: String, data: I) {
        messenger.send(type, data)
    }

    override fun send(type: String) {
        messenger.send(type)
    }

    override suspend fun <I, O> query(type: String, data: I): O {
        return suspendCoroutine { continuation ->
            try {
                messenger.query<I, O>(type, data, { data ->
                    continuation.resume(data)
                }, { error ->
                    continuation.resumeWithException(error)
                })
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }
    }

    override fun <I, O> registerQueryHandler(type: String, callback: suspend (I) -> O) =
        messenger.registerQueryHandler(type) { value, success, failure ->
            MainScope().launch {
                try {
                    val result = callback.invoke(value)
                    success?.invoke(result)
                } catch (error: Throwable) {
                    failure?.invoke(error)
                }
            }
        }

    override fun removeHandler(handler: ITMQueryHandler?) {
        messenger.removeHandler(handler)
    }
}
