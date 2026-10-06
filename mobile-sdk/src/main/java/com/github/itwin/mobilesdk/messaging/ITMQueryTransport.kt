/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

import com.github.itwin.mobilesdk.ITMLogger
import com.github.itwin.mobilesdk.jsonvalue.JSONValue

/**
 * Transport used by [ITMMessengerImpl] to send and receive queries and query responses.
 */
internal interface ITMQueryTransport {
    interface Delegate {
        /**
         * Called when a query is received from the other side of the transport.
         *
         * @param type The query type.
         * @param data Optional query data.
         */
        suspend fun onQuery(queryId: Int, type: String, data: JSONValue?): String

        /**
         * Optional logger the transport will log to.
         */
        val logger: ITMLogger?
    }

    /**
     * Delegate to which incoming queries are passed to.
     */
    val delegate: Delegate

    /**
     * Sends a query to the other side of the transport.
     *
     * @param queryId The query ID.
     * @param type The query type.
     * @param data Optional query data string.
     */
    suspend fun sendQuery(queryId: Int, type: String, data: String?): ITMQueryResponse
}
