package ai.kodif.chat

import ai.kodif.chat.BridgeMessage.Outgoing

/**
 * The page's bridge state, without the WebView: which load is current, whether its widget is ready or the load
 * failed, and what native → widget messages to deliver when. Every method returns the messages to deliver now.
 */
internal class ChatPageState {
    var isReady: Boolean = false
        private set

    /** The load failed (the bundle didn't load, no `ready` in time, or the widget said `unavailable`). */
    var isFailed: Boolean = false
        private set

    var isVisible: Boolean = false
        private set
    var isForeground: Boolean = true
        private set

    private var loadId = 0
    private val queue = ArrayDeque<Outgoing>()
    private val pendingTokenIds = mutableSetOf<String>()

    /** A new page load starts; returns its id for [loadTimedOut]. */
    fun startLoad(): Int {
        loadId += 1
        isReady = false
        isFailed = false
        queue.clear()
        pendingTokenIds.clear()
        return loadId
    }

    /**
     * The widget's bridge is listening: the current app state and custom context, then the queued messages. Nothing
     * once the load has failed.
     */
    fun ready(customContext: Map<String, Any?>?): List<Outgoing> {
        if (isFailed) return emptyList()
        isReady = true
        val messages = mutableListOf<Outgoing>(Outgoing.AppState(isForeground))
        if (customContext != null) messages += Outgoing.CustomContext(customContext)
        messages += queue
        queue.clear()
        return messages
    }

    /**
     * [message] now if the widget is ready. Before that only tokens are queued: app state and custom context are
     * state, sent as they are on `ready`.
     */
    fun send(message: Outgoing): List<Outgoing> = when {
        isReady -> listOf(message)
        message is Outgoing.Token && !isFailed -> {
            queue.addLast(message)
            emptyList()
        }
        else -> emptyList()
    }

    /** Whether the chat is on screen. Native only: it decides which replies the app hears about. */
    fun setVisible(visible: Boolean) {
        isVisible = visible
    }

    /**
     * Whether a finished reply goes to the app (for a badge): not while the shopper is looking at the chat, i.e.
     * it's on screen and the app is in the foreground.
     */
    val reportsReplies: Boolean get() = !(isVisible && isForeground)

    fun setForeground(foreground: Boolean): List<Outgoing> {
        if (foreground == isForeground) return emptyList()
        isForeground = foreground
        return send(Outgoing.AppState(foreground))
    }

    /**
     * The widget asked for a token. Returns the load it belongs to: pass it to [tokenAnswered], because the widget's
     * request ids start over on every load.
     */
    fun tokenRequested(id: String): Int {
        pendingTokenIds += id
        return loadId
    }

    /**
     * Answers request [id] of load [loadId]. An answer for an earlier load (a slow provider calling back after a
     * reload) is dropped without touching the current load's requests, and a provider calling back twice is answered
     * once.
     */
    fun tokenAnswered(id: String, jwt: String?, loadId: Int): List<Outgoing> =
        if (loadId == this.loadId && pendingTokenIds.remove(id)) send(Outgoing.Token(id, jwt)) else emptyList()

    /** Marks the load failed. True if it wasn't already, i.e. the caller should tear down and report it. */
    fun fail(): Boolean {
        if (isFailed) return false
        isFailed = true
        isReady = false
        queue.clear()
        pendingTokenIds.clear()
        return true
    }

    /** The ready timeout of load [id] fired: a failure if that load is still current and never got `ready`. */
    fun loadTimedOut(id: Int): Boolean = id == loadId && !isReady && fail()
}
