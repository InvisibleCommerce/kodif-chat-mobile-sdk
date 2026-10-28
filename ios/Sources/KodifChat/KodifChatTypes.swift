import Foundation

extension KodifChat {
    /// Where the chat widget is loaded from.
    public enum Environment: Equatable, Sendable {
        case production
        case staging
        case development
        /// Any bundle URL, for tests.
        case custom(bundleURL: URL)

        /// The widget bundle for this environment.
        public var bundleURL: URL {
            switch self {
            case .production: return URL(string: "https://js.shippedsuite.com/api/chat-widget.js")!
            case .staging: return URL(string: "https://js-staging.shippedsuite.com/api/chat-widget.js")!
            case .development: return URL(string: "https://js.dev.invisiblecommerce.com/api/chat-widget.js")!
            case let .custom(bundleURL): return bundleURL
            }
        }
    }

    /// Who wrote a message.
    public enum Author: String, Equatable, Sendable {
        case ai
        case agent
    }

    struct Configuration: Equatable {
        var integrationId: String
        var environment: Environment
    }
}
