package ai.kodif.chat

import ai.kodif.chat.BridgeMessage.Outgoing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPageStateTest {
    private val context = mapOf("screen" to "cart")

    @Test
    fun queuesTokensUntilReadyThenSendsTheCurrentStateFirst() {
        val state = ChatPageState()
        state.startLoad()
        assertEquals(emptyList<Outgoing>(), state.send(Outgoing.Token(null, "jwt")))
        assertEquals(emptyList<Outgoing>(), state.setVisible(true))
        assertEquals(emptyList<Outgoing>(), state.send(Outgoing.CustomContext(mapOf("stale" to true))))

        assertEquals(
            listOf(
                Outgoing.Visibility(true),
                Outgoing.AppState(true),
                Outgoing.CustomContext(context),
                Outgoing.Token(null, "jwt"),
            ),
            state.ready(context),
        )
        assertTrue(state.isReady)
        assertEquals(listOf(Outgoing.Visibility(false)), state.setVisible(false))
        assertEquals(listOf(Outgoing.CustomContext(null)), state.send(Outgoing.CustomContext(null)))
    }

    @Test
    fun sendsNoCustomContextOnReadyWhenTheAppHasNone() {
        val state = ChatPageState()
        state.startLoad()
        assertEquals(listOf(Outgoing.Visibility(false), Outgoing.AppState(true)), state.ready(null))
    }

    @Test
    fun answersEachTokenRequestOnce() {
        val state = ChatPageState()
        state.startLoad()
        state.ready(null)
        state.tokenRequested("t1")
        assertEquals(listOf(Outgoing.Token("t1", "jwt")), state.tokenAnswered("t1", "jwt"))
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("t1", "jwt"))
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("unknown", null))
    }

    @Test
    fun aReloadForgetsPendingRequestsAndQueuedMessages() {
        val state = ChatPageState()
        state.startLoad()
        state.tokenRequested("t1")
        state.send(Outgoing.Token(null, "jwt"))
        state.startLoad()
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("t1", "jwt"))
        assertEquals(listOf(Outgoing.Visibility(false), Outgoing.AppState(true)), state.ready(null))
    }

    @Test
    fun aLoadWithoutReadyTimesOutAsAFailure() {
        val state = ChatPageState()
        val load = state.startLoad()
        assertTrue(state.loadTimedOut(load))
        assertTrue(state.isFailed)
        // Reported once.
        assertFalse(state.loadTimedOut(load))
        assertFalse(state.fail())
    }

    @Test
    fun aReadyLoadDoesNotTimeOut() {
        val state = ChatPageState()
        val load = state.startLoad()
        state.ready(null)
        assertFalse(state.loadTimedOut(load))
        assertFalse(state.isFailed)
    }

    @Test
    fun anEarlierLoadsTimeoutIsIgnored() {
        val state = ChatPageState()
        val first = state.startLoad()
        state.startLoad()
        assertFalse(state.loadTimedOut(first))
        assertFalse(state.isFailed)
    }

    @Test
    fun aFailedLoadSendsNothingUntilTheNextLoad() {
        val state = ChatPageState()
        state.startLoad()
        state.ready(null)
        assertTrue(state.fail())
        assertFalse(state.isReady)
        assertEquals(emptyList<Outgoing>(), state.send(Outgoing.Token(null, "jwt")))
        assertEquals(emptyList<Outgoing>(), state.ready(null))

        state.startLoad()
        assertFalse(state.isFailed)
        assertEquals(listOf(Outgoing.Visibility(false), Outgoing.AppState(true)), state.ready(null))
    }

    @Test
    fun appStateChangesAreSentOnlyWhenTheyChange() {
        val state = ChatPageState()
        state.startLoad()
        state.ready(null)
        assertEquals(emptyList<Outgoing>(), state.setForeground(true))
        assertEquals(listOf(Outgoing.AppState(false)), state.setForeground(false))
    }
}
