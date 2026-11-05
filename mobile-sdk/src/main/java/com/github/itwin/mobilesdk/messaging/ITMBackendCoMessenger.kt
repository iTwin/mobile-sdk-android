/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk.messaging

/**
 * A wrapper around [ITMBackendMessenger] that uses Kotlin Coroutines.
 *
 * @param messenger The [ITMBackendMessenger] that this wraps.
 */
class ITMBackendCoMessenger(private val messenger: ITMBackendMessenger) : ITMCoMessengerProtocol by ITMCoMessengerImpl(messenger) {
    /**
     * Convenience wrapper around [ITMBackendMessenger.backendLaunchSucceeded].
     */
    fun backendLaunchSucceeded() {
        messenger.backendLaunchSucceeded()
    }

    /**
     * Convenience wrapper around [ITMBackendMessenger.isBackendLaunchComplete].
     */
    val isBackendLaunchComplete: Boolean
        get() = messenger.isBackendLaunchComplete

    /**
     * Convenience wrapper around [ITMBackendMessenger.backendLaunchFailed].
     *
     * @param error The reason for the failure.
     */
    fun backendLaunchFailed(error: Throwable) {
        messenger.backendLaunchFailed(error)
    }

    /**
     * Call to join the backend launch job (wait for the backend to launch).
     */
    suspend fun backendLaunchJoin() {
        messenger.backendLaunchJob.join()
    }
}
