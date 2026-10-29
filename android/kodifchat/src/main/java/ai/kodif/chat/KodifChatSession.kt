package ai.kodif.chat

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Color
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * The one shared chat: a WebView hosting the widget page, and the bridge. The WebView lives on the application context
 * and is reparented into whichever [ChatHost] shows it. The page buffers native → widget messages until the widget
 * starts, so native sends whenever it likes. After `unavailable`, the next preload or open reloads. Main thread only.
 */
@MainThread
internal class KodifChatSession(
    private val appContext: Context,
    private val configuration: KodifChat.Configuration,
) {
    /** Test hook: every message the widget posted, after parsing. */
    internal var onIncoming: ((Bridge.Incoming) -> Unit)? = null

    internal var webView: WebView? = null
        private set
    private val contextWrapper = MutableContextWrapper(appContext)
    private var host: ChatHost? = null
    private var needsReload = false
    private var isVisible = false
    private var pageHtml = ""

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(Bridge.ASSET_HOST)
        .addPathHandler("/kodif-chat/") { path ->
            if (path != "index.html") return@addPathHandler null
            WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), pageHtml.byteInputStream())
        }
        .build()

    private val bridgeSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER).also {
            if (!it) Log.w(TAG, "This WebView is too old for the chat (no WEB_MESSAGE_LISTENER); update Android System WebView.")
        }
    }

    fun preload() {
        if (bridgeSupported) loadedWebView() else KodifChat.listener?.onUnavailable()
    }

    /** Shows the chat in [container], taking the WebView from wherever it was. */
    fun attach(host: ChatHost, container: ViewGroup) {
        if (!bridgeSupported) {
            // Dismiss the host that's showing up, once it has finished starting.
            container.post {
                host.dismiss()
                KodifChat.listener?.onUnavailable()
            }
            return
        }
        if (this.host !== host) this.host?.cancelPending()
        this.host = host
        host.activity?.let { contextWrapper.baseContext = it }
        val view = loadedWebView()
        if (view.parent === container) return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun detach(host: ChatHost) {
        if (this.host !== host) return
        isVisible = false
        host.cancelPending()
        this.host = null
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        contextWrapper.baseContext = appContext
    }

    fun setVisible(host: ChatHost, visible: Boolean) {
        if (this.host !== host) return
        isVisible = visible
        if (visible) KodifChat.unreadCount = 0
    }

    fun onAppStateChanged() {
        send(Bridge.appState(KodifChat.isForeground))
        if (KodifChat.isForeground && isVisible) KodifChat.unreadCount = 0
    }

    fun send(json: String) {
        webView?.evaluateJavascript("window.KodifChatNative && window.KodifChatNative.receive($json)", null)
    }

    fun sendCustomContext() = send(Bridge.customContext(KodifChat.customContext))

    fun reload() {
        webView?.let { load(it) }
    }

    fun destroy() {
        host?.cancelPending()
        host = null
        webView?.let { destroyWebView(it) }
        webView = null
    }

    // region Web view

    /** The WebView, created or reloaded as needed. */
    private fun loadedWebView(): WebView {
        webView?.let { view ->
            if (needsReload) load(view)
            return view
        }
        return createWebView().also { load(it) }
    }

    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature") // bridgeSupported checks WEB_MESSAGE_LISTENER
    private fun createWebView(): WebView {
        val view = WebView(contextWrapper)
        webView = view
        view.setBackgroundColor(Color.WHITE)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Links leave the page only through shouldOverrideUrlLoading. With one window, target=_blank links and
            // window.open navigate this WebView, so they're intercepted like any other link.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            setGeolocationEnabled(false)
        }
        view.webViewClient = Client()
        view.webChromeClient = ChromeClient()
        WebViewCompat.addWebMessageListener(view, "kodifChat", setOf(Bridge.ORIGIN)) { source, message, origin, isMainFrame, _ ->
            if (source === webView && isMainFrame && origin.host == Bridge.ASSET_HOST) {
                Bridge.parse(message.data)?.let { handle(it) }
            }
        }
        return view
    }

    private fun load(view: WebView) {
        needsReload = false
        pageHtml = Bridge.html(
            configuration.environment.bundleUrl,
            configuration.integrationId,
            KodifChat.voiceEnabled,
            KodifChat.language,
        )
        view.loadUrl(Bridge.PAGE_URL)
    }

    private fun destroyWebView(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    // endregion

    // region Widget → native

    private fun handle(message: Bridge.Incoming) {
        onIncoming?.invoke(message)
        val listener = KodifChat.listener
        when (message) {
            is Bridge.Incoming.RequestToken -> requestToken(message.id)
            Bridge.Incoming.Close -> {
                host?.dismiss()
                listener?.onClose()
            }
            Bridge.Incoming.Reply -> if (!(isVisible && KodifChat.isForeground)) KodifChat.unreadCount += 1
            Bridge.Incoming.OpenSettings -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null)),
            )
            Bridge.Incoming.Unavailable -> {
                // Switched off for this store, or failed to start: start over the next time the chat is shown.
                needsReload = true
                host?.dismiss()
                listener?.onUnavailable()
            }
        }
    }

    private fun requestToken(id: String) {
        val provider = KodifChat.tokenProvider ?: return send(Bridge.token(id, null))
        try {
            provider.fetchToken { jwt -> KodifChat.onMain { send(Bridge.token(id, jwt)) } }
        } catch (e: RuntimeException) {
            Log.w(TAG, "The token provider threw; continuing anonymous", e)
            send(Bridge.token(id, null))
        }
    }

    /** A link left the page: the listener's, else a Custom Tab (http/https) or the system (mailto, tel, …). */
    private fun openUrl(uri: Uri) {
        if (KodifChat.listener?.onOpenUrl(uri.toString()) == true) return
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build().intent.setData(uri)
                if (!startActivity(tab)) startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            "mailto", "tel", "sms", "smsto", "geo" -> startActivity(Intent(Intent.ACTION_VIEW, uri))
        }
    }

    private fun startActivity(intent: Intent): Boolean {
        val context: Context = host?.activity ?: appContext
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    // endregion

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame || request.url.host == Bridge.ASSET_HOST) return false
            openUrl(request.url)
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun onPageFinished(view: WebView, url: String) {
            // A fresh page: tell it the current state (it buffers until the widget starts).
            send(Bridge.appState(KodifChat.isForeground))
            if (KodifChat.customContext != null) sendCustomContext()
        }

        /** The renderer died: replace the WebView, in place if it's on screen. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val parent = view.parent as? ViewGroup
            destroyWebView(view)
            if (view === webView) {
                webView = null
                parent?.addView(loadedWebView(), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            host?.showFileChooser(callback, params) ?: false

        override fun onPermissionRequest(request: PermissionRequest) {
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            val host = host
            when {
                !KodifChat.voiceEnabled || request.origin.host != Bridge.ASSET_HOST || audio !in request.resources -> request.deny()
                ChatHost.isGranted(appContext, Manifest.permission.RECORD_AUDIO) -> request.grant(arrayOf(audio))
                host == null -> request.deny()
                else -> host.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                    if (granted) request.grant(arrayOf(audio)) else request.deny()
                }
            }
        }
    }

    private companion object {
        const val TAG = "KodifChat"
    }
}
