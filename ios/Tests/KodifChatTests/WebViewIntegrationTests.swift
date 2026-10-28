import WebKit
import XCTest
@testable import KodifChat

/// Loads the real page (`loadHTMLString` with the https base URL, the generated HTML, a module script) with a stub
/// widget bundle, and checks the bridge both ways: `ready` and `requestToken` reach native, and native's `token`
/// answer reaches the widget. Also checks the page is a secure context, which voice mode's microphone needs.
final class WebViewIntegrationTests: XCTestCase {
    /// Plays the widget: records what native sends and posts what mobile mode posts.
    private let stubBundle = """
    window.__received = [];
    window.ShippedAI = {
      ChatWidget: function (options) {
        window.__options = options;
        var post = function (m) { window.webkit.messageHandlers.kodifChat.postMessage(JSON.stringify(m)); };
        window.KodifChatNative = { receive: function (m) { window.__received.push(m); } };
        post({ v: 1, type: 'ready' });
        post({ v: 1, type: 'requestToken', id: 't1' });
      }
    };
    window.dispatchEvent(new Event('shipped:chat-script-loaded'));
    """

    @MainActor
    func testReadyAndTokenRoundTrip() async throws {
        let bundleURL = try XCTUnwrap(URL(string: "data:text/javascript;base64," + Data(stubBundle.utf8).base64EncodedString()))
        KodifChat.tokenProvider = { completion in
            DispatchQueue.global().async { completion("test-jwt") }
        }
        defer { KodifChat.tokenProvider = nil }

        let ready = expectation(description: "ready")
        let requestToken = expectation(description: "requestToken")
        var incoming: [BridgeMessage.Incoming] = []
        let session = KodifChatSession(
            configuration: .init(integrationId: "test-integration", environment: .custom(bundleURL: bundleURL))
        )
        defer { session.invalidate() }
        session.onIncoming = { message in
            incoming.append(message)
            switch message {
            case .ready: ready.fulfill()
            case .requestToken: requestToken.fulfill()
            default: break
            }
        }

        await fulfillment(of: [ready, requestToken], timeout: 30, enforceOrder: true)
        XCTAssertEqual(incoming, [.ready, .requestToken(id: "t1")])

        let received = try await poll(session.webView) { messages in
            messages.contains { $0["type"] as? String == "token" }
        }
        let token = try XCTUnwrap(received.first { $0["type"] as? String == "token" })
        XCTAssertEqual(token["id"] as? String, "t1")
        XCTAssertEqual(token["jwt"] as? String, "test-jwt")
        XCTAssertEqual(token["v"] as? Int, 1)
        // On ready, native sends the current state first.
        XCTAssertTrue(received.contains { $0["type"] as? String == "visibility" && $0["visible"] as? Bool == false })
        XCTAssertTrue(received.contains { $0["type"] as? String == "appState" })

        let isSecureContext = try await evaluate(session.webView, "window.isSecureContext")
        XCTAssertEqual(isSecureContext as? Bool, true, "voice mode needs a secure context")
        let origin = try await evaluate(session.webView, "location.origin")
        XCTAssertEqual(origin as? String, "https://mobile-chat.kodif.ai")
        let host = try await evaluate(session.webView, "window.__options.host")
        XCTAssertEqual(host as? String, "ios")
        let integrationId = try await evaluate(session.webView, "window.__options.integrationId")
        XCTAssertEqual(integrationId as? String, "test-integration")
    }

    @MainActor
    func testMessagesFromOtherVersionsAndTypesAreIgnored() async throws {
        let stub = """
        window.ShippedAI = { ChatWidget: function () {
          var post = function (m) { window.webkit.messageHandlers.kodifChat.postMessage(JSON.stringify(m)); };
          window.KodifChatNative = { receive: function () {} };
          post({ v: 2, type: 'close' });
          post({ v: 1, type: 'somethingNew' });
          post({ v: 1, type: 'ready' });
        } };
        window.dispatchEvent(new Event('shipped:chat-script-loaded'));
        """
        let bundleURL = try XCTUnwrap(URL(string: "data:text/javascript;base64," + Data(stub.utf8).base64EncodedString()))
        let ready = expectation(description: "ready")
        var incoming: [BridgeMessage.Incoming] = []
        let session = KodifChatSession(configuration: .init(integrationId: "i", environment: .custom(bundleURL: bundleURL)))
        defer { session.invalidate() }
        session.onIncoming = { message in
            incoming.append(message)
            if message == .ready { ready.fulfill() }
        }
        await fulfillment(of: [ready], timeout: 30)
        XCTAssertEqual(incoming, [.ready])
    }

    // MARK: Helpers

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
    private func poll(
        _ webView: WKWebView,
        until done: ([[String: Any]]) -> Bool
    ) async throws -> [[String: Any]] {
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
