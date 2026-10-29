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
        val load = state.tokenRequested("t1")
        assertEquals(listOf(Outgoing.Token("t1", "jwt")), state.tokenAnswered("t1", "jwt", load))
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("t1", "jwt", load))
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("unknown", null, load))
    }

    @Test
    fun anEarlierLoadsTokenAnswerCannotAnswerTheSameIdOnTheNextLoad() {
        val state = ChatPageState()
        state.startLoad()
        state.ready(null)
        val firstLoad = state.tokenRequested("1")

        // Reload: the widget's request ids start over.
        state.startLoad()
        state.ready(null)
        val secondLoad = state.tokenRequested("1")

        // The first load's slow provider calls back: dropped, and the second load's request stays pending.
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("1", "old-shopper", firstLoad))
        assertEquals(listOf(Outgoing.Token("1", "new-shopper")), state.tokenAnswered("1", "new-shopper", secondLoad))
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("1", "new-shopper", secondLoad))
    }

    @Test
    fun aReloadForgetsPendingRequestsAndQueuedMessages() {
        val state = ChatPageState()
        val first = state.startLoad()
        state.tokenRequested("t1")
        state.send(Outgoing.Token(null, "jwt"))
        state.startLoad()
        assertEquals(emptyList<Outgoing>(), state.tokenAnswered("t1", "jwt", first))
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
