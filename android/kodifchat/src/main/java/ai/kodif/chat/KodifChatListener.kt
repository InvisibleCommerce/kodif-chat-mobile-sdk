package ai.kodif.chat

/** Events from the chat. Every method has a default, so implement only what you need. */
public interface KodifChatListener {
    /**
     * The shopper tapped a link (a product card, a link in a reply, a file). Return true if the app handled it;
     * false (the default) opens it in a Custom Tab.
     */
    public fun onOpenUrl(url: String): Boolean = false

    /** The shopper closed the chat. [KodifChatActivity] has already finished; a [KodifChatFragment] host removes it. */
    public fun onClose() {}

    /** A reply arrived while the chat wasn't on screen, for an in-app badge. */
    public fun onMessage(preview: String, author: KodifChat.Author) {}

    /** The chat is switched off for this store, or failed to start. The chat UI has been dismissed. */
    public fun onUnavailable() {}
}

/** Supplies the signed-in shopper's JWT. */
public fun interface TokenProvider {
    /**
     * Fetch the shopper's JWT (signed with the secret from the Custom Authentication tab of your web chat settings)
     * and pass it to [callback], from any thread. Pass null for an anonymous shopper.
     */
    public fun fetchToken(callback: (String?) -> Unit)
}
