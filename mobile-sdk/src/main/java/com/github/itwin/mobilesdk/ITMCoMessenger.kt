/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import com.github.itwin.mobilesdk.messaging.ITMCoMessengerImpl
import com.github.itwin.mobilesdk.messaging.ITMCoMessengerProtocol

/**
 * A wrapper around [ITMMessenger] that uses Kotlin Coroutines.
 *
 * @param messenger The [ITMMessenger] that this wraps.
 */
class ITMCoMessenger(private val messenger: ITMMessenger) : ITMCoMessengerProtocol by ITMCoMessengerImpl(messenger) {
    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchSucceeded].
     */
    fun frontendLaunchSucceeded() {
        messenger.frontendLaunchSucceeded()
    }

    /**
     * Convenience wrapper around [ITMMessenger.isFrontendLaunchComplete].
     */
    val isFrontendLaunchComplete: Boolean
        get() = messenger.isFrontendLaunchComplete

    /**
     * Convenience wrapper around [ITMMessenger.frontendLaunchFailed].
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        messenger.frontendLaunchFailed(error)
    }

    /**
     * Call to join the frontend launch job (wait for the frontend to launch).
     */
    suspend fun frontendLaunchJoin() {
        messenger.frontendLaunchJob.join()
    }
}
