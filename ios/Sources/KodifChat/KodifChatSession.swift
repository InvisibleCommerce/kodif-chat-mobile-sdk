import os
import SafariServices
import UIKit
import WebKit

let kodifChatLog = Logger(subsystem: "ai.kodif.chat", category: "KodifChat")

/// The one shared chat: the web view, the bridge, and the queue of native → widget messages waiting for `ready`.
/// ``KodifChatViewController`` borrows its web view while it's on screen.
@MainActor
final class KodifChatSession: NSObject {
    static let messageHandlerName = "kodifChat"

    let configuration: KodifChat.Configuration
    private(set) var webView: WKWebView!
    /// The view controller showing the chat, if any.
    private(set) weak var hostController: KodifChatViewController?
    /// Test hook: every message the widget posted, after decoding.
    var onIncoming: ((BridgeMessage.Incoming) -> Void)?

    private var isReady = false
    private var isVisible = false
    private var isForeground = true
    private var needsReload = false
    private var isInvalidated = false
    private var queue: [BridgeMessage.Outgoing] = []
    private var pendingTokenIDs: Set<String> = []

    init(configuration: KodifChat.Configuration) {
        self.configuration = configuration
        super.init()
        webView = makeWebView()
        let center = NotificationCenter.default
        center.addObserver(
            self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil
        )
        center.addObserver(
            self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil
        )
        loadPage()
    }

