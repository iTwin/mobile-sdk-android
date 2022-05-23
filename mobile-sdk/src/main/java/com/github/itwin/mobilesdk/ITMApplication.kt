/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused")

package com.github.itwin.mobilesdk

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.commit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.MutableLiveData
import com.bentley.itwin.AuthorizationClient
import com.bentley.itwin.IModelJsHost
import com.bentley.itwin.MobileFrontend
import com.eclipsesource.json.Json
import com.eclipsesource.json.JsonObject
import com.github.itwin.mobilesdk.jsonvalue.getOptionalLong
import com.github.itwin.mobilesdk.jsonvalue.getOptionalString
import com.github.itwin.mobilesdk.jsonvalue.isYes
import kotlinx.coroutines.*
import java.io.InputStreamReader
import java.lang.Float.max
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private enum class ReachabilityStatus {
    NotReachable,
    ReachableViaWiFi,
    ReachableViaWWAN,
}

/**
 * Holder for hash parameters used in the frontend URL.
 *
 * @property name The name of the hash parameter.
 * @property value The value of the hash parameter.
 */
class HashParam(val name: String, val value: String) {
    /**
     * @param name The name of the hash parameter.
     * @param value The value of the hash parameter as a Boolean.
     */
    constructor(name: String, value: Boolean): this(name, if (value) "YES" else "NO")
}

/**
 * Type alias for an array of HashParam values.
 */
typealias HashParams = Array<HashParam>

/**
 * Convert the array of HashParam values into a string suitable for use in a URL.
 */
fun HashParams.toUrlString(): String {
    if (this.isEmpty()) {
        return ""
    }
    return this.joinToString("&") { hashParam ->
        "${hashParam.name}=${URLEncoder.encode(hashParam.value, "utf-8")}"
    }
}

/**
 * Main class for interacting with an iTwin Mobile SDK-based web app.
 *
 * __Note:__ Most applications will override this class in order to customize the behavior and register for messages.
 *
 * @param appContext The Android Application object's `Context`.
 * @param attachWebViewLogger Whether or not to attach an [ITMWebViewLogger] to the application's
 * webView, default is `false`.
 * @param forceExtractBackendAssets Whether or not to always extract backend assets from during
 * application launch, default is `false`. Only set this to `true` for debug builds.
 */
