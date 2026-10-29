import Foundation

/// The messages of the bridge protocol (PROTOCOL.md), v1. Pure: no web view needed.
enum BridgeMessage {
    static let version = 1

    /// Widget → native.
    enum Incoming: Equatable, Decodable {
        case ready
        case requestToken(id: String)
        case close
        case message(preview: String, author: KodifChat.Author)
        case openSettings
        case unavailable

        private enum CodingKeys: String, CodingKey {
            case v, type, id, preview, author
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            let version = try container.decode(Int.self, forKey: .v)
            guard version == BridgeMessage.version else {
                throw DecodingError.dataCorruptedError(
                    forKey: .v, in: container, debugDescription: "Unsupported protocol version \(version)"
                )
            }
            switch try container.decode(String.self, forKey: .type) {
            case "ready":
                self = .ready
            case "requestToken":
                self = .requestToken(id: try container.decode(String.self, forKey: .id))
            case "close":
                self = .close
            case "message":
                let preview = try container.decode(String.self, forKey: .preview)
                let rawAuthor = try container.decode(String.self, forKey: .author)
                guard let author = KodifChat.Author(rawValue: rawAuthor) else {
                    throw DecodingError.dataCorruptedError(
                        forKey: .author, in: container, debugDescription: "Unknown author \(rawAuthor)"
                    )
                }
                self = .message(preview: preview, author: author)
            case "openSettings":
                self = .openSettings
            case "unavailable":
                self = .unavailable
            case let type:
                throw DecodingError.dataCorruptedError(
                    forKey: .type, in: container, debugDescription: "Unknown message type \(type)"
                )
            }
        }

        /// Decodes a posted message. Nil for anything native ignores: malformed JSON, a `v` other than
        /// ``BridgeMessage/version``, an unknown `type`, or a known type missing its fields.
        static func decode(_ body: Any) -> Incoming? {
            guard let json = body as? String else { return nil }
            return try? JSONDecoder().decode(Incoming.self, from: Data(json.utf8))
        }
    }

    /// Native → widget.
    enum Outgoing {
        /// Answers `requestToken` (with its `id`), or pushes a login / logout when `id` is nil.
        case token(id: String?, jwt: String?)
        case appState(foreground: Bool)
        case customContext([String: Any]?)

        var type: String {
            switch self {
            case .token: return "token"
            case .appState: return "appState"
            case .customContext: return "customContext"
            }
        }

        var jsonObject: [String: Any] {
            var object: [String: Any] = ["v": BridgeMessage.version, "type": type]
            switch self {
            case let .token(id, jwt):
                if let id { object["id"] = id }
                object["jwt"] = jwt ?? NSNull()
            case let .appState(foreground):
                object["state"] = foreground ? "foreground" : "background"
            case let .customContext(context):
                // JSONEmbedding.encode sanitizes it.
                object["context"] = context ?? NSNull()
            }
            return object
        }

        /// The message as JSON that is safe to embed in a script, or nil if it can't be encoded.
        var json: String? {
            JSONEmbedding.encode(jsonObject)
        }
    }
}

/// Turns app values into JSON that can be embedded in a `<script>` or passed to `evaluateJavaScript`.
enum JSONEmbedding {
    /// Encodes a dictionary or array (sanitized first), escaped with ``escapeForScript(_:)``.
    static func encode(_ object: Any) -> String? {
        let sanitized = sanitize(object)
        guard JSONSerialization.isValidJSONObject(sanitized),
              let data = try? JSONSerialization.data(withJSONObject: sanitized, options: [.sortedKeys, .withoutEscapingSlashes])
        else { return nil }
        return escapeForScript(String(decoding: data, as: UTF8.self))
    }

    /// `<` only occurs inside JSON strings, so writing it as `<` keeps the JSON identical while making sure a value
    /// can't close the script tag (`</script>`) or open an HTML comment. U+2028 / U+2029 are escaped for older
    /// JavaScript engines, where they end a line inside a string literal.
    static func escapeForScript(_ json: String) -> String {
        json
            .replacingOccurrences(of: "<", with: "\\u003c")
            .replacingOccurrences(of: "\u{2028}", with: "\\u2028")
            .replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }

    /// Makes app values JSON-compatible: optionals unwrap (nil → null), non-finite numbers → null, URLs and dates →
    /// strings, anything else unknown → its description.
    static func sanitize(_ value: Any) -> Any {
        let mirror = Mirror(reflecting: value)
        if mirror.displayStyle == .optional {
            guard let wrapped = mirror.children.first?.value else { return NSNull() }
            return sanitize(wrapped)
        }
        switch value {
        case let dictionary as [String: Any]:
            return dictionary.mapValues { sanitize($0) }
        case let array as [Any]:
            return array.map { sanitize($0) }
        case let double as Double where !double.isFinite:
            return NSNull()
        case let float as Float where !float.isFinite:
            return NSNull()
        case is String, is NSNull, is Bool, is Int, is Int8, is Int16, is Int32, is Int64,
             is UInt, is UInt8, is UInt16, is UInt32, is UInt64, is Double, is Float, is NSNumber:
            return value
        case let url as URL:
            return url.absoluteString
        case let date as Date:
            return ISO8601DateFormatter().string(from: date)
        default:
            return String(describing: value)
        }
    }
}
