import WebKit
import XCTest
@testable import KodifChat

/// Loads the real page (`loadHTMLString` on the https base URL, a module script) with stub widget bundles and checks
/// the bridge both ways, link interception, unread counting and a bundle that fails to load.
final class WebViewIntegrationTests: XCTestCase {
    /// Plays the widget: takes over KodifChatNative, replays what the page buffered, asks for a token, posts a reply.
    private let stubWidget = """
    window.ShippedAI = { ChatWidget: function (options) {
      window.__options = options;
      window.__received = window.KodifChatNative.pending.slice();
      window.KodifChatNative = { receive: function (m) { window.__received.push(m); } };
      var post = function (m) { window.webkit.messageHandlers.kodifChat.postMessage(JSON.stringify(m)); };
      window.__click = function (href, target) {
        var a = document.createElement('a');
        a.href = href;
        if (target) a.target = target;
        document.body.appendChild(a);
        a.click();
      };
      post({ v: 1, type: 'requestToken', id: '8d5e2c1a' });
      post({ v: 1, type: 'reply' });
    } };
    window.dispatchEvent(new Event('shipped:chat-script-loaded'));
    """

    /// KodifChat's settings and unread count are static: start every test from a clean slate.
    @MainActor
    private func resetChat() {
        KodifChat.delegate = nil
        KodifChat.tokenProvider = nil
        KodifChat.customContext = nil
        KodifChat.unreadCount = 0
    }

    @MainActor
    private func makeSession(bundle: String) throws -> KodifChatSession {
        let url = try XCTUnwrap(URL(string: "data:text/javascript;base64," + Data(bundle.utf8).base64EncodedString()))
        return KodifChatSession(configuration: .init(integrationId: "test-integration", environment: .custom(bundleURL: url)))
    }

    @MainActor
    func testTokenRoundTripStateAndUnreadCount() async throws {
        resetChat()
        defer { resetChat() }
        KodifChat.tokenProvider = { completion in DispatchQueue.global().async { completion("test-jwt") } }
        KodifChat.customContext = ["screen": "cart"]
        let recorder = Recorder()
        KodifChat.delegate = recorder

        let requestToken = expectation(description: "requestToken")
        let reply = expectation(description: "reply")
        let session = try makeSession(bundle: stubWidget)
        defer { session.invalidate() }
        session.onIncoming = { message in
            if message == .requestToken(id: "8d5e2c1a") { requestToken.fulfill() }
            if message == .reply { reply.fulfill() }
        }
        await fulfillment(of: [requestToken, reply], timeout: 30)

        // Not on screen, so the reply counts as unread.
        XCTAssertEqual(KodifChat.unreadCount, 1)
        XCTAssertEqual(recorder.unreadCounts, [1])

        let received = try await poll(session.webView) { messages in
            messages.contains { $0["type"] as? String == "token" } && messages.contains { $0["type"] as? String == "customContext" }
        }
        let token = try XCTUnwrap(received.first { $0["type"] as? String == "token" })
        XCTAssertEqual(token["id"] as? String, "8d5e2c1a")
        XCTAssertEqual(token["jwt"] as? String, "test-jwt")
        XCTAssertTrue(received.contains { $0["type"] as? String == "appState" && $0["state"] as? String == "foreground" })
        let context = try XCTUnwrap(received.first { $0["type"] as? String == "customContext" })
        XCTAssertEqual((context["context"] as? [String: Any])?["screen"] as? String, "cart")

        let isSecureContext = try await evaluate(session.webView, "window.isSecureContext")
        XCTAssertEqual(isSecureContext as? Bool, true, "voice mode needs a secure context")
        let origin = try await evaluate(session.webView, "location.origin")
        XCTAssertEqual(origin as? String, "https://mobile-chat.kodif.ai")
        let host = try await evaluate(session.webView, "window.__options.host")
        XCTAssertEqual(host as? String, "ios")
    }

    /// Links leave the page only through navigation interception: a plain link (decidePolicyFor) and a
    /// target=_blank one (createWebViewWith).
    @MainActor
    func testLinksReachTheOpenURLPath() async throws {
        resetChat()
        defer { resetChat() }
        let recorder = Recorder()
        KodifChat.delegate = recorder
        let started = expectation(description: "widget started")
        let session = try makeSession(bundle: stubWidget)
        defer { session.invalidate() }
        session.onIncoming = { if case .requestToken = $0 { started.fulfill() } }
        await fulfillment(of: [started], timeout: 30)

        for (script, expected) in [
            ("__click('https://example.com/plain'); true", "https://example.com/plain"),
            ("__click('https://example.com/new-window', '_blank'); true", "https://example.com/new-window"),
        ] {
            let opened = expectation(description: expected)
            recorder.onOpen = { url in
                XCTAssertEqual(url.absoluteString, expected)
                opened.fulfill()
            }
            _ = try await evaluate(session.webView, script)
            await fulfillment(of: [opened], timeout: 10)
        }
        let origin = try await evaluate(session.webView, "location.origin")
        XCTAssertEqual(origin as? String, "https://mobile-chat.kodif.ai")
    }

