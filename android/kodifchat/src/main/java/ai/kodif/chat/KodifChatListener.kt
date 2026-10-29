package ai.kodif.chat

/** Events from the chat. Every method has a default, so implement only what you need. */
public interface KodifChatListener {
    /**
     * The shopper tapped a link that leaves the chat (a product card, a link in a reply, a file; new-window links
     * too). Return true if the app handled it; false (the default) opens it in a Custom Tab.
     */
    public fun onOpenUrl(url: String): Boolean = false

    /** The shopper closed the chat. [KodifChatActivity] has already finished; a [KodifChatFragment] host removes it. */
    public fun onClose() {}

    /**
     * A reply arrived while the shopper wasn't looking (the chat isn't on screen, or the app is in the background),
     * for an in-app badge.
     */
    public fun onMessage(preview: String, author: KodifChat.Author) {}

    /**
     * The chat is switched off for this store, or failed to start (it didn't load, e.g. offline). The chat UI has
     * been dismissed; the next [KodifChat.preload] or [KodifChat.open] tries again.
     */
    public fun onUnavailable() {}
}

/** Supplies the signed-in shopper's JWT. */
public fun interface TokenProvider {
    /**
     * Fetch a JWT for the signed-in shopper from your backend, and pass it to [callback], from any thread; pass null
     * for an anonymous shopper. Your backend signs it with its own private key (RS256 or ES256), and the public key
     * (or a JWKS URL) goes on the Custom Authentication tab of your web chat settings. Never mint tokens in the app.
     */
    public fun fetchToken(callback: (String?) -> Unit)
}
