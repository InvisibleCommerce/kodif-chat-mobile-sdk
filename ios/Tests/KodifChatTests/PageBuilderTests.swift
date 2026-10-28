import XCTest
@testable import KodifChat

final class PageBuilderTests: XCTestCase {
    private let bundleURL = URL(string: "https://example.test/chat-widget.js")!

    private func options(in html: String) throws -> [String: Any] {
        let marker = "new window.ShippedAI.ChatWidget("
        let start = try XCTUnwrap(html.range(of: marker)).upperBound
        let end = try XCTUnwrap(html.range(of: ")\n", range: start..<html.endIndex)).lowerBound
        let json = String(html[start..<end])
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
    }

    func testBuildsTheProtocolPage() {
        let html = PageBuilder.html(
            bundleURL: KodifChat.Environment.production.bundleURL,
            options: .init(integrationId: "int_123", microphone: false)
        )
        XCTAssertTrue(html.hasPrefix("<!doctype html>\n<html>\n<head>\n  <meta charset=\"utf-8\">"))
        XCTAssertTrue(html.contains(
            #"<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">"#
        ))
        XCTAssertTrue(html.contains("window.addEventListener('shipped:chat-script-loaded', function () {"))
        XCTAssertTrue(html.contains(#"<script type="module" src="https://js.shippedsuite.com/api/chat-widget.js"></script>"#))
        XCTAssertTrue(html.contains("<body></body>"))
    }

    func testEncodesRequiredOptions() throws {
        let html = PageBuilder.html(bundleURL: bundleURL, options: .init(integrationId: "int_123", microphone: true))
        let options = try options(in: html)
        XCTAssertEqual(options["integrationId"] as? String, "int_123")
        XCTAssertEqual(options["host"] as? String, "ios")
        XCTAssertEqual((options["hostCapabilities"] as? [String: Any])?["microphone"] as? Bool, true)
        XCTAssertNil(options["language"])
        XCTAssertNil(options["customContext"])
    }

    func testEncodesOptionalOptions() throws {
        let html = PageBuilder.html(
            bundleURL: bundleURL,
            options: .init(
                integrationId: "int_123",
                microphone: false,
                language: "fr",
                customContext: ["screen": "cart", "items": 3]
            )
        )
        let options = try options(in: html)
        XCTAssertEqual((options["hostCapabilities"] as? [String: Any])?["microphone"] as? Bool, false)
        XCTAssertEqual(options["language"] as? String, "fr")
        let context = try XCTUnwrap(options["customContext"] as? [String: Any])
        XCTAssertEqual(context["screen"] as? String, "cart")
        XCTAssertEqual(context["items"] as? Int, 3)
    }

    func testValuesCannotBreakOutOfTheScriptTag() throws {
        let html = PageBuilder.html(
            bundleURL: bundleURL,
            options: .init(
                integrationId: "</script><script>alert(1)</script>",
                microphone: false,
                customContext: ["note": "<!-- </SCRIPT>"]
            )
        )
        // Only the page's own two closing tags.
        XCTAssertEqual(html.lowercased().components(separatedBy: "</script").count - 1, 2)
        XCTAssertFalse(html.contains("<!--"))
        let options = try options(in: html)
        XCTAssertEqual(options["integrationId"] as? String, "</script><script>alert(1)</script>")
        XCTAssertEqual((options["customContext"] as? [String: Any])?["note"] as? String, "<!-- </SCRIPT>")
    }

    func testUsesEachEnvironmentsBundle() {
        XCTAssertEqual(KodifChat.Environment.production.bundleURL.absoluteString, "https://js.shippedsuite.com/api/chat-widget.js")
        XCTAssertEqual(KodifChat.Environment.staging.bundleURL.absoluteString, "https://js-staging.shippedsuite.com/api/chat-widget.js")
        XCTAssertEqual(
            KodifChat.Environment.development.bundleURL.absoluteString,
            "https://js.dev.invisiblecommerce.com/api/chat-widget.js"
        )
        XCTAssertEqual(KodifChat.Environment.custom(bundleURL: bundleURL).bundleURL, bundleURL)
    }

    func testEscapesTheBundleURLAttribute() {
        let url = URL(string: "https://example.test/b.js?a=1&b=%22x")!
        let html = PageBuilder.html(bundleURL: url, options: .init(integrationId: "i", microphone: false))
        XCTAssertTrue(html.contains(#"src="https://example.test/b.js?a=1&amp;b=%22x""#))
    }

    func testBaseURLIsHTTPS() {
        XCTAssertEqual(PageBuilder.baseURL.scheme, "https")
        XCTAssertEqual(PageBuilder.baseURL.host, PageBuilder.baseHost)
    }
}
