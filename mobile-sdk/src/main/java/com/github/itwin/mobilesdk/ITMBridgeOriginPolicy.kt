package com.github.itwin.mobilesdk

import java.net.URI

/** Shared origin rules for native WebView bridges, including standalone bridge instances. */
object ITMBridgeOriginPolicy {
    val DEFAULT_ALLOWED_ORIGINS: Set<String> = setOf("https://appassets.itwinjs.org")

    /** Returns the exact HTTP(S) origin of a URL, or null for unsupported URLs. */
    fun canonicalOrigin(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null || uri.port !in -1..65535 || uri.port == 0) return null
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == defaultPort) -1 else uri.port
        return URI(scheme, null, host, port, null, null, null).toString()
    }

    /** Requires canonical origins without paths, credentials, wildcards, or default ports. */
    fun validatedOrigins(origins: Set<String>): Set<String> = origins.map { origin ->
        require(origin == canonicalOrigin(origin)) { "Expected an exact HTTP(S) origin: $origin" }
        origin
    }.toSet()
}
