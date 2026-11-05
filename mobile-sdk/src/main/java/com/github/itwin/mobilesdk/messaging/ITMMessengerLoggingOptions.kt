/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/

package com.github.itwin.mobilesdk.messaging

import java.util.concurrent.ConcurrentHashMap

/**
 * Message logging settings shared by [com.github.itwin.mobilesdk.ITMMessenger] and [ITMBackendMessenger].
 */
object ITMMessengerLoggingOptions {
    /**
     * Whether or not logging of all messages is enabled.
     */
    var isLoggingEnabled = false

    /**
     * Whether or not full logging of all messages (with their optional bodies) is enabled.
     *
     * __WARNING:__ You should only enable this in debug builds, since message bodies may
     * contain private information.
     */
    var isFullLoggingEnabled = false

    /**
     * Set containing query types that are not logged.
     *
     * @see [addUnloggedQueryType]
     * @see [removeUnloggedQueryType]
     */
    private val unloggedQueryTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Add a query type to the list of unlogged queries.
     *
     * Unlogged queries are ignored when logging sent or received queries. This is useful (for example)
     * for queries that are themselves intended to produce log output, to prevent double log output.
     *
     * @param type The type of the query for which logging is disabled.
     *
     * @see [removeUnloggedQueryType]
     */
    fun addUnloggedQueryType(type: String) {
        unloggedQueryTypes.add(type)
    }

    /**
     * Remove a query type from the list of unlogged queries.
     *
     * @param type The type of the query to remove.
     *
     * @see [addUnloggedQueryType]
     */
    fun removeUnloggedQueryType(type: String) {
        unloggedQueryTypes.remove(type)
    }

    /**
     * Get query types for which queries are not logged.
     *
     * @see [addUnloggedQueryType]
     */
    fun getUnloggedQueryTypes(): Set<String> = unloggedQueryTypes
}
