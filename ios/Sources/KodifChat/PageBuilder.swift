import Foundation

/// Builds the page that hosts the widget (PROTOCOL.md, "The page").
enum PageBuilder {
    /// The page's origin: https, so voice mode's microphone gets a secure context. Never fetched.
    static let baseURL = URL(string: "https://mobile-chat.kodif.ai/")!
    static let baseHost = baseURL.host!

    struct Options {
        var integrationId: String
        var microphone: Bool
        var language: String?
    }

    /// Posted straight to the native handler when the bundle fails to load, so native treats it like the widget's
    /// own `unavailable` (the widget, and so its bridge, never started). iOS's handler, else Android's.
    static let bundleErrorHandler =
        "(function(){var m=JSON.stringify({v:1,type:'unavailable'});"
        + "var w=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.kodifChat;"
        + "if(w){w.postMessage(m)}else if(window.kodifChat){window.kodifChat.postMessage(m)}})()"

    /// The widget options, as JSON that is safe inside a `<script>`. Custom context isn't one: native sends it as a
    /// `customContext` message after `ready`, so later changes replace it rather than merge into it.
    static func optionsJSON(_ options: Options) -> String {
        var object: [String: Any] = [
            "integrationId": options.integrationId,
            "host": "ios",
            "hostCapabilities": ["microphone": options.microphone],
        ]
        if let language = options.language { object["language"] = language }
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
          <script type="module" src="\(escapeAttribute(bundleURL.absoluteString))" onerror="\(escapeAttribute(bundleErrorHandler))"></script>
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