    /// Tears the session down; it can't be used afterwards.
    func invalidate() {
        isInvalidated = true
        NotificationCenter.default.removeObserver(self)
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.messageHandlerName)
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.stopLoading()
        webView.removeFromSuperview()
        hostController?.dismissChat()
        hostController = nil
    }

    // MARK: Hosting

    /// Hands the web view to `controller`, which is about to show it.
    func attach(_ controller: KodifChatViewController) -> WKWebView {
        hostController = controller
        if needsReload {
            needsReload = false
            loadPage()
        }
        return webView
    }

    /// Tells the widget whether `controller`, if it's the one holding the chat, is on screen.
    func setVisible(_ visible: Bool, from controller: KodifChatViewController) {
        guard controller === hostController, visible != isVisible else { return }
        isVisible = visible
        send(.visibility(visible))
    }

    // MARK: Native → widget

    /// Sends `message` to the widget, or queues it until `ready`.
    func send(_ message: BridgeMessage.Outgoing) {
        guard !isInvalidated else { return }
        guard isReady else {
            if message.isQueuedUntilReady { queue.append(message) }
            return
        }
        guard let json = message.json else {
            kodifChatLog.error("Dropped a \(message.type, privacy: .public) message that couldn't be encoded as JSON")
            return
        }
        webView.evaluateJavaScript("window.KodifChatNative && window.KodifChatNative.receive(\(json))", completionHandler: nil)
    }

    // MARK: Page

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.userContentController.add(WeakScriptMessageHandler(self), name: Self.messageHandlerName)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsLinkPreview = false
        webView.backgroundColor = .white
        // Edge to edge: the widget pads its header and composer with env(safe-area-inset-*).
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        return webView
    }

    private func loadPage() {
        isReady = false
        queue.removeAll()
        pendingTokenIDs.removeAll()
        let options = PageBuilder.Options(
            integrationId: configuration.integrationId,
            microphone: KodifChat.voiceEnabled,
            language: KodifChat.language,
            customContext: KodifChat.customContext
        )
        let html = PageBuilder.html(bundleURL: configuration.environment.bundleURL, options: options)
        webView.loadHTMLString(html, baseURL: PageBuilder.baseURL)
    }

    // MARK: Widget → native

    fileprivate func receive(_ message: WKScriptMessage) {
        guard !isInvalidated, message.webView === webView, message.frameInfo.isMainFrame else { return }
        let origin = message.frameInfo.securityOrigin
        guard origin.protocol == "https", origin.host == PageBuilder.baseHost else { return }
        guard let incoming = BridgeMessage.Incoming.decode(message.body) else { return }
        handle(incoming)
    }

    private func handle(_ message: BridgeMessage.Incoming) {
        onIncoming?(message)
        switch message {
        case .ready:
            isReady = true
            send(.visibility(isVisible))
            send(.appState(foreground: isForeground))
            let queued = queue
            queue.removeAll()
            queued.forEach { send($0) }
        case let .requestToken(id):
            requestToken(id)
        case .close:
            hostController?.dismissChat()
            KodifChat.delegate?.kodifChatDidClose()
        case let .openURL(string):
            if let url = URL(string: string) { open(url) }
        case let .message(preview, author):
            KodifChat.delegate?.kodifChat(didReceiveMessage: preview, author: author)
        case .openSettings:
            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
        case .unavailable:
            // Start over the next time the chat is shown, in case it failed to start.
            needsReload = true
            hostController?.dismissChat()
            KodifChat.delegate?.kodifChatUnavailable()
        }
    }

    private func requestToken(_ id: String) {
        pendingTokenIDs.insert(id)
        guard let provider = KodifChat.tokenProvider else {
            answerToken(id: id, jwt: nil)
            return
        }
        // The provider may call back on any thread.
        provider { @Sendable jwt in
            Task { @MainActor in self.answerToken(id: id, jwt: jwt) }
        }
    }

    private func answerToken(id: String, jwt: String?) {
        // A page reload forgets pending ids, and a provider that calls back twice is answered once.
        guard pendingTokenIDs.remove(id) != nil else { return }
        send(.token(id: id, jwt: jwt))
    }

    // MARK: Links

    func open(_ url: URL) {
        if KodifChat.delegate?.kodifChat(openURL: url) == true { return }
        switch url.scheme?.lowercased() {
        case "http", "https":
            guard let presenter = topViewController() else {
                UIApplication.shared.open(url)
                return
            }
            presenter.present(SFSafariViewController(url: url), animated: true)
        case "mailto", "tel", "sms", "facetime", "facetime-audio":
            UIApplication.shared.open(url)
        default:
            kodifChatLog.notice("Not opening a link with an unsupported scheme")
        }
    }

    private func topViewController() -> UIViewController? {
        var top: UIViewController?
        if let host = hostController, host.viewIfLoaded?.window != nil {
            top = host
        } else {
            top = UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow)?
                .rootViewController
        }
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }

    // MARK: App state

    @objc private func didEnterBackground() {
        isForeground = false
        send(.appState(foreground: false))
    }

    @objc private func willEnterForeground() {
        isForeground = true
        send(.appState(foreground: true))
    }
}

// MARK: - WKNavigationDelegate

extension KodifChatSession: WKNavigationDelegate {
    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        // A new window (target=_blank / window.open) goes to webView(_:createWebViewWith:...); frames load freely.
        guard let frame = navigationAction.targetFrame, frame.isMainFrame else {
            decisionHandler(.allow)
            return
        }
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        if Self.isPageLoad(url: url, navigationType: navigationAction.navigationType) {
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel)
            open(url)
        }
    }

    /// The page's own load (`loadHTMLString` with the base URL), as opposed to the shopper leaving it.
    static func isPageLoad(url: URL, navigationType: WKNavigationType) -> Bool {
        if url.scheme == "about" { return true }
        guard url.scheme == "https", url.host == PageBuilder.baseHost, url.path.isEmpty || url.path == "/" else {
            return false
        }
        return navigationType != .linkActivated && navigationType != .formSubmitted
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard !isInvalidated else { return }
        kodifChatLog.notice("The web content process terminated; reloading the chat")
        loadPage()
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
        let allowed = KodifChat.voiceEnabled
            && origin.protocol == "https"
            && origin.host == PageBuilder.baseHost
            && type == .microphone
        decisionHandler(allowed ? .grant : .deny)
    }
}

// MARK: - WKScriptMessageHandler

/// Breaks the retain cycle WKUserContentController → handler → web view.
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
