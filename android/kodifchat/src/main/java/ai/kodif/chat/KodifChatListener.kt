package ai.kodif.chat

/** Events from the chat. Every method has a default. */
public interface KodifChatListener {
    /** A link leaving the chat was tapped. Return true if the app handled it; false (the default) opens a Custom Tab. */
    public fun onOpenUrl(url: String): Boolean = false

    /** The shopper closed the chat. [KodifChatActivity] has finished; a [KodifChatFragment]'s host removes it. */
    public fun onClose() {}

    /** [KodifChat.unreadCount] changed, for an in-app badge. */
    public fun onUnreadCountChanged(count: Int) {}

    /** The chat is switched off for this store, or failed to start (e.g. offline). It has been dismissed; the next
     * [KodifChat.preload] or [KodifChat.open] tries again. */
    public fun onUnavailable() {}
}

/** Fetches the signed-in shopper's JWT from your backend. Never mint tokens in the app. */
public fun interface TokenProvider {
    /** Pass the shopper's JWT to [callback] (from any thread), or null for an anonymous shopper. */
    public fun fetchToken(callback: (String?) -> Unit)
}
