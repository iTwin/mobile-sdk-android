/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.webkit.WebView
import com.github.itwin.mobilesdk.messaging.ITMMessengerImpl
import com.github.itwin.mobilesdk.messaging.ITMMessengerLoggingOptions
import com.github.itwin.mobilesdk.messaging.ITMMessengerProtocol
import com.github.itwin.mobilesdk.messaging.ITMQueryHandler
import kotlinx.coroutines.Job

typealias ITMSuccessCallback<T> = (T) -> Unit
typealias ITMFailureCallback = (Throwable) -> Unit
typealias ITMQueryCallback<I, O> = (I, success: ITMSuccessCallback<O>?, failure: ITMFailureCallback?) -> Unit

/**
 * Class for sending and receiving messages to and from a [WebView][android.webkit.WebView] using
 * the `Messenger` class in `@itwin/mobile-sdk-core`.
 *
 * @param logger The [ITMLogger] to use for logging. If this is `null`, no logging happens.
 */
class ITMMessenger(logger: ITMLogger? = null): ITMMessengerProtocol {
    /**
     * Empty interface used for message handlers. Alias to [ITMQueryHandler] kept for backwards compatibility.
     */
    typealias ITMHandler = ITMQueryHandler

    /**
     * Transport that passes data to and from frontend running in a WebView.
     */
    private val frontendTransport: ITMFrontendQueryTransport
        get() = implementation.transport as ITMFrontendQueryTransport

    /**
     * Implementation of [ITMMessengerProtocol] to which this messenger delegates to.
     */
    private val implementation = ITMMessengerImpl("JS", "WVID", logger)

    init {
        val transport = ITMFrontendQueryTransport(implementation) { error, awaitingLaunch ->
            implementation.resetLaunch(error, awaitingLaunch)
        }
        implementation.transport = transport
    }

    /**
     * Logger used to log info about incoming and outgoing queries and errors during query handling.
     */
    var logger: ITMLogger?
        get() = implementation.logger
        set(value) {
            implementation.logger = value
        }

    /**
     * The [WebView][android.webkit.WebView] with which this [ITMMessenger] communicates.
     */
    var webView: WebView?
        get() = frontendTransport.webView
        set(value) {
            frontendTransport.webView = value
        }

    /** Attach before loading the frontend, using exact native-configured origins. */
    fun attach(webView: WebView, allowedOrigins: Set<String> = ITMBridgeOriginPolicy.DEFAULT_ALLOWED_ORIGINS) {
        frontendTransport.attach(webView, allowedOrigins)
    }

    /** Remove the listener and fail work belonging to the attached document. */
    fun detach() = frontendTransport.detach()

    /** Call before application navigation hooks. */
    fun onPageStarted(webView: WebView) = frontendTransport.onPageStarted(webView)

    /** Bootstrap the authorized document's response channel after loading. */
    fun onPageFinished(webView: WebView) = frontendTransport.onPageFinished(webView)

    /**
     * [Job] indicating that the frontend running in [webView] is ready to receive messages. All
     * calls to [send] and [query] will wait for this to complete before sending the message.
     */
    internal val frontendLaunchJob: Job
        get() = implementation.launchDeferred

    //region Companion Object

    companion object {
        /**
         * Whether or not logging of all messages is enabled.
         *
         * > __Note:__ This is shared with [com.github.itwin.mobilesdk.messaging.ITMBackendMessenger].
         */
        var isLoggingEnabled: Boolean
            get() = ITMMessengerLoggingOptions.isLoggingEnabled
            set(value) {
                ITMMessengerLoggingOptions.isLoggingEnabled = value
            }

        /**
         * Whether or not full logging of all messages (with their optional bodies) is enabled.
         *
         * __WARNING:__ You should only enable this in debug builds, since message bodies may
         * contain private information.
         *
         * > __Note:__ This is shared with [com.github.itwin.mobilesdk.messaging.ITMBackendMessenger].
         */
        var isFullLoggingEnabled: Boolean
            get() = ITMMessengerLoggingOptions.isFullLoggingEnabled
            set(value) {
                ITMMessengerLoggingOptions.isFullLoggingEnabled = value
            }

        /**
         * Add a query type to the list of unlogged queries.
         *
         * @see [ITMMessengerLoggingOptions.addUnloggedQueryType]
         */
        fun addUnloggedQueryType(type: String) = ITMMessengerLoggingOptions.addUnloggedQueryType(type)

        /**
         * Remove a query type from the list of unlogged queries.
         *
         * @see [ITMMessengerLoggingOptions.removeUnloggedQueryType]
         */
        fun removeUnloggedQueryType(type: String) = ITMMessengerLoggingOptions.removeUnloggedQueryType(type)
    }

    //endregion
    //region Public API

    /**
     * Must be called after the frontend has successfully launched, indicating that the frontend is
     * ready to receive queries.
     */
    fun frontendLaunchSucceeded() {
        implementation.launchSucceeded()
    }

    /**
     * Indicates if the frontend launch has completed.
     */
    val isFrontendLaunchComplete: Boolean
        get() = implementation.isLaunchComplete

    /**
     * Must be called if the frontend fails to launch. This prevents any queries from being sent to
     * the web view.
     *
     * @param error The reason for the failure.
     */
    fun frontendLaunchFailed(error: Throwable) {
        implementation.launchFailed(error)
    }

    override fun <I, O> query(
        type: String,
        data: I,
        success: ITMSuccessCallback<O>?,
        failure: ITMFailureCallback?
    ) {
        implementation.query(type, data, success, failure)
    }

    override fun <I, O> registerQueryHandler(
        type: String,
        callback: ITMQueryCallback<I, O>
    ): ITMQueryHandler {
        return implementation.registerQueryHandler(type, callback)
    }

    override fun removeHandler(handler: ITMQueryHandler?) {
        implementation.removeHandler(handler)
    }

    //endregion
}
