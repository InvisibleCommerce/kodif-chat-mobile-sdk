import Foundation

/// Builds the page that hosts the widget (PROTOCOL.md, "The page").
enum PageBuilder {
    /// The page's origin: https, so voice mode's microphone gets a secure context. Never fetched.
    static let baseURL = URL(string: "https://mobile-chat.kodif.ai/")!
    static let baseHost = "mobile-chat.kodif.ai"

    struct Options {
        var integrationId: String
        var microphone: Bool
        var language: String?
        var customContext: [String: Any]?
        var host = "ios"
    }

    /// The widget options, as JSON that is safe inside a `<script>`.
    static func optionsJSON(_ options: Options) -> String {
        var object: [String: Any] = [
            "integrationId": options.integrationId,
            "host": options.host,
            "hostCapabilities": ["microphone": options.microphone],
        ]
        if let language = options.language { object["language"] = language }
        if let customContext = options.customContext { object["customContext"] = customContext }
        if let json = JSONEmbedding.encode(object) { return json }
        // Unreachable after sanitizing; drop the app-supplied context rather than fail to start.
        object["customContext"] = nil
        return JSONEmbedding.encode(object) ?? "{}"
    }

    static func html(bundleURL: URL, options: Options) -> String {
        """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
          <script>
            window.addEventListener('shipped:chat-script-loaded', function () {
              new window.ShippedAI.ChatWidget(\(optionsJSON(options)))
            })
          </script>
          <script type="module" src="\(escapeAttribute(bundleURL.absoluteString))"></script>
        </head>
        <body></body>
        </html>

        """
    }

    private static func escapeAttribute(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
    }
}
