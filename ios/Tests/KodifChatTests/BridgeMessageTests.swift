import XCTest
@testable import KodifChat

final class BridgeMessageTests: XCTestCase {
    private func decode(_ json: String) -> BridgeMessage.Incoming? {
        BridgeMessage.Incoming.decode(json)
    }

    private func object(_ message: BridgeMessage.Outgoing) throws -> [String: Any] {
        let json = try XCTUnwrap(message.json)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
    }

    // MARK: Incoming

    func testDecodesEveryWidgetMessage() {
        XCTAssertEqual(decode(#"{"v":1,"type":"ready"}"#), .ready)
        XCTAssertEqual(decode(#"{"v":1,"type":"requestToken","id":"abc"}"#), .requestToken(id: "abc"))
        XCTAssertEqual(decode(#"{"v":1,"type":"close"}"#), .close)
        XCTAssertEqual(
            decode(#"{"v":1,"type":"openUrl","url":"https://example.com/p?a=1"}"#),
            .openURL("https://example.com/p?a=1")
        )
        XCTAssertEqual(
            decode(#"{"v":1,"type":"message","preview":"Your order shipped","author":"ai"}"#),
            .message(preview: "Your order shipped", author: .ai)
        )
        XCTAssertEqual(
            decode(#"{"v":1,"type":"message","preview":"Hi, I'm Sam","author":"agent"}"#),
            .message(preview: "Hi, I'm Sam", author: .agent)
        )
        XCTAssertEqual(decode(#"{"v":1,"type":"openSettings"}"#), .openSettings)
        XCTAssertEqual(decode(#"{"v":1,"type":"unavailable"}"#), .unavailable)
    }

    func testIgnoresExtraFields() {
        XCTAssertEqual(decode(#"{"v":1,"type":"close","reason":"later"}"#), .close)
    }

    func testIgnoresUnknownTypesAndVersions() {
        XCTAssertNil(decode(#"{"v":1,"type":"somethingNew"}"#))
        XCTAssertNil(decode(#"{"v":2,"type":"ready"}"#))
        XCTAssertNil(decode(#"{"v":"1","type":"ready"}"#))
        XCTAssertNil(decode(#"{"type":"ready"}"#))
        XCTAssertNil(decode(#"{"v":1}"#))
    }

    func testIgnoresMalformedMessages() {
        XCTAssertNil(BridgeMessage.Incoming.decode(42))
        XCTAssertNil(BridgeMessage.Incoming.decode(["v": 1, "type": "ready"]))
        XCTAssertNil(decode(""))
        XCTAssertNil(decode("not json"))
        XCTAssertNil(decode("[1,2]"))
        XCTAssertNil(decode(#"{"v":1,"type":"requestToken"}"#))
        XCTAssertNil(decode(#"{"v":1,"type":"requestToken","id":7}"#))
        XCTAssertNil(decode(#"{"v":1,"type":"openUrl"}"#))
        XCTAssertNil(decode(#"{"v":1,"type":"message","preview":"x","author":"robot"}"#))
        XCTAssertNil(decode(#"{"v":1,"type":"message","author":"ai"}"#))
    }

    // MARK: Outgoing

    func testEncodesTokenAnswer() throws {
        XCTAssertEqual(BridgeMessage.Outgoing.token(id: "abc", jwt: "eyJ.x.y").json,
                       #"{"id":"abc","jwt":"eyJ.x.y","type":"token","v":1}"#)
    }

    func testEncodesAnonymousTokenWithExplicitNull() throws {
        XCTAssertEqual(BridgeMessage.Outgoing.token(id: "abc", jwt: nil).json, #"{"id":"abc","jwt":null,"type":"token","v":1}"#)
    }

    func testEncodesLoginAndLogoutWithoutId() throws {
        XCTAssertEqual(BridgeMessage.Outgoing.token(id: nil, jwt: "eyJ.x.y").json, #"{"jwt":"eyJ.x.y","type":"token","v":1}"#)
        XCTAssertEqual(BridgeMessage.Outgoing.token(id: nil, jwt: nil).json, #"{"jwt":null,"type":"token","v":1}"#)
    }

    func testEncodesVisibility() {
        XCTAssertEqual(BridgeMessage.Outgoing.visibility(true).json, #"{"type":"visibility","v":1,"visible":true}"#)
        XCTAssertEqual(BridgeMessage.Outgoing.visibility(false).json, #"{"type":"visibility","v":1,"visible":false}"#)
    }

    func testEncodesAppState() {
        XCTAssertEqual(BridgeMessage.Outgoing.appState(foreground: true).json, #"{"state":"foreground","type":"appState","v":1}"#)
        XCTAssertEqual(BridgeMessage.Outgoing.appState(foreground: false).json, #"{"state":"background","type":"appState","v":1}"#)
    }

    func testEncodesCustomContext() throws {
        let context: [String: Any] = [
            "screen": "product",
            "productId": 42,
            "price": 9.5,
            "vip": true,
            "tags": ["a", "b"],
            "nested": ["x": NSNull()],
            "missing": String?.none as Any,
            "page": URL(string: "https://example.com/p")!,
        ]
        XCTAssertEqual(
            BridgeMessage.Outgoing.customContext(context).json,
            #"{"context":{"missing":null,"nested":{"x":null},"page":"https://example.com/p","price":9.5,"productId":42,"#
                + #""screen":"product","tags":["a","b"],"vip":true},"type":"customContext","v":1}"#
        )
        XCTAssertEqual(BridgeMessage.Outgoing.customContext(nil).json, #"{"context":null,"type":"customContext","v":1}"#)
    }

    func testNonFiniteNumbersBecomeNull() {
        XCTAssertEqual(
            BridgeMessage.Outgoing.customContext(["a": Double.nan, "b": Double.infinity]).json,
            #"{"context":{"a":null,"b":null},"type":"customContext","v":1}"#
        )
    }

    func testEscapesScriptBreakingCharacters() throws {
        let jwt = "</script><!-- \u{2028}\u{2029}"
        let json = try XCTUnwrap(BridgeMessage.Outgoing.token(id: nil, jwt: jwt).json)
        XCTAssertFalse(json.contains("<"))
        XCTAssertFalse(json.contains("\u{2028}"))
        XCTAssertFalse(json.contains("\u{2029}"))
        XCTAssertEqual(try object(.token(id: nil, jwt: jwt))["jwt"] as? String, jwt)
    }

    func testOnlyTokenAndCustomContextWaitForReady() {
        XCTAssertTrue(BridgeMessage.Outgoing.token(id: nil, jwt: nil).isQueuedUntilReady)
        XCTAssertTrue(BridgeMessage.Outgoing.customContext(nil).isQueuedUntilReady)
        XCTAssertFalse(BridgeMessage.Outgoing.visibility(true).isQueuedUntilReady)
        XCTAssertFalse(BridgeMessage.Outgoing.appState(foreground: true).isQueuedUntilReady)
    }
}
