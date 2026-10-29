import Foundation

/// Events from the chat. Every method has a default implementation.
@MainActor
public protocol KodifChatDelegate: AnyObject {
    /// A link leaving the chat was tapped. Return true if the app handled it; false (the default) opens it in an
    /// in-app Safari view.
    func kodifChat(openURL url: URL) -> Bool

    /// The shopper closed the chat; it has been dismissed.
    func kodifChatDidClose()

    /// ``KodifChat/unreadCount`` changed, for an in-app badge.
    func kodifChat(unreadCountDidChange count: Int)

    /// The chat is switched off for this store, or failed to start (e.g. offline). It has been dismissed; the next
    /// preload or presentation tries again.
    func kodifChatUnavailable()
}

public extension KodifChatDelegate {
    func kodifChat(openURL url: URL) -> Bool { false }
    func kodifChatDidClose() {}
    func kodifChat(unreadCountDidChange count: Int) {}
    func kodifChatUnavailable() {}
}
