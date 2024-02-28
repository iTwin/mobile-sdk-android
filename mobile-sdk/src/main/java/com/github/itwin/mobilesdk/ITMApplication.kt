/*---------------------------------------------------------------------------------------------
* Copyright (c) Bentley Systems, Incorporated. All rights reserved.
* See LICENSE.md in the project root for license terms and full copyright notice.
*--------------------------------------------------------------------------------------------*/
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package com.github.itwin.mobilesdk

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.system.Os
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.annotation.CallSuper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import com.bentley.itwin.AuthorizationClient
import com.bentley.itwin.IModelJsHost
import com.github.itwin.mobilesdk.jsonvalue.isYes
import com.github.itwin.mobilesdk.jsonvalue.optStringOrNull
import com.github.itwin.mobilesdk.jsonvalue.toMap
import kotlinx.coroutines.*
import org.json.JSONObject
import java.lang.Float.max
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.properties.Delegates

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
    companion object {
        /**
         * Create a string hash param from a value in configData.
         * @param configData The source of the config data for the [HashParam].
         * @param configKey The key to use to look up the value in [configData].
         * @param name The name of the [HashParam]
         * @return A [HashParam] with the string value contained in the given value in [configData],
         * or null if [configData] is null or the value does not exist.
         */
        fun fromConfigData(configData: JSONObject?, configKey: String, name: String) =
            configData?.optStringOrNull(configKey)?.let {
                HashParam(name, it)
            }
    }

    /**
     * @param name The name of the hash parameter.
     * @param value The value of the hash parameter as a Boolean.
     */
    constructor(name: String, value: Boolean): this(name, if (value) "YES" else "NO")
}

/**
 * Type alias for a list of HashParam values.
 */
typealias HashParams = List<HashParam>

/**
 * Convert the list of HashParam values into a string suitable for use in a URL.
 */
fun HashParams.toUrlString(): String {
    if (this.isEmpty()) {
        return ""
    }
    return "&" + this.joinToString("&") {
        "${it.name}=${URLEncoder.encode(it.value, "utf-8")}"
    }
}

/**
 * Main class for interacting with an iTwin Mobile SDK-based web app.
 *
 * > __Note:__ Most applications will override this class in order to customize the behavior and
 * register for messages.
 *
 * @param appContext The Android Application object's `Context`.
 * @param attachWebViewLogger Whether or not to attach an [ITMWebViewLogger] to the application's
 * webView, default is `false`.
 * @param forceExtractBackendAssets Whether or not to always extract backend assets during
 * application launch, default is `false`. Set this to `true` for debug builds where the backend
 * assets might change without a version number change, since backend assets are normally only
 * extracted after a fresh install or when the app's version number changes.
 */
