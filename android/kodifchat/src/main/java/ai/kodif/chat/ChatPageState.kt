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
     * The widget's bridge is listening: the current visibility, app state and custom context, then the queued
     * messages. Nothing once the load has failed.
     */
    fun ready(customContext: Map<String, Any?>?): List<Outgoing> {
        if (isFailed) return emptyList()
        isReady = true
        val messages = mutableListOf<Outgoing>(Outgoing.Visibility(isVisible), Outgoing.AppState(isForeground))
        if (customContext != null) messages += Outgoing.CustomContext(customContext)
        messages += queue
        queue.clear()
        return messages
    }

    /**
     * [message] now if the widget is ready. Before that only tokens are queued: visibility, app state and custom
     * context are state, sent as they are on `ready`.
     */
    fun send(message: Outgoing): List<Outgoing> = when {
        isReady -> listOf(message)
        message is Outgoing.Token && !isFailed -> {
            queue.addLast(message)
            emptyList()
        }
        else -> emptyList()
    }

    fun setVisible(visible: Boolean): List<Outgoing> {
        if (visible == isVisible) return emptyList()
        isVisible = visible
        return send(Outgoing.Visibility(visible))
    }

    fun setForeground(foreground: Boolean): List<Outgoing> {
        if (foreground == isForeground) return emptyList()
        isForeground = foreground
        return send(Outgoing.AppState(foreground))
    }

    fun tokenRequested(id: String) {
        pendingTokenIds += id
    }

    /** Answers a pending `requestToken`; a stale id (reloaded page, provider calling back twice) gets nothing. */
    fun tokenAnswered(id: String, jwt: String?): List<Outgoing> =
        if (pendingTokenIds.remove(id)) send(Outgoing.Token(id, jwt)) else emptyList()

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
