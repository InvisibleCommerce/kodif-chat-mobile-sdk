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

    /// The widget's bridge is listening: the current visibility, app state and custom context, then the queued
    /// messages. Nothing once the load has failed.
    mutating func ready(customContext: [String: Any]?) -> [BridgeMessage.Outgoing] {
        guard !isFailed else { return [] }
        isReady = true
        var messages: [BridgeMessage.Outgoing] = [.visibility(isVisible), .appState(foreground: isForeground)]
        if let customContext { messages.append(.customContext(customContext)) }
        messages += queue
        queue.removeAll()
        return messages
    }

    /// `message` now if the widget is ready. Before that only tokens are queued: visibility, app state and custom
    /// context are state, sent as they are on `ready`.
    mutating func send(_ message: BridgeMessage.Outgoing) -> [BridgeMessage.Outgoing] {
        if isReady { return [message] }
        if message.isQueuedUntilReady && !isFailed { queue.append(message) }
        return []
    }

    mutating func setVisible(_ visible: Bool) -> [BridgeMessage.Outgoing] {
        guard visible != isVisible else { return [] }
        isVisible = visible
        return send(.visibility(visible))
    }

    mutating func setForeground(_ foreground: Bool) -> [BridgeMessage.Outgoing] {
        guard foreground != isForeground else { return [] }
        isForeground = foreground
        return send(.appState(foreground: foreground))
    }

    mutating func tokenRequested(_ id: String) {
        pendingTokenIDs.insert(id)
    }

    /// Answers a pending `requestToken`; a stale id (reloaded page, provider calling back twice) gets nothing.
    mutating func tokenAnswered(_ id: String, jwt: String?) -> [BridgeMessage.Outgoing] {
        guard pendingTokenIDs.remove(id) != nil else { return [] }
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
