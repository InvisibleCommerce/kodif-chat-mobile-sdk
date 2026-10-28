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
import java.io.ByteArrayInputStream

/**
 * The one shared chat: the WebView, the bridge, and the queue of native → widget messages waiting for `ready`.
 * The WebView lives on the application context and is reparented into whichever [ChatHost] shows it. Main thread only.
 */
@MainThread
internal class KodifChatSession(
    private val appContext: Context,
    private val configuration: KodifChat.Configuration,
) {
    /** Test hook: every message the widget posted, after parsing. */
    internal var onIncoming: ((BridgeMessage.Incoming) -> Unit)? = null

    internal var webView: WebView? = null
        private set
    private val contextWrapper = MutableContextWrapper(appContext)
    private var host: ChatHost? = null
    private var isReady = false
    private var isVisible = false
    private var needsReload = false
    private var pageHtml = ""
    private val queue = ArrayDeque<BridgeMessage.Outgoing>()
    private val pendingTokenIds = mutableSetOf<String>()
    private var isDestroyed = false

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(ASSET_HOST)
        .addPathHandler(PAGE_PATH_PREFIX) { path ->
            if (path == PAGE_FILE) {
                WebResourceResponse(
                    "text/html",
                    "utf-8",
                    200,
                    "OK",
                    mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(pageHtml.toByteArray(Charsets.UTF_8)),
                )
            } else {
                null
            }
        }
        .build()

    private val bridgeSupported: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER).also { supported ->
            if (!supported) {
                Log.w(TAG, "This WebView is too old for the chat (no WEB_MESSAGE_LISTENER); update Android System WebView.")
            }
        }
    }

    fun preload() {
        if (!bridgeSupported) {
            KodifChat.post { handle(BridgeMessage.Incoming.Unavailable) }
            return
        }
        if (webView == null) createWebView()
    }

    /** Shows the chat in [container], taking the WebView from wherever it was. */
    fun attach(host: ChatHost, container: ViewGroup) {
        if (!bridgeSupported) {
            // Dismiss the host that's showing up, once it has finished starting.
            KodifChat.post { handle(BridgeMessage.Incoming.Unavailable) }
            return
        }
        if (this.host !== host) this.host?.cancelPending()
        this.host = host
        host.activity?.let { contextWrapper.baseContext = it }
        if (needsReload) {
            needsReload = false
            webView?.let { loadPage(it) }
        }
        val view = webView ?: createWebView()
        if (view.parent === container) return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun detach(host: ChatHost) {
        if (this.host !== host) return
        setVisible(host, false)
        host.cancelPending()
        this.host = null
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        contextWrapper.baseContext = appContext
    }

    /** Tells the widget whether [host], if it's the one holding the chat, is on screen. */
    fun setVisible(host: ChatHost, visible: Boolean) {
        if (this.host !== host) return
        if (isVisible == visible) return
        isVisible = visible
        send(BridgeMessage.Outgoing.Visibility(visible))
    }

    /**
     * Sends [message] to the widget, or queues it until `ready`. Visibility and app state aren't queued: the
     * current state is sent on every `ready`.
     */
    fun send(message: BridgeMessage.Outgoing) {
        val view = webView
        if (isReady && view != null) {
            view.evaluateJavascript("window.KodifChatNative && window.KodifChatNative.receive(${message.toJson()})", null)
        } else if (message is BridgeMessage.Outgoing.Token || message is BridgeMessage.Outgoing.CustomContext) {
            queue.addLast(message)
        }
    }

    fun destroy() {
        isDestroyed = true
        host?.cancelPending()
        host = null
        webView?.let { destroyWebView(it) }
        webView = null
    }

    // region Web view

    @SuppressLint("SetJavaScriptEnabled")
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
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
        }
        view.webViewClient = Client()
        view.webChromeClient = ChromeClient()
        // Callers check bridgeSupported first; lint wants the feature check next to the call.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, BRIDGE_NAME, setOf(ORIGIN), bridgeListener)
            loadPage(view)
        }
        return view
    }

    /** Widget → native. Only the main frame of our own page is heard. */
    private val bridgeListener = WebViewCompat.WebMessageListener { source, message, origin, isMainFrame, _ ->
        if (source === webView && isMainFrame && origin.scheme == "https" && origin.host == ASSET_HOST) {
            BridgeMessage.Incoming.parse(message.data)?.let { handle(it) }
        }
    }

    private fun loadPage(view: WebView) {
        isReady = false
        queue.clear()
        pendingTokenIds.clear()
        pageHtml = PageBuilder.html(
            configuration.environment.bundleUrl,
            PageBuilder.Options(
                integrationId = configuration.integrationId,
                microphone = KodifChat.voiceEnabled,
                language = KodifChat.language,
                customContext = KodifChat.customContext,
            ),
        )
        view.loadUrl(PAGE_URL)
    }

    private fun destroyWebView(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
    }

    /** The renderer died (crash or memory pressure): replace the web view and start over. */
    private fun recreateWebView() {
        val old = webView ?: return
        val parent = old.parent as? ViewGroup
        destroyWebView(old)
        webView = null
        val view = createWebView()
        parent?.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // endregion

    // region Incoming messages

    private fun handle(message: BridgeMessage.Incoming) {
        if (isDestroyed) return
        onIncoming?.invoke(message)
        val listener = KodifChat.listener
        when (message) {
            BridgeMessage.Incoming.Ready -> onReady()
            is BridgeMessage.Incoming.RequestToken -> requestToken(message.id)
            BridgeMessage.Incoming.Close -> {
                host?.dismiss()
                listener?.onClose()
            }
            is BridgeMessage.Incoming.OpenUrl -> openUrl(message.url)
            is BridgeMessage.Incoming.Message -> listener?.onMessage(message.preview, message.author)
            BridgeMessage.Incoming.OpenSettings -> openSettings()
            BridgeMessage.Incoming.Unavailable -> {
                // Start over the next time the chat is shown, in case it failed to start.
                needsReload = true
                host?.dismiss()
                listener?.onUnavailable()
            }
        }
    }

    private fun onReady() {
        isReady = true
        send(BridgeMessage.Outgoing.Visibility(isVisible))
        send(BridgeMessage.Outgoing.AppState(KodifChat.isForeground))
        while (queue.isNotEmpty()) send(queue.removeFirst())
    }

    private fun requestToken(id: String) {
        pendingTokenIds += id
        val provider = KodifChat.tokenProvider
        if (provider == null) {
            answerToken(id, null)
            return
        }
        try {
            provider.fetchToken { jwt -> KodifChat.onMain { answerToken(id, jwt) } }
        } catch (e: RuntimeException) {
            Log.w(TAG, "The token provider threw; continuing anonymous", e)
            answerToken(id, null)
        }
    }

    private fun answerToken(id: String, jwt: String?) {
        // A page reload clears the pending ids, and a provider that calls back twice is answered once.
        if (isDestroyed || !pendingTokenIds.remove(id)) return
        send(BridgeMessage.Outgoing.Token(id, jwt))
    }

    private fun openUrl(url: String) {
        if (KodifChat.listener?.onOpenUrl(url) == true) return
        val uri = Uri.parse(url)
        val context: Context = host?.activity ?: appContext
        val flags = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
        when (uri.scheme?.lowercase()) {
            "http", "https" -> try {
                val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                tab.intent.addFlags(flags)
                tab.launchUrl(context, uri)
            } catch (_: ActivityNotFoundException) {
                startView(context, uri, flags)
            }
            in EXTERNAL_SCHEMES -> startView(context, uri, flags)
            else -> Log.w(TAG, "Not opening a link with an unsupported scheme: ${uri.scheme}")
        }
    }

    private fun startView(context: Context, uri: Uri, flags: Int) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(flags))
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No app can open ${uri.scheme} links")
        }
    }

    private fun openSettings() {
        val context: Context = host?.activity ?: appContext
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "No settings screen for this app")
        }
    }

    // endregion

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            if (url.scheme == "https" && url.host == ASSET_HOST) return false
            openUrl(url.toString())
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            assetLoader.shouldInterceptRequest(request.url)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView && !isDestroyed) {
                KodifChat.onMain { recreateWebView() }
            } else {
                destroyWebView(view)
            }
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            val host = host ?: return false
            return host.showFileChooser(filePathCallback, fileChooserParams)
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
            val origin = request.origin
            val allowed = KodifChat.voiceEnabled &&
                origin.scheme == "https" && origin.host == ASSET_HOST &&
                audio in request.resources
            if (!allowed) {
                request.deny()
                return
            }
            val host = host
            when {
                ChatHost.isGranted(appContext, Manifest.permission.RECORD_AUDIO) -> request.grant(arrayOf(audio))
                host == null -> request.deny()
                else -> host.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
                    if (granted) request.grant(arrayOf(audio)) else request.deny()
                }
            }
        }
    }

    internal companion object {
        const val TAG = "KodifChat"
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$ASSET_HOST"
        const val PAGE_PATH_PREFIX = "/kodif-chat/"
        const val PAGE_FILE = "index.html"
        const val PAGE_URL = "$ORIGIN$PAGE_PATH_PREFIX$PAGE_FILE"
        const val BRIDGE_NAME = "kodifChat"
        val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "smsto", "geo")
    }
}
