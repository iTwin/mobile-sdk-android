package org.itwinjs.mobilesdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.webkit.*
import androidx.lifecycle.MutableLiveData
import com.bentley.itwin.AuthorizationClient
import com.bentley.itwin.IModelJsHost
import com.bentley.itwin.MobileFrontend
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

enum class ReachabilityStatus {
    NotReachable,
    ReachableViaWiFi,
    ReachableViaWWAN,
}

abstract class ITMApplication(val appContext: Context, private val attachConsoleLogger: Boolean = false, private val forceExtractBackendAssets: Boolean = false) {
    protected var host: IModelJsHost? = null
    private var backendInitTask = Job()
    private val _isBackendInitialized = AtomicBoolean(false)
    val isBackendInitialized: Boolean get() = _isBackendInitialized.get()

    var webView: MobileFrontend? = null
    var mobileUi: ITMNativeUI? = null
    val isLoaded = MutableLiveData(false)
    var frontendBaseUrl = ""
    var messenger: ITMMessenger? = null
    var coMessenger: ITMCoMessenger? = null
    var logger = ITMLogger()
    private var consoleLogger: ITMConsoleLogger? = null
    private var reachabilityStatus = ReachabilityStatus.NotReachable

    /**
     * Initialize the iModelJs backend if not initialized yet. This can be called from the launch activity.
     */
    open fun initializeBackend() {
        if (_isBackendInitialized.getAndSet(true))
            return

        try {
            host = IModelJsHost(appContext, forceExtractBackendAssets, getAuthorizationClient()).apply {
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
     * Initialize the iModelJs frontend if not initialized yet.
     * This requires the Looper to be running, so cannot be called from the launch activity. If you have not already
     * called [initializeBackend], this will call it.
     * Note: Your application must contain the ACCESS_NETWORK_STATE permission.
     */
    @SuppressLint("MissingPermission")
    open fun initializeFrontend() {
        initializeBackend()
        MainScope().launch {
            if (webView != null)
                return@launch

            try {
                backendInitTask.join()
                val args = getUrlHashParams()
                val baseUrl = getBaseUrl()
                val mobileFrontend = object : MobileFrontend(host, args) {
                    override fun supplyEntryPoint(): String {
                        return baseUrl
                    }

                    override fun onConfigurationChanged(newConfig: Configuration?) {
                        super.onConfigurationChanged(newConfig)
                        newConfig?.let {
                            this@ITMApplication.mobileUi?.onConfigurationChanged(newConfig)
                        }
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
            } catch (e: Exception) {
                coMessenger?.frontendLaunchFailed(e)
                reset()
                logger.log(ITMLogger.Severity.Error, "Error loading imodeljs frontend: $e")
            }
        }
    }

    @SuppressLint("MissingPermission")
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
        _isBackendInitialized.set(false)
        webView = null
        messenger = null
        coMessenger = null
    }

    protected open fun setupWebView() {
        val webView = this.webView ?: return
        messenger = ITMMessenger(this)
        if (attachConsoleLogger) {
            consoleLogger = ITMConsoleLogger(webView, ::onConsoleLog)
        }
        coMessenger = ITMCoMessenger(messenger!!)
        webView.settings.setSupportZoom(false)
        webView.webViewClient = object : WebViewClient() {
            fun shouldIgnoreUrl(url: String?): Boolean {
                return url != null && url.startsWith("file:///android_asset/frontend")
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (shouldIgnoreUrl(url)) {
                    return
                }
                this@ITMApplication.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (shouldIgnoreUrl(url)) {
                    return
                }
                isLoaded.value = true
                this@ITMApplication.onPageFinished(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val result = this@ITMApplication.shouldInterceptRequest(view, request)
                return result ?: super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (this@ITMApplication.shouldOverrideUrlLoading(view, request)) {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                return super.onRenderProcessGone(view, detail)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (!this@ITMApplication.onReceivedError(view, request, error)) {
                    super.onReceivedError(view, request, error)
                }
            }
        }
    }

    open fun onPageFinished(view: WebView?, url: String?) {
        consoleLogger?.inject()
    }

    open fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        updateAvailability()
    }

    open fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        return null
    }

    open fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false
    }

    open fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?): Boolean {
        return false
    }

    open fun onConsoleLog(type: ITMConsoleLogger.LogType, message: String) {}

    abstract fun openUri(uri: Uri)

    open fun getBackendHomePath(): String {
        return "ITMApplication/home"
    }

    open fun getBackendPath(): String {
        return "ITMApplication/backend"
    }

    open fun getBackendEntryPointScript(): String {
        return "main.js"
    }

    open fun getBaseUrl(): String {
        return "file:///android_asset/ITMApplication/frontend/index.html"
    }

    open suspend fun getUrlHashParams(): String {
        return ""
    }

    abstract fun getAuthorizationClient(): AuthorizationClient

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