import UIKit
import WebKit

/// The Kodif chat. Call ``configure(integrationId:environment:)`` once (e.g. at launch), then present
/// ``KodifChatViewController`` (or ``KodifChatView`` in SwiftUI).
@MainActor
public enum KodifChat {
    /// Fetches the signed-in shopper's JWT from your backend: call the completion with it (from any thread), or with
    /// nil for an anonymous shopper. Never mint tokens in the app.
    public typealias TokenProvider = (@escaping (String?) -> Void) -> Void

    private(set) static var configuration: Configuration?
    static var session: KodifChatSession?

    /// Sets up the chat for `integrationId`, your web chat channel's integration id. Calling it again with a different
    /// id or environment discards the running chat.
    public static func configure(integrationId: String, environment: Environment = .production) {
        let newConfiguration = Configuration(integrationId: integrationId, environment: environment)
        guard newConfiguration != configuration else { return }
        session?.invalidate()
        session = nil
        configuration = newConfiguration
    }

    /// Supplies the shopper's JWT whenever the chat asks for one (when it starts and when a token expires). Nil (the
    /// default): the shopper is anonymous. See the README's "Shopper tokens".
    public static var tokenProvider: TokenProvider?

    /// Offers voice mode. Your Info.plist must have `NSMicrophoneUsageDescription` (iOS terminates the app without
    /// it). Set it before ``preload()`` / presenting the chat.
    public static var voiceEnabled = false

    /// The chat's locale (e.g. `fr`). Nil: the widget detects it. Set it before ``preload()`` / presenting the chat.
    public static var language: String?

    /// Key/values attached to the conversation (screen, product, app version), as JSON values. Setting it replaces
    /// the conversation's context (nil clears it). Keys whose values aren't JSON are dropped.
    public static var customContext: [String: Any]? {
        didSet { session?.sendCustomContext() }
    }

    private static let delegateBox = WeakDelegateBox()

    /// Receives the chat's events. Held weakly.
    public static var delegate: KodifChatDelegate? {
        get { delegateBox.value }
        set { delegateBox.value = newValue }
    }

    /// Replies that arrived while the shopper wasn't looking (the chat off screen, or the app in the background).
    /// Showing the chat resets it to 0.
    public internal(set) static var unreadCount = 0 {
        didSet {
            if unreadCount != oldValue { delegate?.kodifChat(unreadCountDidChange: unreadCount) }
        }
    }

    /// Call when the shopper signs in or out. Asks ``tokenProvider``: nil means they signed out, so the chat forgets
    /// the previous shopper (its data on this device is cleared). Either way the chat restarts with the new shopper.
    public static func shopperChanged() {
        guard let tokenProvider else {
            Task { await restart(signedOut: true) }
            return
        }
        tokenProvider { @Sendable jwt in
            Task { @MainActor in await restart(signedOut: jwt == nil) }
        }
    }

    private static func restart(signedOut: Bool) async {
        if signedOut {
            let store = WKWebsiteDataStore.default()
            let types = WKWebsiteDataStore.allWebsiteDataTypes()
            let records = await store.dataRecords(ofTypes: types)
            await store.removeData(ofTypes: types, for: records.filter { Bridge.baseHost.hasSuffix($0.displayName) })
        }
        session?.reload()
    }

    /// Starts the chat in the background, so presenting it later is instant. Call it from your first screen: the web
    /// view is created on the next turn of the main run loop, so it doesn't hold up that screen.
    public static func preload() {
        DispatchQueue.main.async {
            sharedSession()?.reloadIfNeeded()
        }
    }

    /// Presents the chat full screen from `presenter`. Does nothing if it's already on screen.
    public static func present(from presenter: UIViewController, animated: Bool = true) {
        guard configuration != nil else {
            print("[KodifChat] Call KodifChat.configure(integrationId:) before presenting the chat.")
            return
        }
        if session?.hostController?.viewIfLoaded?.window != nil { return }
        presenter.present(KodifChatViewController(), animated: animated)
    }

    static func sharedSession() -> KodifChatSession? {
        if let session { return session }
        guard let configuration else { return nil }
        session = KodifChatSession(configuration: configuration)
        return session
    }
}

private final class WeakDelegateBox {
    weak var value: KodifChatDelegate?
}
