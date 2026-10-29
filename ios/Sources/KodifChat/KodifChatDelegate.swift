import Foundation

/// Events from the chat. Every method has a default implementation, so implement only what you need.
@MainActor
public protocol KodifChatDelegate: AnyObject {
    /// The shopper tapped a link that leaves the chat (a product card, a link in a reply, a file; new-window links
    /// too). Return true if the app handled it; false (the default) opens it in an in-app Safari view.
    func kodifChat(openURL url: URL) -> Bool

    /// The shopper closed the chat; it has been dismissed.
    func kodifChatDidClose()

    /// A reply arrived while the shopper wasn't looking (the chat isn't on screen, or the app is in the background),
    /// for an in-app badge.
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author)

    /// The chat is switched off for this store, or failed to start (it didn't load, e.g. offline). It has been
    /// dismissed; the next ``KodifChat/preload()`` or presentation tries again.
    func kodifChatUnavailable()
}

public extension KodifChatDelegate {
    func kodifChat(openURL url: URL) -> Bool { false }
    func kodifChatDidClose() {}
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author) {}
    func kodifChatUnavailable() {}
}