abstract class ITMApplication(
    @Suppress("MemberVisibilityCanBePrivate") val appContext: Context,
    private val attachWebViewLogger: Boolean = false,
    private val forceExtractBackendAssets: Boolean = false) {

    /**
     * The `MobileUi.preferredColorScheme` value set by the TypeScript code.
     */
    enum class PreferredColorScheme(val value: Long) {
        Automatic(0),
        Light(1),
        Dark(2);

        companion object {
            private val allValues = values()

            /**
             * Convert a [Long] into a [PreferredColorScheme].
             *
             * @param value The value to convert to a [PreferredColorScheme].
             * @return The [PreferredColorScheme] corresponding to [value], or null.
             */
            fun fromLong(value: Long) = allValues.firstOrNull { it.value == value }
        }

        /**
         * Converts this [PreferredColorScheme] into an Android `NightMode` value.
         *
         * @return The `NightMode` value corresponding to this [PreferredColorScheme].
         */
        fun toNightMode() = when (this) {
            Automatic -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            Light -> AppCompatDelegate.MODE_NIGHT_NO
            Dark -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    /**
     * The [IModelJsHost] used by this [ITMApplication].
     */
    protected var host: IModelJsHost? = null

    /**
     * The fragment used to present geolocation permissions requests to the user.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected var geolocationFragment: ITMGeolocationFragment? = null

    /**
     * The fragment used to present a sign in UI to the user.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected var authorizationFragment: ITMAuthorizationFragment? = null

    /**
     * The AuthorizationClient used for authentication.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    protected var authorizationClient: AuthorizationClient? = null

    private var backendInitTask = Job()
    private var frontendInitTask = Job()
    private val _isBackendInitialized = AtomicBoolean(false)

    /**
     * Indicates whether or not the backend is done initializing.
     */
    val isBackendInitialized: Boolean get() = _isBackendInitialized.get()

    /**
     * The `MobileUi.preferredColorScheme` value set by the TypeScript code, default is automatic.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var preferredColorScheme = PreferredColorScheme.Automatic

    /**
     * The [WebView] that the web app runs in.
     */
    var webView: MobileFrontend? = null

    /**
     * The [ITMNativeUI] for this app.
     */
    var nativeUI: ITMNativeUI? = null

    /**
     * Whether or not the web app is loaded.
     */
    val isLoaded = MutableLiveData(false)

    /**
     * The base URl used by the frontend, without any index.html suffix.
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var frontendBaseUrl = ""

    /**
     * The [ITMMessenger] for communication between native code and JavaScript code (and vice versa).
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var messenger: ITMMessenger? = null

    /**
     * The [ITMCoMessenger] associated with [messenger].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var coMessenger: ITMCoMessenger? = null

    /**
     * The [ITMLogger] responsible for handling log messages (both from native code and JavaScript code). The default logger
     * uses [Log][android.util.Log] for the messages. Replace this object with an [ITMLogger] subclass to change the logging behavior.
     */
    var logger = ITMLogger()

    /**
     * The [ITMGeolocationManager] that handles Geolocation messages from the `navigator.geolocation` Polyfill in
     * `@itwin/mobile-sdk-core`. This value is initialized in [setupWebView].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var geolocationManager: ITMGeolocationManager? = null

    /**
     * The config data loaded from `ITMAppConfig.json`. This value is initialized in [finishInit].
     */
    @Suppress("MemberVisibilityCanBePrivate")
    var configData: JsonObject? = null
    private var webViewLogger: ITMWebViewLogger? = null
    private var reachabilityStatus = ReachabilityStatus.NotReachable

    /**
     * Kotlin Coroutine that waits for frontend initialization to complete, if it has not already completed.
     */
    suspend fun waitForFrontendInitialize() {
        frontendInitTask.join()
    }

    /**
     * Finish initialization, calling functions that can't go into the constructor because they are open.
     */
    open fun finishInit() {
        configData = loadITMAppConfig()
        configData?.let { configData ->
            if (configData.isYes("ITMAPPLICATION_MESSAGE_LOGGING")) {
                ITMMessenger.isLoggingEnabled = true
            }
            if (configData.isYes("ITMAPPLICATION_FULL_MESSAGE_LOGGING")) {
                ITMMessenger.isLoggingEnabled = true
                ITMMessenger.isFullLoggingEnabled = true
            }
        }
    }

    /**
     * Loads the contents of `ITMApplication/ITMAppConfig.json` from the app assets.
     *
     * Override this function to load the app config data in another way.
     *
     * The following keys in the returned value are used by iTwin Mobile SDK:
     *
     *     | Key                                 | Description                                                                                           |
     *     |-------------------------------------|-------------------------------------------------------------------------------------------------------|
     *     | ITMAPPLICATION_CLIENT_ID            | ITMOIDCAuthorizationClient required value containing the app's client ID.                                 |
     *     | ITMAPPLICATION_SCOPE                | ITMOIDCAuthorizationClient required value containing the app's scope.                                     |
     *     | ITMAPPLICATION_ISSUER_UR            | ITMOIDCAuthorizationClient optional value containing the app's issuer URL.                                |
     *     | ITMAPPLICATION_REDIRECT_URI         | ITMOIDCAuthorizationClient optional value containing the app's redirect URL.                              |
     *     | ITMAPPLICATION_MESSAGE_LOGGING      | Set to YES to have ITMMessenger log message traffic between JavaScript and Swift.                     |
     *     | ITMAPPLICATION_FULL_MESSAGE_LOGGING | Set to YES to include full message data in the ITMMessenger message logs. (DO NOT USE IN PRODUCTION.) |
     *
     * Note: Other keys may be present but are ignored by iTwin Mobile SDK. For example, the iTwin Mobile SDK sample apps include keys with an `ITMSAMPLE_` prefix.
     *
     * @return The parsed contents of `ITMApplication/ITMAppConfig.json`, or null if the file does not
     * exist, or there is an error parsing the file.
     */
    open fun loadITMAppConfig(): JsonObject? {
        val manager = appContext.assets
        try {
            val itmAppConfigStream = manager.open("ITMApplication/ITMAppConfig.json")
            return Json.parse(InputStreamReader(itmAppConfigStream, "UTF-8")) as JsonObject
        } catch (ex: Exception) {
            // Ignore
        }
        return null
    }

    /**
     * Initialize the iModelJs backend if it is not initialized yet. This can be called from the launch activity.
     */
    open fun initializeBackend() {
        if (_isBackendInitialized.getAndSet(true))
            return

        try {
            authorizationClient = createAuthorizationClient()
            host = IModelJsHost(appContext, forceExtractBackendAssets, authorizationClient).apply {
                setBackendPath(getBackendPath())
                setHomePath(getBackendHomePath())
                setEntryPointScript(getBackendEntryPointScript())
                startup()
            }
            backendInitTask.complete()
            logger.log(ITMLogger.Severity.Debug, "imodeljs backend loaded.")
        } catch (e: Exception) {
            reset()
            logger.log(ITMLogger.Severity.Error, "Error loading imodeljs backend: $e")
        }
    }

    /**
     * Initialize the iModelJs frontend if it is not initialized yet.
     *
     * This requires the Looper to be running, so cannot be called from the launch activity. If you have not already
     * called [initializeBackend], this will call it.
     *
     * @param fragmentActivity The [FragmentActivity] for the activity in which the frontend is running.
     * @param fragmentContainerId The resource ID of the [FragmentContainerView][androidx.fragment.app.FragmentContainerView]
     * into which to place UI fragments.
     */
    open fun initializeFrontend(fragmentActivity: FragmentActivity, @IdRes fragmentContainerId: Int) {
        initializeBackend()
        MainScope().launch {
            if (webView != null)
                return@launch

            try {
                backendInitTask.join()
                val args = getUrlHashParams().toUrlString()
                val baseUrl = getBaseUrl()
                val mobileFrontend = object : MobileFrontend(host, args) {
                    override fun supplyEntryPoint(): String {
                        return baseUrl
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) {
                        super.onConfigurationChanged(newConfig)
                        this@ITMApplication.nativeUI?.onConfigurationChanged(newConfig)
                    }

                    override fun overScrollBy(
                        deltaX: Int,
                        deltaY: Int,
                        scrollX: Int,
                        scrollY: Int,
                        scrollRangeX: Int,
                        scrollRangeY: Int,
                        maxOverScrollX: Int,
                        maxOverScrollY: Int,
                        isTouchEvent: Boolean
                    ): Boolean {
                        return false
                    }

                    @Suppress("EmptyFunctionBlock")
                    override fun scrollTo(x: Int, y: Int) {
                    }

                    @Suppress("EmptyFunctionBlock")
                    override fun computeScroll() {
                    }
                }
                webView = mobileFrontend
                setupWebView()
                mobileFrontend.loadEntryPoint()
                frontendBaseUrl = baseUrl.removeSuffix("index.html")
                host?.setFrontend(mobileFrontend)
                val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        super.onAvailable(network)
                        updateAvailability(true)
                    }

                    override fun onLost(network: Network) {
                        super.onLost(network)
                        updateAvailability(false)
                    }
                })
                nativeUI = createNativeUI(fragmentActivity)
                geolocationManager?.let { geolocationManager ->
                    fragmentActivity.supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        val frag = createGeolocationFragment(geolocationManager)
                        add(fragmentContainerId, frag)
                        geolocationFragment = frag
                    }
                }
                (authorizationClient as? ITMAuthorizationClient)?.let { authorizationClient ->
                    fragmentActivity.supportFragmentManager.commit {
                        setReorderingAllowed(true)
                        val frag = createAuthorizationFragment(authorizationClient)
                        add(fragmentContainerId, frag)
                        authorizationFragment = frag
                    }
                }
                frontendInitTask.complete()
            } catch (e: Exception) {
                coMessenger?.frontendLaunchFailed(e)
                reset()
                logger.log(ITMLogger.Severity.Error, "Error loading imodeljs frontend: $e")
            }
        }
    }

    /**
     * Call this from [onDestroy][Activity.onDestroy] in the activity that is showing the frontend.
     *
     * @param context The context for the [Activity] that is being destroyed.
     */
    open fun onActivityDestroy(context: Context) {
        webView?.setOnApplyWindowInsetsListener(null)
        geolocationManager?.stopLocationUpdates()
        geolocationManager?.setGeolocationFragment(null)
        geolocationFragment = null
        nativeUI?.detach()
        nativeUI = null
    }

    /**
     * Function that creates an [ITMNativeUI] object for this [ITMApplication]. Override to return a
     * custom [ITMNativeUI] subclass.
     */
    open fun createNativeUI(context: Context): ITMNativeUI? {
        webView?.let { webView ->
            coMessenger?.let { coMessenger ->
                return ITMNativeUI(context, webView, coMessenger)
            }
        }
        return null
    }

    /**
     * Clean up any existing frontend and initialize the frontend again.
     *
     * __Note:__ Call this if the [WebView] runs out of memory, killing the web app.
     *
     * @param fragmentActivity The [FragmentActivity] for the activity in which the frontend is running.
     * @param fragmentContainerId The resource ID of the [FragmentContainerView][androidx.fragment.app.FragmentContainerView]
     * into which to place UI fragments.
     */
    open fun reinitializeFrontend(fragmentActivity: FragmentActivity, @IdRes fragmentContainerId: Int) {
        webView = null
        messenger = null
        coMessenger = null
        isLoaded.value = false
        initializeFrontend(fragmentActivity, fragmentContainerId)
    }

    private fun updateAvailability(available: Boolean? = null) {
        available?.let {
            reachabilityStatus = ReachabilityStatus.NotReachable
            if (it) {
                val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                reachabilityStatus = if (connectivityManager.isActiveNetworkMetered) ReachabilityStatus.ReachableViaWWAN else ReachabilityStatus.ReachableViaWiFi
            }
        }
        MainScope().launch {
            webView?.evaluateJavascript("window.Bentley_InternetReachabilityStatus = ${reachabilityStatus.ordinal}", null)
        }
    }

    private fun reset() {
        backendInitTask.cancel()
        backendInitTask = Job()
        frontendInitTask.cancel()
        frontendInitTask = Job()
        _isBackendInitialized.set(false)
        webView = null
        messenger = null
        coMessenger = null
    }

    /**
     * Function that creates an [ITMMessenger] object for this [ITMApplication]. Override to return a
     * custom [ITMMessenger] subclass.
     */
    open fun createMessenger(): ITMMessenger {
        return ITMMessenger(this)
    }

    /**
     * Set up [webView] for usage with iTwin Mobile SDK.
     */
    protected open fun setupWebView() {
        val webView = this.webView ?: return
        messenger = createMessenger()
        if (attachWebViewLogger) {
            webViewLogger = ITMWebViewLogger(webView, ::onWebViewLog)
        }
        coMessenger = ITMCoMessenger(messenger!!)
        messenger?.addMessageListener("Bentley_ITM_updatePreferredColorScheme") { value ->
            value?.asObject()?.getOptionalLong("preferredColorScheme")?.let { longValue ->
                preferredColorScheme = PreferredColorScheme.fromLong(longValue) ?: PreferredColorScheme.Automatic
                MainScope().launch {
                    AppCompatDelegate.setDefaultNightMode(preferredColorScheme.toNightMode())
                }
            }
        }
        webView.settings.setSupportZoom(false)
        webView.webViewClient = object : WebViewClient() {
            fun shouldIgnoreUrl(url: String): Boolean {
                return url.startsWith("file:///android_asset/frontend")
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (shouldIgnoreUrl(url)) {
                    return
                }
                this@ITMApplication.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (shouldIgnoreUrl(url)) {
                    return
                }
                isLoaded.value = true
                this@ITMApplication.onPageFinished(view, url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val result = this@ITMApplication.shouldInterceptRequest(view, request)
                return result ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (this@ITMApplication.shouldOverrideUrlLoading(view, request)) {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (this@ITMApplication.onRenderProcessGone(view, detail)) {
                    return true
                }
                return super.onRenderProcessGone(view, detail)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!this@ITMApplication.onReceivedError(view, request, error)) {
                    super.onReceivedError(view, request, error)
                }
            }
        }
        geolocationManager = ITMGeolocationManager(appContext, webView)
    }

    /**
     * Attach [webView] to the given [ViewGroup].
     *
     * @param container: The [ViewGroup] into which to place [webView].
     */
    open fun attachWebView(container: ViewGroup) {
        webView?.let { webView ->
            (webView.parent as? ViewGroup)?.removeView(webView)
            container.addView(webView)
            webView.setOnApplyWindowInsetsListener { v, insets ->
                updateSafeAreas(v)
                v.onApplyWindowInsets(insets)
            }
        }
    }

    private fun updateSafeAreas(view: View) {
        val activity = ((view.parent as? ViewGroup)?.context as? Activity) ?: return
        val window = activity.window ?: return
        val message = JsonObject()
        message["left"] = 0
        message["right"] = 0
        message["top"] = 0
        message["bottom"] = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.decorView.rootWindowInsets?.displayCutout?.let { displayCutoutInsets ->
                val density = activity.resources.displayMetrics.density
                val top = displayCutoutInsets.safeInsetTop / density
                val bottom = displayCutoutInsets.safeInsetBottom / density
                val left = displayCutoutInsets.safeInsetLeft / density
                val right = displayCutoutInsets.safeInsetRight / density
                // Make both sides have the same safe area.
                val sides = max(left, right)
                message["left"] = sides
                message["right"] = sides
                // Include the actual left/right insets so developers can use them if desired.
                message["minLeft"] = left
                message["minRight"] = right
                message["top"] = top
                message["bottom"] = bottom
            }
        }
        messenger?.send("Bentley_ITM_muiUpdateSafeAreas", message)
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onPageFinished].
     *
     * Make sure to call super if you override this function.
     */
    open fun onPageFinished(view: WebView, url: String) {
        webViewLogger?.inject()
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onPageStarted].
     *
     * Make sure to call super if you override this function.
     */
    open fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        updateAvailability()
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.shouldInterceptRequest].
     *
     * This default implementation simply returns null.
     */
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        return null
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.shouldOverrideUrlLoading].
     *
     * This default implementation simply returns false.
     */
    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        return false
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onRenderProcessGone].
     *
     * This default implementation simply returns false.
     */
    open fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        return false
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onReceivedError].
     *
     * This default implementation simply returns false.
     */
    open fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError): Boolean {
        return false
    }

    /**
     * Callback function for the [ITMWebViewLogger] attached to [webView] (if any).
     */
    open fun onWebViewLog(type: ITMWebViewLogger.LogType, message: String) {
        logger.log(ITMLogger.Severity.fromString(type.name), message)
    }

    /**
     * Override to open the given [Uri].
     *
     * @param uri The [Uri] to open
     */
    abstract fun openUri(uri: Uri)

    /**
     * Get the relative path used as the home path for the backend.
     *
     * @return The relative path to where the backend home should go. The default implementation returns
     * `"ITMApplication/home"`.
     */
    open fun getBackendHomePath(): String {
        return "ITMApplication/home"
    }

    /**
     * Get the relative path used where the backend is stored in the app assets.
     *
     * @return The relative path to where the backend home should go. The default implementation returns
     * `"ITMApplication/backend"`.
     */
    open fun getBackendPath(): String {
        return "ITMApplication/backend"
    }

    /**
     * Get name of the entry point JavaScript file for the backend.
     *
     * @return The relative path to where the backend home should go. The default implementation returns
     * `"main.js"`.
     */
    open fun getBackendEntryPointScript(): String {
        return "main.js"
    }

    /**
     * Get the base URL for the frontend.
     *
     * @return The URL to use for the frontend. The default uses the `ITMAPPLICATION_BASE_URL` value from [configData],
     * if present, or `"file:///android_asset/ITMApplication/frontend/index.html"` otherwise.
     */
    open fun getBaseUrl(): String {
        configData?.getOptionalString("ITMAPPLICATION_BASE_URL")?.let { baseUrl ->
            return baseUrl
        }
        return "file:///android_asset/ITMApplication/frontend/index.html"
    }

    /**
     * Override to add custom hash parameters to the URL used to open the frontend.
     */
    open suspend fun getUrlHashParams(): HashParams {
        return emptyArray()
    }

    /**
     * Creates the [AuthorizationClient] to be used for this iTwin Mobile web app.
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * If your application handles authorization on its own, create a subclass of [AuthorizationClient] or
     * [ITMAuthorizationClient].
     *
     * @return An instance of [AuthorizationClient], or null if you don't want any authentication in your app.
     */
    open fun createAuthorizationClient(): AuthorizationClient? {
        configData?.let { configData ->
            return ITMOIDCAuthorizationClient(this, configData)
        }
        return null
    }

    /**
     * Creates the [ITMGeolocationFragment] to be used for this iTwin Mobile web app.
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * @param geolocationManager The [ITMGeolocationManager] to use with the fragment.
     *
     * @return An instance of [ITMGeolocationFragment] attached to [geolocationManager].
     */
    open fun createGeolocationFragment(geolocationManager: ITMGeolocationManager): ITMGeolocationFragment {
        return ITMGeolocationFragment(geolocationManager)
    }

    /**
     * Creates the [ITMAuthorizationFragment] to be used for this iTwin Mobile web app.
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * __Note:__ If your [AuthorizationClient] is not a subclass of [ITMAuthorizationClient], this function
     * will never be called. If you need a fragment to go with it, you are responsible for setting it up yourself.
     * If your [AuthorizationClient] _is_ a subclass of [ITMAuthorizationClient], then the [client] param
     * passed here will be an instance of that class.
     *
     * @param client The [ITMAuthorizationClient] to use with the fragment.
     *
     * @return An instance of [ITMOIDCAuthorizationFragment] attached to [client]. If this default implementation
     * is called, [authorizationClient] __must__ be an [ITMOIDCAuthorizationClient].
     */
    open fun createAuthorizationFragment(client: ITMAuthorizationClient): ITMAuthorizationFragment {
        val oidcClient = client as? ITMOIDCAuthorizationClient ?: throw Error("client is not ITMOIDCAuthorizationClient.")
        return ITMOIDCAuthorizationFragment(oidcClient)
    }
//    private fun loadFrontend() {
//        val host = this.host ?: return
//        MainScope().launch {
//            val baseUrl = getBaseUrl()
//            val fullUrl = baseUrl + "#&platform=android&port=" + host.port + getUrlHashParams()
//            webView?.loadUrl(fullUrl)
//            frontendBaseUrl = baseUrl.removeSuffix("index.html")
//        }
//    }
}