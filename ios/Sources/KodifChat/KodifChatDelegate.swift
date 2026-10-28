import Foundation

/// Events from the chat. Every method has a default implementation, so implement only what you need.
@MainActor
public protocol KodifChatDelegate: AnyObject {
    /// The shopper tapped a link (a product card, a link in a reply, a file). Return true if the app handled it; false
    /// (the default) opens it in an in-app Safari view.
    func kodifChat(openURL url: URL) -> Bool

    /// The shopper closed the chat; it has been dismissed.
    func kodifChatDidClose()

    /// A reply arrived while the chat wasn't on screen, for an in-app badge.
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author)

    /// The chat is switched off for this store, or failed to start. It has been dismissed.
    func kodifChatUnavailable()
}

public extension KodifChatDelegate {
    func kodifChat(openURL url: URL) -> Bool { false }
    func kodifChatDidClose() {}
    func kodifChat(didReceiveMessage preview: String, author: KodifChat.Author) {}
    func kodifChatUnavailable() {}
}
