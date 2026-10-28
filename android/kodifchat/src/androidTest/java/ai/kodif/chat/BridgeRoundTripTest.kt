package ai.kodif.chat

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Loads the real page (asset loader origin, generated HTML, module script) with a stub widget bundle and checks the
 * bridge both ways: `ready` and `requestToken` reach native, and native's `token` answer reaches the widget.
 */
@RunWith(AndroidJUnit4::class)
class BridgeRoundTripTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    // Plays the widget: records what native sends and posts what mobile mode posts.
    private val stubBundle = """
        window.__received = [];
        window.ShippedAI = {
          ChatWidget: function (options) {
            window.__options = options;
            var post = function (m) { window.kodifChat.postMessage(JSON.stringify(m)); };
            window.KodifChatNative = { receive: function (m) { window.__received.push(m); } };
            post({ v: 1, type: 'ready' });
            post({ v: 1, type: 'requestToken', id: 't1' });
          }
        };
        window.dispatchEvent(new Event('shipped:chat-script-loaded'));
    """.trimIndent()

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            KodifChat.discardSession()
            KodifChat.tokenProvider = null
        }
    }

    @Test
    fun readyAndTokenRoundTrip() {
        val bundleUrl = "data:text/javascript;base64," + Base64.encodeToString(stubBundle.toByteArray(), Base64.NO_WRAP)
        val incoming = CopyOnWriteArrayList<BridgeMessage.Incoming>()
        val gotRequest = CountDownLatch(1)

        instrumentation.runOnMainSync {
            KodifChat.configure(instrumentation.targetContext, "test-integration", KodifChat.Environment.Custom(bundleUrl))
            KodifChat.tokenProvider = TokenProvider { callback -> Thread { callback("test-jwt") }.start() }
            val session = KodifChat.requireSession()
            session.onIncoming = { message ->
                incoming += message
                if (message is BridgeMessage.Incoming.RequestToken) gotRequest.countDown()
            }
            session.preload()
        }

        assertTrue("the widget never asked for a token", gotRequest.await(30, TimeUnit.SECONDS))
        assertEquals(BridgeMessage.Incoming.Ready, incoming.first())
        assertEquals(BridgeMessage.Incoming.RequestToken("t1"), incoming[1])

        val received = pollJs("JSON.stringify(window.__received)") { it.contains("\"type\":\"token\"") }
        assertTrue(received, received.contains("{\"v\":1,\"type\":\"token\",\"id\":\"t1\",\"jwt\":\"test-jwt\"}"))
        // On ready, native sends the current state first.
        assertTrue(received, received.contains("\"type\":\"visibility\",\"visible\":false"))
        assertTrue(received, received.contains("\"type\":\"appState\""))

        assertEquals("true", evaluate("window.isSecureContext"))
        assertEquals("\"android\"", evaluate("window.__options.host"))
        assertEquals("\"https://appassets.androidplatform.net\"", evaluate("location.origin"))
    }

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            KodifChat.session!!.webView!!.evaluateJavascript(script) {
                result = it
                latch.countDown()
            }
        }
        assertTrue("evaluateJavascript timed out", latch.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun pollJs(script: String, done: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 15_000
        var value = ""
        while (System.currentTimeMillis() < deadline) {
            // evaluateJavascript returns the string JSON-encoded; decode one level.
            value = evaluate(script).let { if (it == "null") it else org.json.JSONArray("[$it]").getString(0) }
            if (done(value)) return value
            Thread.sleep(200)
        }
        return value
    }
}
