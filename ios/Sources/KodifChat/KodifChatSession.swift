import SafariServices
import UIKit
import WebKit

/// The one shared chat: a web view hosting the widget page, and the bridge. ``KodifChatViewController`` borrows the
/// web view while it's on screen. The page buffers native → widget messages until the widget starts, so native sends
/// whenever it likes. After `unavailable` or a web content process crash, the next preload or presentation reloads.
@MainActor
final class KodifChatSession: NSObject {
    let configuration: KodifChat.Configuration
    private(set) var webView: WKWebView!
    private(set) weak var hostController: KodifChatViewController?
    /// Test hook: every message the widget posted, after decoding.
    var onIncoming: ((Bridge.Incoming) -> Void)?

    private var needsReload = false
    private var isVisible = false
    private var isForeground = true

    init(configuration: KodifChat.Configuration) {
        self.configuration = configuration
        super.init()

        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        // Every new-window request is routed like a link (see createWebViewWith), so window.open needn't wait for a tap.
        config.preferences.javaScriptCanOpenWindowsAutomatically = true
        config.userContentController.add(WeakScriptMessageHandler(self), name: "kodifChat")

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsLinkPreview = false
        webView.backgroundColor = .white
        // Edge to edge: the widget pads its header and composer with env(safe-area-inset-*).
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(appStateChanged), name: UIApplication.willEnterForegroundNotification, object: nil)
        reload()
    }

    func invalidate() {
        NotificationCenter.default.removeObserver(self)
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "kodifChat")
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.removeFromSuperview()
        hostController?.dismissChat()
    }

    func reload() {
        needsReload = false
        let html = Bridge.html(
            bundleURL: configuration.environment.bundleURL,
            integrationId: configuration.integrationId,
            microphone: KodifChat.voiceEnabled,
            language: KodifChat.language
        )
        webView.loadHTMLString(html, baseURL: Bridge.baseURL)
    }

    func reloadIfNeeded() {
        if needsReload { reload() }
    }

    /// Hands the web view to `controller`, which is about to show it.
    func attach(_ controller: KodifChatViewController) -> WKWebView {
        hostController = controller
        reloadIfNeeded()
        return webView
    }

    func setVisible(_ visible: Bool, from controller: KodifChatViewController) {
        guard controller === hostController else { return }
        isVisible = visible
        if visible { KodifChat.unreadCount = 0 }
    }

    func send(_ json: String) {
        webView.evaluateJavaScript("window.KodifChatNative && window.KodifChatNative.receive(\(json))", completionHandler: nil)
    }

    func sendCustomContext() {
        send(Bridge.customContext(KodifChat.customContext))
    }

    @objc private func appStateChanged(_ notification: Notification) {
        isForeground = notification.name == UIApplication.willEnterForegroundNotification
        send(Bridge.appState(foreground: isForeground))
        if isForeground && isVisible { KodifChat.unreadCount = 0 }
    }

    // MARK: Widget → native

    fileprivate func receive(_ message: WKScriptMessage) {
        let origin = message.frameInfo.securityOrigin
        guard message.webView === webView, message.frameInfo.isMainFrame,
              origin.protocol == "https", origin.host == Bridge.baseHost,
              let incoming = Bridge.decode(message.body)
        else { return }
        onIncoming?(incoming)

        switch incoming {
        case let .requestToken(id):
            guard let provider = KodifChat.tokenProvider else {
                send(Bridge.token(id: id, jwt: nil))
                return
            }
            // The provider may call back on any thread.
            provider { @Sendable [weak self] jwt in
                Task { @MainActor [weak self] in self?.send(Bridge.token(id: id, jwt: jwt)) }
            }
        case .close:
            hostController?.dismissChat()
            KodifChat.delegate?.kodifChatDidClose()
        case .reply:
            if !(isVisible && isForeground) { KodifChat.unreadCount += 1 }
        case .openSettings:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        case .unavailable:
            // Switched off for this store, or failed to start: start over the next time the chat is shown.
            needsReload = true
            hostController?.dismissChat()
            KodifChat.delegate?.kodifChatUnavailable()
        }
    }

    // MARK: Links

    /// A link left the page: the delegate's, else an in-app Safari view (http/https) or the system (mailto, tel, …).
    func open(_ url: URL) {
        if KodifChat.delegate?.kodifChat(openURL: url) == true { return }
        switch url.scheme?.lowercased() {
        case "http", "https":
            if let presenter = topViewController() {
                presenter.present(SFSafariViewController(url: url), animated: true)
            } else {
                UIApplication.shared.open(url)
            }
        case "mailto", "tel", "sms", "facetime", "facetime-audio":
            UIApplication.shared.open(url)
        default:
            break
        }
    }

    private func topViewController() -> UIViewController? {
        var top: UIViewController? = hostController?.viewIfLoaded?.window != nil
            ? hostController
            : UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}

// MARK: - WKNavigationDelegate

extension KodifChatSession: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        // New windows go to createWebViewWith; frames load freely.
        guard navigationAction.targetFrame?.isMainFrame == true, let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        let isPageLoad = url.scheme == "about"
            || (url.host == Bridge.baseHost && (url.path.isEmpty || url.path == "/")
                && navigationAction.navigationType != .linkActivated && navigationAction.navigationType != .formSubmitted)
        decisionHandler(isPageLoad ? .allow : .cancel)
        if !isPageLoad { open(url) }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        // A fresh page: tell it the current state (it buffers until the widget starts).
        send(Bridge.appState(foreground: isForeground))
        if KodifChat.customContext != nil { sendCustomContext() }
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        needsReload = true
        if isVisible { reload() }
    }
}

// MARK: - WKUIDelegate

extension KodifChatSession: WKUIDelegate {
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if let url = navigationAction.request.url, url.scheme != "about" { open(url) }
        return nil
    }

    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        let allowed = KodifChat.voiceEnabled && type == .microphone
            && origin.protocol == "https" && origin.host == Bridge.baseHost
        decisionHandler(allowed ? .grant : .deny)
    }
}

/// Breaks the retain cycle WKUserContentController → handler → session.
@MainActor
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var session: KodifChatSession?

    init(_ session: KodifChatSession) {
        self.session = session
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        session?.receive(message)
    }
}
