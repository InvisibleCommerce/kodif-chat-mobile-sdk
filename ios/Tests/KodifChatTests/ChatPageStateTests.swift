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
        XCTAssertTrue(state.setVisible(true).isEmpty)
        XCTAssertTrue(state.send(.customContext(["stale": true])).isEmpty)

        XCTAssertEqual(
            json(state.ready(customContext: ["screen": "cart"])),
            json(
                .visibility(true),
                .appState(foreground: true),
                .customContext(["screen": "cart"]),
                .token(id: nil, jwt: "jwt")
            )
        )
        XCTAssertTrue(state.isReady)
        XCTAssertEqual(json(state.setVisible(false)), json(.visibility(false)))
        XCTAssertEqual(json(state.send(.customContext(nil))), [#"{"context":null,"type":"customContext","v":1}"#])
    }

    func testSendsNoCustomContextOnReadyWhenTheAppHasNone() {
        var state = ChatPageState()
        _ = state.startLoad()
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.visibility(false), .appState(foreground: true)))
    }

    func testAnswersEachTokenRequestOnce() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        state.tokenRequested("t1")
        XCTAssertEqual(json(state.tokenAnswered("t1", jwt: "jwt")), json(.token(id: "t1", jwt: "jwt")))
        XCTAssertTrue(state.tokenAnswered("t1", jwt: "jwt").isEmpty)
        XCTAssertTrue(state.tokenAnswered("unknown", jwt: nil).isEmpty)
    }

    func testAReloadForgetsPendingRequestsAndQueuedMessages() {
        var state = ChatPageState()
        _ = state.startLoad()
        state.tokenRequested("t1")
        _ = state.send(.token(id: nil, jwt: "jwt"))
        _ = state.startLoad()
        XCTAssertTrue(state.tokenAnswered("t1", jwt: "jwt").isEmpty)
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.visibility(false), .appState(foreground: true)))
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
        XCTAssertEqual(json(state.ready(customContext: nil)), json(.visibility(false), .appState(foreground: true)))
    }

    func testAppStateChangesAreSentOnlyWhenTheyChange() {
        var state = ChatPageState()
        _ = state.startLoad()
        _ = state.ready(customContext: nil)
        XCTAssertTrue(state.setForeground(true).isEmpty)
        XCTAssertEqual(json(state.setForeground(false)), json(.appState(foreground: false)))
    }
}
