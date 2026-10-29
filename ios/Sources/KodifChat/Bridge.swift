import Foundation

/// The bridge protocol (PROTOCOL.md), v1, and the page that hosts the widget.
enum Bridge {
    /// The page's origin: https, so voice mode's microphone gets a secure context. Never fetched.
    static let baseURL = URL(string: "https://mobile-chat.kodif.ai/")!
    static let baseHost = baseURL.host!

    /// Widget → native.
    enum Incoming: Equatable {
        case requestToken(id: String)
        case close
        case reply
        case openSettings
        case unavailable
    }

    private struct Envelope: Decodable {
        let v: Int
        let type: String
        let id: String?
    }

    /// Nil for anything native ignores: malformed JSON, another `v`, an unknown type, a missing field.
    static func decode(_ body: Any) -> Incoming? {
        guard let json = body as? String,
              let message = try? JSONDecoder().decode(Envelope.self, from: Data(json.utf8)),
              message.v == 1
        else { return nil }
        switch message.type {
        case "requestToken": return message.id.map { .requestToken(id: $0) }
        case "close": return .close
        case "reply": return .reply
        case "openSettings": return .openSettings
        case "unavailable": return .unavailable
        default: return nil
        }
    }

    // Native → widget.

    static func token(id: String, jwt: String?) -> String {
        encode(["type": "token", "id": id, "jwt": jwt.map { $0 as Any } ?? NSNull()])
    }

    static func appState(foreground: Bool) -> String {
        encode(["type": "appState", "state": foreground ? "foreground" : "background"])
    }

    /// Keys whose values aren't valid JSON are dropped.
    static func customContext(_ context: [String: Any]?) -> String {
        let valid = context?.filter { key, value in
            let isValid = JSONSerialization.isValidJSONObject([key: value])
            #if DEBUG
            if !isValid { print("[KodifChat] Dropped customContext[\"\(key)\"]: not a JSON value") }
            #endif
            return isValid
        }
        return encode(["type": "customContext", "context": valid.map { $0 as Any } ?? NSNull()])
    }

    private static func encode(_ message: [String: Any]) -> String {
        var message = message
        message["v"] = 1
        let data = (try? JSONSerialization.data(withJSONObject: message, options: [.sortedKeys])) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    // The page.

    static func html(bundleURL: URL, integrationId: String, microphone: Bool, language: String?) -> String {
        var options: [String: Any] = [
            "integrationId": integrationId,
            "host": "ios",
            "hostCapabilities": ["microphone": microphone],
        ]
        if let language { options["language"] = language }
        return """
        <!doctype html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
          <script>
            window.KodifChatNative = { pending: [], receive: function (m) { this.pending.push(m) } };
            window.addEventListener('shipped:chat-script-loaded', function () {
              new window.ShippedAI.ChatWidget(\(scriptJSON(options)))
            });
            var bundle = document.createElement('script');
            bundle.type = 'module';
            bundle.src = \(scriptJSON(bundleURL.absoluteString));
            // A bundle that fails to load never starts the widget, so the page reports it. (WKWebView fired no
            // error for a failed module script written in the HTML; one inserted like this does.)
            bundle.onerror = function () {
              var m = JSON.stringify({ v: 1, type: 'unavailable' });
              var w = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.kodifChat;
              if (w) { w.postMessage(m) } else if (window.kodifChat) { window.kodifChat.postMessage(m) }
            };
            document.head.appendChild(bundle);
          </script>
        </head>
        <body></body>
        </html>

        """
    }

    /// `value` as JSON inside a `<script>`: `<` only occurs inside JSON strings, so escaping it keeps a value from
    /// closing the script tag.
    private static func scriptJSON(_ value: Any) -> String {
        let data = (try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])) ?? Data()
        return String(decoding: data, as: UTF8.self).replacingOccurrences(of: "<", with: "\\u003c")
    }
}
