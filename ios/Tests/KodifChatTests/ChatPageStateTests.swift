import XCTest
@testable import KodifChat

final class ChatPageStateTests: XCTestCase {
    private func json(_ messages: [BridgeMessage.Outgoing]) -> [String] {
        messages.map { $0.json ?? "<unencodable>" }
    }

    private func json(_ messages: BridgeMessage.Outgoing...) -> [String] {
        json(messages)
    }

    func testQueuesTokensUntilReadyThenSendsTheCurrentStateFirst() {
        var state = ChatPageState()
        _ = state.startLoad()
        XCTAssertTrue(state.send(.token(id: nil, jwt: "jwt")).isEmpty)
        XCTAssertTrue(state.setForeground(false).isEmpty)
        XCTAssertTrue(state.send(.customContext(["stale": true])).isEmpty)

        XCTAssertEqual(
            json(state.ready(customContext: ["screen": "cart"])),
            json(.appState(foreground: false), .customContext(["screen": "cart"]), .token(id: nil, jwt: "jwt"))
        )
        XCTAssertTrue(state.isReady)
        XCTAssertEqual(json(state.setForeground(true)), json(.appState(foreground: true)))
        XCTAssertEqual(json(state.send(.customContext(nil))), [#"{"context":null,"type":"customContext","v":1}"#])
    }

    func testReportsRepliesUnlessTheChatIsOnScreenInTheForeground() {
        var state = ChatPageState()
        XCTAssertTrue(state.reportsReplies) // preloaded, not shown
        state.setVisible(true)
        XCTAssertFalse(state.reportsReplies) // the shopper is watching
        _ = state.setForeground(false)
        XCTAssertTrue(state.reportsReplies) // on screen, but the app is in the background
        _ = state.setForeground(true)
        state.setVisible(false)
        XCTAssertTrue(state.reportsReplies) // closed
    }

    func testSendsNoCustomContextOnReadyWhenTheAppHasNone() {
        var state = ChatPageState()
        _ = state.startLoad()
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.appState(foreground: true)))
    }

    func testAnswersEachTokenRequestOnce() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        let load = state.tokenRequested("t1")
        XCTAssertEqual(json(state.tokenAnswered("t1", jwt: "jwt", loadID: load)), json(.token(id: "t1", jwt: "jwt")))
        XCTAssertTrue(state.tokenAnswered("t1", jwt: "jwt", loadID: load).isEmpty)
        XCTAssertTrue(state.tokenAnswered("unknown", jwt: nil, loadID: load).isEmpty)
    }

    func testAnEarlierLoadsTokenAnswerCannotAnswerTheSameIdOnTheNextLoad() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        let firstLoad = state.tokenRequested("1")

        // Reload: the widget's request ids start over.
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        let secondLoad = state.tokenRequested("1")

        // The first load's slow provider calls back: dropped, and the second load's request stays pending.
        XCTAssertTrue(state.tokenAnswered("1", jwt: "old-shopper", loadID: firstLoad).isEmpty)
        XCTAssertEqual(
            json(state.tokenAnswered("1", jwt: "new-shopper", loadID: secondLoad)),
            json(.token(id: "1", jwt: "new-shopper"))
        )
        XCTAssertTrue(state.tokenAnswered("1", jwt: "new-shopper", loadID: secondLoad).isEmpty)
    }

    func testAReloadForgetsPendingRequestsAndQueuedMessages() {
        var state = ChatPageState()
        let first = state.startLoad()
        _ = state.tokenRequested("t1")
        _ = state.send(.token(id: nil, jwt: "jwt"))
        _ = state.startLoad()
        XCTAssertTrue(state.tokenAnswered("t1", jwt: "jwt", loadID: first).isEmpty)
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.appState(foreground: true)))
    }

    func testALoadWithoutReadyTimesOutAsAFailure() {
        var state = ChatPageState()
        let load = state.startLoad()
        XCTAssertTrue(state.loadTimedOut(load))
        XCTAssertTrue(state.isFailed)
        // Reported once.
        XCTAssertFalse(state.loadTimedOut(load))
        XCTAssertFalse(state.fail())
    }

    func testAReadyLoadDoesNotTimeOut() {
        var state = ChatPageState()
        let load = state.startLoad()
        _ = state.ready(customContext: nil)
        XCTAssertFalse(state.loadTimedOut(load))
        XCTAssertFalse(state.isFailed)
    }

    func testAnEarlierLoadsTimeoutIsIgnored() {
        var state = ChatPageState()
        let first = state.startLoad()
        _ = state.startLoad()
        XCTAssertFalse(state.loadTimedOut(first))
        XCTAssertFalse(state.isFailed)
    }

    func testAFailedLoadSendsNothingUntilTheNextLoad() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        XCTAssertTrue(state.fail())
        XCTAssertFalse(state.isReady)
        XCTAssertTrue(state.send(.token(id: nil, jwt: "jwt")).isEmpty)
        XCTAssertTrue(state.ready(customContext: nil).isEmpty)

        _ = state.startLoad()
        XCTAssertFalse(state.isFailed)
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.appState(foreground: true)))
    }

    func testAppStateChangesAreSentOnlyWhenTheyChange() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        XCTAssertTrue(state.setForeground(true).isEmpty)
        XCTAssertEqual(json(state.setForeground(false)), json(.appState(foreground: false)))
    }
}
