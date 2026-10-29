import XCTest
@testable import KodifChat

final class BridgeTests: XCTestCase {
    private func object(_ json: String) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
    }

    // MARK: Widget → native

    func testDecodesEveryWidgetMessage() {
        XCTAssertEqual(Bridge.decode(#"{"v":1,"type":"requestToken","id":"5f0c"}"#), .requestToken(id: "5f0c"))
        XCTAssertEqual(Bridge.decode(#"{"v":1,"type":"close"}"#), .close)
        XCTAssertEqual(Bridge.decode(#"{"v":1,"type":"reply"}"#), .reply)
        XCTAssertEqual(Bridge.decode(#"{"v":1,"type":"openSettings"}"#), .openSettings)
        XCTAssertEqual(Bridge.decode(#"{"v":1,"type":"unavailable","extra":true}"#), .unavailable)
    }

    func testIgnoresUnknownAndMalformedMessages() {
        for json in [
            #"{"v":1,"type":"somethingNew"}"#, #"{"v":1,"type":"ready"}"#, #"{"v":1,"type":"openUrl","url":"x"}"#,
            #"{"v":2,"type":"close"}"#, #"{"v":"1","type":"close"}"#, #"{"type":"close"}"#,
            #"{"v":1,"type":"requestToken"}"#, #"{"v":1,"type":"requestToken","id":7}"#,
            "", "not json", "[1,2]",
        ] {
            XCTAssertNil(Bridge.decode(json), json)
        }
        XCTAssertNil(Bridge.decode(42))
    }

    // MARK: Native → widget

    func testEncodesNativeMessages() {
        XCTAssertEqual(Bridge.token(id: "5f0c", jwt: "eyJ.x.y"), #"{"id":"5f0c","jwt":"eyJ.x.y","type":"token","v":1}"#)
        XCTAssertEqual(Bridge.token(id: "5f0c", jwt: nil), #"{"id":"5f0c","jwt":null,"type":"token","v":1}"#)
        XCTAssertEqual(Bridge.appState(foreground: true), #"{"state":"foreground","type":"appState","v":1}"#)
        XCTAssertEqual(Bridge.appState(foreground: false), #"{"state":"background","type":"appState","v":1}"#)
        XCTAssertEqual(Bridge.customContext(nil), #"{"context":null,"type":"customContext","v":1}"#)
    }

    func testEncodesCustomContextDroppingKeysThatArentJSON() throws {
        let context: [String: Any] = [
            "screen": "product", "productId": 42, "vip": true, "tags": ["a", "b"], "nested": ["x": NSNull()],
            "notANumber": Double.nan, "object": Date(),
        ]
        let sent = try XCTUnwrap(try object(Bridge.customContext(context))["context"] as? [String: Any])
        XCTAssertEqual(Set(sent.keys), ["screen", "productId", "vip", "tags", "nested"])
        XCTAssertEqual(sent["productId"] as? Int, 42)
        XCTAssertEqual(sent["tags"] as? [String], ["a", "b"])
    }

    // MARK: The page

    private func options(in html: String) throws -> [String: Any] {
        let start = try XCTUnwrap(html.range(of: "new window.ShippedAI.ChatWidget(")).upperBound
        let end = try XCTUnwrap(html.range(of: ")\n", range: start..<html.endIndex)).lowerBound
        return try object(String(html[start..<end]))
    }

    /// The bundle URL the page's script loads (a JSON string).
    private func bundleSource(in html: String) throws -> String? {
        let start = try XCTUnwrap(html.range(of: "bundle.src = ")).upperBound
        let end = try XCTUnwrap(html.range(of: ";\n", range: start..<html.endIndex)).lowerBound
        return try JSONSerialization.jsonObject(with: Data(html[start..<end].utf8), options: .fragmentsAllowed) as? String
    }

    func testBuildsThePage() throws {
        let html = Bridge.html(
            bundleURL: KodifChat.Environment.production.bundleURL, integrationId: "int_123", microphone: true, language: "fr"
        )
        // The buffer comes first, so native can send before the widget starts.
        let firstScript = try XCTUnwrap(html.range(of: "<script>\n")).upperBound
        XCTAssertTrue(html[firstScript...].trimmingCharacters(in: .whitespaces).hasPrefix(
            "window.KodifChatNative = { pending: [], receive: function (m) { this.pending.push(m) } };"
        ))
        XCTAssertEqual(try bundleSource(in: html), "https://js.shippedsuite.com/api/chat-widget.js")
        XCTAssertTrue(html.contains("bundle.type = 'module';"))
        XCTAssertTrue(html.contains("document.head.appendChild(bundle);"))
        XCTAssertTrue(html.contains("viewport-fit=cover"))

        let options = try options(in: html)
        XCTAssertEqual(options["integrationId"] as? String, "int_123")
        XCTAssertEqual(options["host"] as? String, "ios")
        XCTAssertEqual((options["hostCapabilities"] as? [String: Any])?["microphone"] as? Bool, true)
        XCTAssertEqual(options["language"] as? String, "fr")
        XCTAssertNil(options["customContext"])
    }

    func testReportsABundleThatFailsToLoad() throws {
        let html = Bridge.html(bundleURL: URL(string: "https://x/b.js?a=1&b=2")!, integrationId: "i", microphone: false, language: nil)
        XCTAssertEqual(try bundleSource(in: html), "https://x/b.js?a=1&b=2")
        let onerror = try XCTUnwrap(html.range(of: "bundle.onerror = function () {"))
        let handler = html[onerror.upperBound...]
        XCTAssertTrue(handler.contains("var m = JSON.stringify({ v: 1, type: 'unavailable' });"))
        XCTAssertTrue(handler.contains("window.webkit.messageHandlers.kodifChat"))
        XCTAssertTrue(handler.contains("window.kodifChat.postMessage(m)"))
        // Set before the script is inserted, so a failure can't be missed.
        XCTAssertLessThan(onerror.lowerBound, try XCTUnwrap(html.range(of: "document.head.appendChild(bundle)")).lowerBound)
    }

    func testValuesCannotCloseTheScriptTag() throws {
        let html = Bridge.html(
            bundleURL: URL(string: "https://x/b.js#</script>")!, integrationId: "</script><!--", microphone: false, language: nil
        )
        XCTAssertEqual(html.lowercased().components(separatedBy: "</script").count - 1, 1)
        XCTAssertFalse(html.contains("<!--"))
        XCTAssertEqual(try options(in: html)["integrationId"] as? String, "</script><!--")
    }

    func testEnvironmentBundles() {
        XCTAssertEqual(KodifChat.Environment.staging.bundleURL.absoluteString, "https://js-staging.shippedsuite.com/api/chat-widget.js")
        XCTAssertEqual(
            KodifChat.Environment.development.bundleURL.absoluteString, "https://js.dev.invisiblecommerce.com/api/chat-widget.js"
        )
        let custom = URL(string: "https://example.test/stub.js")!
        XCTAssertEqual(KodifChat.Environment.custom(bundleURL: custom).bundleURL, custom)
    }
}