open class ITMApplication(
    val appContext: Context,
    private val attachWebViewLogger: Boolean = false,
    private val forceExtractBackendAssets: Boolean = false) {

    /**
     * Enum for the `MobileUi.preferredColorScheme` value set by the TypeScript code.
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
            Light     -> AppCompatDelegate.MODE_NIGHT_NO
            Dark      -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    /**
     * The [IModelJsHost] used by this [ITMApplication].
     */
    protected var host: IModelJsHost? = null

    /**
     * The AuthorizationClient used for authentication.
     */
    protected var authorizationClient: AuthorizationClient? = null

    /**
     * Tracks whether the frontend URL is on a remote server (used for debugging via react-scripts).
     */
    var usingRemoteServer = false

    private var backendInitTask = Job()
    private var frontendInitTask = Job()
    private val _isBackendInitialized = AtomicBoolean(false)

    /**
     * Indicates whether or not the backend is done initializing.
     */
    val isBackendInitialized: Boolean get() = _isBackendInitialized.get()

    /**
     * The `MobileUi.preferredColorScheme` value set by the TypeScript code, default is
     * [Automatic][PreferredColorScheme.Automatic].
     */
    var preferredColorScheme = PreferredColorScheme.Automatic

    /**
     * The [WebView] that the web app runs in.
     */
    var webView: WebView? = null

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
    var frontendBaseUrl = ""

    /**
     * The [ITMLogger] responsible for handling log messages (both from native code and JavaScript
     * code). The default logger uses [Log][android.util.Log] for the messages. Replace this object
     * with an [ITMLogger] subclass to change the logging behavior.
     *
     * > __Note:__ Updating this value also updates the logger being used by [messenger].
     */
    var logger: ITMLogger by Delegates.observable(ITMLogger()) { _, _, new ->
        messenger.logger = new
    }

    /**
     * The [ITMMessenger] for communication between native code and JavaScript code (and vice
     * versa).
     */
    var messenger = ITMMessenger(logger)

    /**
     * The [ITMCoMessenger] associated with [messenger].
     */
    var coMessenger = ITMCoMessenger(messenger)

    /**
     * The [ITMGeolocationManager] that handles Geolocation messages from the
     * `navigator.geolocation` Polyfill in `@itwin/mobile-sdk-core`. This value is initialized in
     * [setupWebView].
     */
    var geolocationManager: ITMGeolocationManager? = null

    /**
     * The config data loaded from `ITMAppConfig.json`. This value is initialized in [finishInit].
     */
    var configData: JSONObject? = null
    private var webViewLogger: ITMWebViewLogger? = null
    private var reachabilityStatus = ReachabilityStatus.NotReachable

    /**
     * An [ITMWebAssetLoader] that intercepts https requests that begin with
     * https://appassets.itwinjs.org/assets and loads the local files in the app assets. All other
     * requests are ignored (meaning that the default behavior happens).
     */
    val assetLoader = ITMWebAssetLoader(appContext)

    /**
     * Kotlin Coroutine that waits for frontend initialization to complete, if it has not already
     * completed.
     */
    suspend fun waitForFrontendInitialize() {
        frontendInitTask.join()
    }

    /**
     * Kotlin Coroutine that waits for backend initialization to complete, if it has not already
     * completed.
     */
    suspend fun waitForBackendInitialize() {
        backendInitTask.join()
    }

    /**
     * Finish initialization, calling functions that can't go into the constructor because they are
     * open.
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

            // Add the configuration vars with an "ITMAPPLICATION_" prefix to the environment so
            // they can be picked up by other code (i.e. the typescript backend)
            configData.toMap().forEach {
                if (it.key.startsWith("ITMAPPLICATION_")) {
                    Os.setenv(it.key, it.value as String, true)
                }
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
     *     | Key                                 | Description                                                 |
     *     |-------------------------------------|-------------------------------------------------------------|
     *     | ITMAPPLICATION_CLIENT_ID            | ITMOIDCAuthorizationClient required value containing the    |
     *     |                                     | app client ID.                                              |
     *     | ITMAPPLICATION_SCOPE                | ITMOIDCAuthorizationClient required value containing the    |
     *     |                                     | app scope.                                                  |
     *     | ITMAPPLICATION_ISSUER_UR            | ITMOIDCAuthorizationClient optional value containing the    |
     *     |                                     | app issuer URL.                                             |
     *     | ITMAPPLICATION_REDIRECT_URI         | ITMOIDCAuthorizationClient optional value containing the    |
     *     |                                     | app redirect URL.                                           |
     *     | ITMAPPLICATION_MESSAGE_LOGGING      | Set to YES to have ITMMessenger log message traffic between |
     *     |                                     | JavaScript and Swift.                                       |
     *     | ITMAPPLICATION_FULL_MESSAGE_LOGGING | Set to YES to include full message data in the ITMMessenger |
     *     |                                     | message logs. (DO NOT USE IN PRODUCTION.)                   |
     *
     * Note: Other keys may be present but are ignored by iTwin Mobile SDK. For example, the iTwin
     * Mobile SDK sample apps include keys with an `ITMSAMPLE_` prefix.
     *
     * @return The parsed contents of `ITMApplication/ITMAppConfig.json`, or null if the file does
     * not exist, or there is an error parsing the file.
     */
    open fun loadITMAppConfig() = try {
        JSONObject(appContext.assets.open("ITMApplication/ITMAppConfig.json").bufferedReader().use { it.readText() })
    } catch (ex: Exception) {
        null
    }

    /**
     * Initialize the iTwinJS backend if it is not initialized yet. This can be called from the
     * launch activity.
     *
     * @param allowInspectBackend Allow inspection of the backend code, default false.
     */
    open fun initializeBackend(allowInspectBackend: Boolean = false) {
        if (_isBackendInitialized.getAndSet(true))
            return

        try {
            host = IModelJsHost(appContext, forceExtractBackendAssets, provideAuthorizationClient(), allowInspectBackend).apply {
                setBackendPath(getBackendPath())
                setHomePath(getBackendHomePath())
                setEntryPointScript(getBackendEntryPointScript())
                startup()
            }
            backendInitTask.complete()
            logger.log(ITMLogger.Severity.Debug, "iTwinJS backend loaded.")
        } catch (e: Exception) {
            reset()
            logger.log(ITMLogger.Severity.Error, "Error loading iTwinJS backend: $e")
        }
    }

    /**
     * Finish initialization of the frontend including creating the nativeUI and completing the
     * frontendInitTask.
     *
     * @param context The [Context] to use to create the native UI.
     */
    open fun finishInitializeFrontend(context: Context) {
        nativeUI = createNativeUI(context)
        frontendInitTask.complete()
    }

    /**
     * Associate with the given activity and then initialize the frontend.
     *
     * If you have not already called [initializeBackend], this will call it.
     *
     * @param activity The activity to associate with and context to use during initialization.
     * @param allowInspectBackend Allow inspection of the backend code, default false. Do not set
     * this to `true` in production builds.
     */
    open fun initializeFrontend(activity: ComponentActivity, allowInspectBackend: Boolean = false) {
        associateWithActivity(activity)
        initializeFrontend(activity as Context, allowInspectBackend)
    }

    private val connectivityManager
        get() = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            updateAvailability(true)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            updateAvailability(false)
        }
    }

    /**
     * Initialize the iTwinJS frontend if it is not initialized yet.
     *
     * This requires the Looper to be running, so cannot be called from the launch activity.
     *
     * If you have not already called [initializeBackend], this will call it.
     *
     * @param context The [Context].
     * @param allowInspectBackend Allow inspection of the backend code, default false. Do not set
     * this to `true` in production builds.
     * @param existingWebView An existing webview to use instead of creating one. If you pass a
     * value here, you need to manually call
     * [onConfigurationChanged][ITMNativeUI.onConfigurationChanged] on [nativeUI].
     */
    open fun initializeFrontend(context: Context, allowInspectBackend: Boolean = false, existingWebView: WebView? = null) {
        // Note: geolocationManager needs to be created *before* the activity has started
        geolocationManager = provideGeolocationManager()

        initializeBackend(allowInspectBackend)
        if (webView != null) {
            frontendInitTask.cancel()
            frontendInitTask = Job()
        }

        MainScope().launch {
            if (webView != null) {
                finishInitializeFrontend(context)
                return@launch
            }
            try {
                // Wait for backend to finish initializing.
                backendInitTask.join()
                webView = existingWebView ?: object : WebView(context) {
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
                    ) = false

                    @Suppress("EmptyFunctionBlock")
                    override fun scrollTo(x: Int, y: Int) {
                    }

                    @Suppress("EmptyFunctionBlock")
                    override fun computeScroll() {
                    }
                }
                val baseUrl = getBaseUrl()
                setupWebView()
                frontendBaseUrl = baseUrl.removeSuffix("index.html")
                host?.let {
                    it.webView = webView
                    it.loadEntryPoint(baseUrl, getUrlHashParams().toUrlString())
                }
                connectivityManager.registerDefaultNetworkCallback(connectivityCallback)
                if (usingRemoteServer) {
                    MainScope().launch {
                        delay(10000)
                        if (!messenger.isFrontendLaunchComplete) {
                            with(AlertDialog.Builder(context)) {
                                setTitle(R.string.itm_error)
                                setMessage(context.getString(R.string.itm_debug_server_error, baseUrl))
                                setCancelable(false)
                                setPositiveButton(R.string.itm_ok) { _, _ -> }
                                show()
                            }
                        }
                    }
                }
                finishInitializeFrontend(context)
            } catch (error: Throwable) {
                coMessenger.frontendLaunchFailed(error)
                reset()
                logger.log(ITMLogger.Severity.Error, "Error loading iTwinJS frontend: $error")
            }
        }
    }


    /**
     * Call this from [onPause][Activity.onPause] in the activity that is showing the frontend.
     *
     * Note: this is only necessary if the activity has NOT already been associated via
     * [initializeFrontend] or [associateWithActivity].
     */
    open fun onActivityPause() {
        host?.onPause()
    }

    /**
     * Call this from [onResume][Activity.onResume] in the activity that is showing the frontend.
     *
     * Note: this is only necessary if the activity has NOT already been associated via
     * [initializeFrontend] or [associateWithActivity].
     */
    open fun onActivityResume() {
        host?.onResume()
    }

    /**
     * Call this from [onDestroy][Activity.onDestroy] in the activity that is showing the frontend.
     *
     * Note: this is only necessary if the activity has NOT already been associated via
     * [initializeFrontend] or [associateWithActivity].
     */
    open fun onActivityDestroy() {
        webView?.setOnApplyWindowInsetsListener(null)
        authorizationClient = null
        nativeUI?.detach()
        nativeUI = null
    }

    /**
     * Function that creates an [ITMNativeUI] object for this [ITMApplication]. Override to return a
     * custom [ITMNativeUI] subclass.
     */
    @CallSuper
    open fun createNativeUI(context: Context) =
        webView?.let {
            ITMNativeUI(context, it, coMessenger).apply { registerStandardComponents() }
        }

    /**
     * Clean up any existing frontend and initialize the frontend again.
     *
     * > __Note:__ Call this if the [WebView] runs out of memory, killing the web app.
     *
     * @param context The [Context].
     */
    open fun reinitializeFrontend(context: Context) {
        webViewLogger?.detach()
        webViewLogger = null
        webView = null
        messenger = ITMMessenger(logger)
        coMessenger = ITMCoMessenger(messenger)
        isLoaded.value = false
        connectivityManager.unregisterNetworkCallback(connectivityCallback)
        initializeFrontend(context)
    }

    private fun updateAvailability(available: Boolean? = null) {
        available?.let {
            reachabilityStatus = ReachabilityStatus.NotReachable
            if (it) {
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
        webViewLogger?.detach()
        webViewLogger = null
        webView = null
        messenger = ITMMessenger(logger)
        coMessenger = ITMCoMessenger(messenger)
        connectivityManager.unregisterNetworkCallback(connectivityCallback)
    }

    /**
     * Function that creates an [ITMMessenger] object for this [ITMApplication]. Override to return
     * a custom [ITMMessenger] subclass.
     *
     * > __Note:__ The value returned from here will have its [logger][ITMMessenger.logger] updated
     * any time this [ITMApplication]'s [logger] is updated.
     */
    open fun createMessenger() = ITMMessenger(logger)

    /**
     * Update the application to conform to the [preferredColorScheme].
     */
    open fun applyPreferredColorScheme() {
        AppCompatDelegate.setDefaultNightMode(preferredColorScheme.toNightMode())
    }

    /**
     * Set up [webView] for usage with iTwin Mobile SDK.
     */
    protected open fun setupWebView() {
        val webView = this.webView ?: return
        messenger.webView = webView
        if (attachWebViewLogger) {
            webViewLogger = ITMWebViewLogger(webView, ::onWebViewLog)
        }
        coMessenger.registerQueryHandler<Map<String, Number>, Unit>("Bentley_ITM_updatePreferredColorScheme") { params ->
            params.getOptionalLong("preferredColorScheme")?.let {
                preferredColorScheme = PreferredColorScheme.fromLong(it) ?: PreferredColorScheme.Automatic
                applyPreferredColorScheme()
            }
        }
        webView.settings.setSupportZoom(false)
        webView.webViewClient = object : WebViewClient() {
            fun shouldIgnoreUrl(url: String) =
                url.startsWith("file:///android_asset/frontend")

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
                return this@ITMApplication.shouldInterceptRequest(view, request) ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return this@ITMApplication.shouldOverrideUrlLoading(view, request) || super.shouldOverrideUrlLoading(view, request)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                return this@ITMApplication.onRenderProcessGone(view, detail) || super.onRenderProcessGone(view, detail)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!this@ITMApplication.onReceivedError(view, request, error)) {
                    super.onReceivedError(view, request, error)
                }
            }
        }
        geolocationManager?.webView = webView
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
            webView.setOnApplyWindowInsetsListener { view, insets ->
                updateSafeAreas(view, insets)
                view.onApplyWindowInsets(insets)
            }
        }
    }

    private data class SafeAreaValues(val left: Float, val right: Float, val top: Float, val bottom: Float) {
        val sides by lazy { max(left, right) }
    }

    private fun getSafeAreas(activity: Activity, view: View, insets: WindowInsets): SafeAreaValues {
        fun toDp(value: Int): Float {
            val density = activity.resources.displayMetrics.density
            return value / density
        }
        var insetsType = WindowInsetsCompat.Type.displayCutout()
        // Ideally we'd always get system bars insets but before API 30 getInsets() returns bars insets even if they are overlaid on top.
        if (activity.isInMultiWindowMode)
            insetsType = insetsType or WindowInsetsCompat.Type.systemBars()

        return with(WindowInsetsCompat.toWindowInsetsCompat(insets, view).getInsets(insetsType)) {
            SafeAreaValues(toDp(left), toDp(right), toDp(top), toDp(bottom))
        }
    }

    private fun updateSafeAreas(view: View, insets: WindowInsets) {
        val activity = ((view.parent as? ViewGroup)?.context as? Activity) ?: return
        with(getSafeAreas(activity, view, insets)) {
            val message = mapOf(
                // We want both sides to have the same safe area.
                "left" to sides,
                "right" to sides,
                // Include the actual left/right insets so developers can use them if desired.
                "minLeft" to left,
                "minRight" to right,
                "top" to top,
                "bottom" to bottom,
            )
            messenger.send("Bentley_ITM_muiUpdateSafeAreas", message)
        }
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onPageFinished].
     */
    @CallSuper
    open fun onPageFinished(view: WebView, url: String) {
        webViewLogger?.inject()
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onPageStarted].
     */
    @CallSuper
    open fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        updateAvailability()
    }

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.shouldInterceptRequest].
     */
    open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
        assetLoader.shouldInterceptRequest(request.url)

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.shouldOverrideUrlLoading].
     *
     * This default implementation simply returns false.
     */
    open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onRenderProcessGone].
     *
     * This default implementation simply returns false.
     */
    open fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = false

    /**
     * Called when the [webView]'s [WebViewClient] calls [WebViewClient.onReceivedError].
     *
     * This default implementation simply returns false.
     *
     * @return `true` to prevent the [WebViewClient] from calling `super.onReceivedError`.
     */
    open fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) = false

    /**
     * Callback function for the [ITMWebViewLogger] attached to [webView] (if any).
     */
    open fun onWebViewLog(type: ITMWebViewLogger.LogType, message: String) {
        logger.log(ITMLogger.Severity.fromString(type.name), message)
    }

    /**
     * Get the relative path used as the home path for the backend.
     *
     * @return The relative path to where the backend home should go. The default implementation
     * returns `"ITMApplication/home"`.
     */
    open fun getBackendHomePath() = "ITMApplication/home"

    /**
     * Get the relative path used where the backend is stored in the app assets.
     *
     * @return The relative path to where the backend should go. The default implementation returns
     * `"ITMApplication/backend"`.
     */
    open fun getBackendPath() = "ITMApplication/backend"

    /**
     * Get name of the entry point JavaScript file for the backend.
     *
     * @return The name of the entry point JavaScript file for the backend. The default
     * implementation returns `"main.js"`.
     */
    open fun getBackendEntryPointScript() = "main.js"

    /**
     * Get the base URL for the frontend.
     *
     * @return The URL to use for the frontend. The default uses the `ITMAPPLICATION_BASE_URL` value
     * from [configData], if present, or
     * `"https://appassets.itwinjs.org/assets/ITMApplication/frontend/index.html"` otherwise.
     *
     * > __Note:__ The default URL is designed to work with [ITMWebAssetLoader].
     */
    open fun getBaseUrl(): String {
        configData?.optStringOrNull("ITMAPPLICATION_BASE_URL")?.let {
            usingRemoteServer = true
            return it
        }
        usingRemoteServer = false
        return "${ITMWebAssetLoader.URL_PREFIX}ITMApplication/frontend/index.html"
    }

    /**
     * Generates the list of [HashParam] values to be used in the web app's URL. Override to add
     * custom hash parameters to the URL used to open the frontend. If you override this function,
     * you must include the results from super in your list.
     *
     * @return The default [ITMApplication] [HashParam] values based on the contents of
     * [configData].
     */
    @CallSuper
    open suspend fun getUrlHashParams(): HashParams =
        listOfNotNull(
            HashParam.fromConfigData(configData, "ITMAPPLICATION_API_PREFIX", "apiPrefix")
        )

    /**
     * Creates the [AuthorizationClient] to be used for this iTwin Mobile web app.
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * If your application handles authorization on its own, create a subclass of
     * [AuthorizationClient].
     *
     * @return An instance of [AuthorizationClient], or `null` if you don't want any authentication
     * in your app.
     */
    open fun createAuthorizationClient(): AuthorizationClient? =
        configData?.let {
            ITMOIDCAuthorizationClient(this, it)
        }

    /**
     * If the [authorizationClient] is `null`, first sets it using [createAuthorizationClient].
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * @return The [authorizationClient] value.
     */
    open fun provideAuthorizationClient() =
        authorizationClient ?: createAuthorizationClient().also { authorizationClient = it }

    /**
     * Creates the [ITMGeolocationManager] to be used for this iTwin Mobile web app.
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * @return An instance of [ITMGeolocationManager] or `null` if your app doesn't need
     * geolocation.
     */
    open fun createGeolocationManager(): ITMGeolocationManager? = ITMGeolocationManager(appContext)

    /**
     * If the [geolocationManager] is `null`, first sets it using [createGeolocationManager].
     *
     * Override this function in a subclass in order to add custom behavior.
     *
     * @return The [geolocationManager] value.
     */
    open fun provideGeolocationManager() =
        geolocationManager ?: createGeolocationManager().also { geolocationManager = it }

    /**
     * Associates the given activity with the [geolocationManager] and [authorizationClient], and
     * adds a handler to:
     *  - call [IModelJsHost.onPause] when paused.
     *  - call [IModelJsHost.onResume] when resumed.
     *  - call [onActivityDestroy] when destroyed.
     *
     * @param activity The Activity to associate.
     */
    open fun associateWithActivity(activity: ComponentActivity) {
        provideGeolocationManager()?.associateWithActivity(activity)
        (provideAuthorizationClient() as? ITMOIDCAuthorizationClient)?.associateWithActivity(activity)
        activity.lifecycle.addObserver(object: DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                onActivityPause()
            }
            override fun onResume(owner: LifecycleOwner) {
                onActivityResume()
            }
            override fun onDestroy(owner: LifecycleOwner) {
                onActivityDestroy()
            }
        })
    }
}