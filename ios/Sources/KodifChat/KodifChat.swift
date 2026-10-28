import UIKit

/// The Kodif chat. Call ``configure(integrationId:environment:)`` once (e.g. at launch), then present
/// ``KodifChatViewController`` (or ``KodifChatView`` in SwiftUI).
@MainActor
public enum KodifChat {
    /// Supplies the shopper's JWT: call the completion with it (from any thread), or with nil for an anonymous shopper.
    public typealias TokenProvider = (@escaping (String?) -> Void) -> Void

    private(set) static var configuration: Configuration?
    static var session: KodifChatSession?

    /// Sets up the chat for `integrationId`, the id of your web chat channel's integration. Calling it again with a
    /// different id or environment discards the running chat.
    public static func configure(integrationId: String, environment: Environment = .production) {
        let newConfiguration = Configuration(integrationId: integrationId, environment: environment)
        guard newConfiguration != configuration else { return }
        session?.invalidate()
        session = nil
        configuration = newConfiguration
    }

    /// Supplies the shopper's JWT when the widget asks for one: sign it on your backend with the secret from the
    /// Custom Authentication tab of your web chat settings. Nil (the default): the shopper is anonymous.
    public static var tokenProvider: TokenProvider?

    /// Pushes the shopper's login (a JWT) or logout (nil) to a running chat. ``tokenProvider`` still answers the
    /// widget's own requests, so keep it in sync with the shopper's state.
    public static func setShopperToken(_ jwt: String?) {
        session?.send(.token(id: nil, jwt: jwt))
    }

    /// Offers voice mode. Your app's Info.plist must have `NSMicrophoneUsageDescription` (iOS terminates the app
    /// without it). Set it before ``preload()`` / presenting the chat.
    public static var voiceEnabled = false

    /// The chat's locale (e.g. `fr`). Nil: the widget detects it. Set it before ``preload()`` / presenting the chat.
    public static var language: String?

    /// Key/values attached to the conversation (screen, product, app version). Values should be JSON-compatible
    /// (strings, numbers, booleans, `NSNull`, arrays and dictionaries). Setting it updates a chat that's running.
    public static var customContext: [String: Any]? {
        didSet { session?.send(.customContext(customContext)) }
    }

    private static let delegateBox = WeakDelegateBox()

    /// Receives the chat's events. Held weakly.
    public static var delegate: KodifChatDelegate? {
        get { delegateBox.value }
        set { delegateBox.value = newValue }
    }

    /// Starts the chat in the background, so presenting it later is instant.
    public static func preload() {
        _ = sharedSession()
    }

    /// Presents the chat full screen from `presenter`. Does nothing if the chat is already on screen.
    public static func present(from presenter: UIViewController, animated: Bool = true) {
        guard configuration != nil else {
            kodifChatLog.error("Call KodifChat.configure(integrationId:) before presenting the chat")
            return
        }
        if let host = session?.hostController, host.viewIfLoaded?.window != nil { return }
        presenter.present(KodifChatViewController(), animated: animated)
    }

    static func sharedSession() -> KodifChatSession? {
        if let session { return session }
        guard let configuration else {
            kodifChatLog.error("Call KodifChat.configure(integrationId:) first")
            return nil
        }
        let newSession = KodifChatSession(configuration: configuration)
        session = newSession
        return newSession
    }
}

private final class WeakDelegateBox {
    weak var value: KodifChatDelegate?
}
