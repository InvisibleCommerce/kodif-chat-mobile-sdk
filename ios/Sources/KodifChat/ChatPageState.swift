import Foundation

/// The page's bridge state, without the web view: which load is current, whether its widget is ready or the load
/// failed, and what native → widget messages to deliver when. Every mutating method returns the messages to deliver
/// now.
struct ChatPageState {
    private(set) var isReady = false
    /// The load failed (the bundle didn't load, no `ready` in time, or the widget said `unavailable`).
    private(set) var isFailed = false
    private(set) var isVisible = false
    private(set) var isForeground = true

    private var loadID = 0
    private var queue: [BridgeMessage.Outgoing] = []
    private var pendingTokenIDs: Set<String> = []

    /// A new page load starts; returns its id for ``loadTimedOut(_:)``.
    mutating func startLoad() -> Int {
        loadID += 1
        isReady = false
        isFailed = false
        queue.removeAll()
        pendingTokenIDs.removeAll()
        return loadID
    }

    /// The widget's bridge is listening: the current app state and custom context, then the queued messages. Nothing
    /// once the load has failed.
    mutating func ready(customContext: [String: Any]?) -> [BridgeMessage.Outgoing] {
        guard !isFailed else { return [] }
        isReady = true
        var messages: [BridgeMessage.Outgoing] = [.appState(foreground: isForeground)]
        if let customContext { messages.append(.customContext(customContext)) }
        messages += queue
        queue.removeAll()
        return messages
    }

    /// `message` now if the widget is ready. Before that only tokens are queued: app state and custom context are
    /// state, sent as they are on `ready`.
    mutating func send(_ message: BridgeMessage.Outgoing) -> [BridgeMessage.Outgoing] {
        if isReady { return [message] }
        if case .token = message, !isFailed { queue.append(message) }
        return []
    }

    /// Whether the chat is on screen. Native only: it decides which replies the app hears about.
    mutating func setVisible(_ visible: Bool) {
        isVisible = visible
    }

    /// Whether a finished reply goes to the app (for a badge): not while the shopper is looking at the chat, i.e.
    /// it's on screen and the app is in the foreground.
    var reportsReplies: Bool {
        !(isVisible && isForeground)
    }

    mutating func setForeground(_ foreground: Bool) -> [BridgeMessage.Outgoing] {
        guard foreground != isForeground else { return [] }
        isForeground = foreground
        return send(.appState(foreground: foreground))
    }

    /// The widget asked for a token. Returns the load it belongs to: pass it to ``tokenAnswered(_:jwt:loadID:)``,
    /// because the widget's request ids start over on every load.
    mutating func tokenRequested(_ id: String) -> Int {
        pendingTokenIDs.insert(id)
        return loadID
    }

    /// Answers request `id` of load `loadID`. An answer for an earlier load (a slow provider calling back after a
    /// reload) is dropped without touching the current load's requests, and a provider calling back twice is answered
    /// once.
    mutating func tokenAnswered(_ id: String, jwt: String?, loadID: Int) -> [BridgeMessage.Outgoing] {
        guard loadID == self.loadID, pendingTokenIDs.remove(id) != nil else { return [] }
        return send(.token(id: id, jwt: jwt))
    }

    /// Marks the load failed. True if it wasn't already, i.e. the caller should tear down and report it.
    mutating func fail() -> Bool {
        guard !isFailed else { return false }
        isFailed = true
        isReady = false
        queue.removeAll()
        pendingTokenIDs.removeAll()
        return true
    }

    /// The ready timeout of load `id` fired: a failure if that load is still current and never got `ready`.
    mutating func loadTimedOut(_ id: Int) -> Bool {
        id == loadID && !isReady && fail()
    }
}