    @MainActor
    func testABundleThatFailsToLoadIsReportedAndReloaded() async throws {
        resetChat()
        defer { resetChat() }
        // Nothing listens on port 1, so the connection is refused at once (like a device that's offline) and the
        // module script's onerror reports it. Loopback, so it doesn't depend on the runner's DNS.
        let recorder = Recorder()
        KodifChat.delegate = recorder
        let bundleURL = try XCTUnwrap(URL(string: "https://127.0.0.1:1/chat-widget.js"))

        let first = expectation(description: "first load reported unavailable")
        recorder.onUnavailable = { first.fulfill() }
        let session = KodifChatSession(configuration: .init(integrationId: "i", environment: .custom(bundleURL: bundleURL)))
        defer { session.invalidate() }
        let result = await XCTWaiter().fulfillment(of: [first], timeout: 30)
        if result != .completed {
            XCTFail("the failed load wasn't reported: \(await diagnoseBundleError(session))")
            return
        }

        let second = expectation(description: "reload reported unavailable")
        recorder.onUnavailable = { second.fulfill() }
        session.reloadIfNeeded()
        await fulfillment(of: [second], timeout: 30)
    }

    // MARK: Helpers

    /// What WebKit does with a module script that fails to load: which error events fire, and whether the page's
    /// handler can reach native when called directly.
    @MainActor
    private func diagnoseBundleError(_ session: KodifChatSession) async -> String {
        let probe = """
        window.__probe = [];
        window.addEventListener('error', function (e) { __probe.push('window-capture:' + (e.target && e.target.tagName)); }, true);
        ['module', 'text/javascript'].forEach(function (type) {
          var s = document.createElement('script');
          s.type = type;
          s.onerror = function () { __probe.push(type + ':onerror'); };
          s.onload = function () { __probe.push(type + ':onload'); };
          s.src = 'https://127.0.0.1:1/probe-' + type.replace('/', '-') + '.js';
          document.head.appendChild(s);
        });
        true
        """
        _ = try? await evaluate(session.webView, probe)
        try? await Task.sleep(nanoseconds: 10_000_000_000)
        let probed = try? await evaluate(session.webView, "JSON.stringify(window.__probe)")
        let attribute = try? await evaluate(session.webView, "document.querySelector('script[type=module]').getAttribute('onerror')")
        var direct = "no"
        let reported = expectation(description: "direct handler")
        (KodifChat.delegate as? Recorder)?.onUnavailable = {
            direct = "yes"
            reported.fulfill()
        }
        _ = try? await evaluate(session.webView, Bridge.bundleErrorHandler + "; true")
        _ = await XCTWaiter().fulfillment(of: [reported], timeout: 5)
        let readyState = try? await evaluate(session.webView, "document.readyState")
        return "readyState=\(String(describing: readyState)) "
            + "probe=\(String(describing: probed)) onerror attribute=\(String(describing: attribute)) "
            + "handler reaches native when called directly: \(direct)"
    }

    @MainActor
    private func evaluate(_ webView: WKWebView, _ script: String) async throws -> Any? {
        try await withCheckedThrowingContinuation { continuation in
            webView.evaluateJavaScript(script) { result, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: result)
                }
            }
        }
    }

    @MainActor
    private func poll(_ webView: WKWebView, until done: ([[String: Any]]) -> Bool) async throws -> [[String: Any]] {
        let deadline = Date().addingTimeInterval(15)
        var messages: [[String: Any]] = []
        while Date() < deadline {
            let json = try await evaluate(webView, "JSON.stringify(window.__received || [])") as? String ?? "[]"
            messages = (try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]) ?? []
            if done(messages) { return messages }
            try await Task.sleep(nanoseconds: 200_000_000)
        }
        return messages
    }
}

@MainActor
private final class Recorder: KodifChatDelegate {
    var onOpen: ((URL) -> Void)?
    var onUnavailable: (() -> Void)?
    var unreadCounts: [Int] = []

    func kodifChat(openURL url: URL) -> Bool {
        onOpen?(url)
        return true
    }

    func kodifChat(unreadCountDidChange count: Int) {
        unreadCounts.append(count)
    }

    func kodifChatUnavailable() {
        onUnavailable?()
    }
}
