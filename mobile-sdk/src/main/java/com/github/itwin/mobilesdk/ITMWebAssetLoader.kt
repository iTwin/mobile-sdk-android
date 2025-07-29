/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
package com.github.itwin.mobilesdk

import android.content.Context
import android.content.res.AssetManager
import android.net.Uri
import android.webkit.WebResourceResponse
import java.net.URLConnection

/**
 * Class that implements [shouldInterceptRequest] to load app assets any time a URL with the
 * prefix `https://appassets.itwinjs.org/assets/` is used.
 *
 * @param context: The `Context` used to load assets.
 */
class ITMWebAssetLoader(private val context: Context) {
    companion object {
        /**
         * The URL prefix that this asset loader looks for to indicate that a URL should load a
         * local app asset instead of the actual URL.
         */
        const val URL_PREFIX = "https://appassets.itwinjs.org/assets/"

        /**
         * The default MIME type to use for unrecognized file extensions.
         */
        const val DEFAULT_MIME_TYPE = "text/plain"

        /**
         * The path prefix to the frontend's index.html file under assets. Since the vite-based
         * frontend considers index.html to be in /, this will be used as a prefix when asset
         * lookups fail.
         */
        const val FRONTEND_PREFIX = "ITMApplication/frontend/"
    }

    private fun guessMimeType(path: String) =
        URLConnection.guessContentTypeFromName(path) ?: DEFAULT_MIME_TYPE

    /**
     * Checks the given [Uri], and if it has a prefix of [URL_PREFIX]
     * (`https://appassets.itwinjs.org/assets/`), loads the associated data from the given app
     * asset.
     *
     * @param url: The [Uri] of the request to load.
     *
     * @return: A WebResourceResponse configured to load the given app asset if the URL has a prefix
     * of [URL_PREFIX], or `null` otherwise. Note that even if the file is not present in app assets
     * for a URL with the appropriate prefix, this will still return a [WebResourceResponse]. It's
     * just that the response won't load any data. This is intentional, since any URL using the
     * prefix is expected to be loaded from app assets.
     */
    fun shouldInterceptRequest(url: Uri): WebResourceResponse? {
        val urlString = url.toString()
        if (urlString.indexOf(URL_PREFIX) != 0) {
            return null
        }
        var path = urlString.substring(URL_PREFIX.length)
        val hashIndex = path.indexOf("#")
        if (hashIndex != -1) {
            path = path.substring(0, hashIndex)
        }
        val questionIndex = path.lastIndexOf("?")
        if (questionIndex != -1) {
            path = path.substring(0, questionIndex)
        }
        var asset = catchToNull { context.assets.open(path, AssetManager.ACCESS_STREAMING) }
        if (asset == null) {
            path = "${FRONTEND_PREFIX}assets/${path}"
            asset = catchToNull { context.assets.open(path, AssetManager.ACCESS_STREAMING) }
        }
        return if (asset != null) {
            WebResourceResponse(guessMimeType(path), "utf-8", asset)
        } else {
            WebResourceResponse(null, null, null)
        }
    }
}
