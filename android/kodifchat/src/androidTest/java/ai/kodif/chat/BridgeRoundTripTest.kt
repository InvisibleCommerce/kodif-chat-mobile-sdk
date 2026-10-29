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
        assertTrue(received, received.startsWith("[{\"v\":1,\"type\":\"appState\""))

        assertEquals("true", evaluate("window.isSecureContext"))
        assertEquals("\"android\"", evaluate("window.__options.host"))
        assertEquals("\"https://appassets.androidplatform.net\"", evaluate("location.origin"))
    }

    /** Links leave the page only through navigation interception: a plain link and a target=_blank one. */
    @Test
    fun linksReachTheOpenUrlPath() {
        val stub = """
            window.ShippedAI = { ChatWidget: function () {
              window.KodifChatNative = { receive: function () {} };
              window.__click = function (href, target) {
                var a = document.createElement('a');
                a.href = href;
                if (target) a.target = target;
                document.body.appendChild(a);
                a.click();
              };
              window.kodifChat.postMessage(JSON.stringify({ v: 1, type: 'ready' }));
            } };
            window.dispatchEvent(new Event('shipped:chat-script-loaded'));
        """.trimIndent()
        val bundleUrl = "data:text/javascript;base64," + Base64.encodeToString(stub.toByteArray(), Base64.NO_WRAP)
        val opened = java.util.concurrent.LinkedBlockingQueue<String>()
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            KodifChat.configure(instrumentation.targetContext, "test-integration", KodifChat.Environment.Custom(bundleUrl))
            KodifChat.listener = object : KodifChatListener {
                override fun onOpenUrl(url: String): Boolean {
                    opened += url
                    return true
                }
            }
            val session = KodifChat.requireSession()
            session.onIncoming = { if (it == BridgeMessage.Incoming.Ready) ready.countDown() }
            session.preload()
        }
        try {
            assertTrue("the stub never got ready", ready.await(30, TimeUnit.SECONDS))
            evaluate("__click('https://example.com/plain')")
            assertEquals("https://example.com/plain", opened.poll(10, TimeUnit.SECONDS))
            evaluate("__click('https://example.com/new-window', '_blank')")
            assertEquals("https://example.com/new-window", opened.poll(10, TimeUnit.SECONDS))
            // The page stayed.
            assertEquals("\"https://appassets.androidplatform.net/kodif-chat/index.html\"", evaluate("location.href"))
        } finally {
            instrumentation.runOnMainSync { KodifChat.listener = null }
        }
    }

    @Test
    fun aBundleThatFailsToLoadIsReportedAndReloadedOnTheNextPreload() {
        // .invalid never resolves, so the module script fails to load.
        val missingBundle = "https://kodif-chat.invalid/chat-widget.js"
        val unavailable = java.util.concurrent.LinkedBlockingQueue<Unit>()
        instrumentation.runOnMainSync {
            KodifChat.configure(instrumentation.targetContext, "test-integration", KodifChat.Environment.Custom(missingBundle))
            KodifChat.listener = object : KodifChatListener {
                override fun onUnavailable() {
                    unavailable += Unit
                }
            }
            KodifChat.preload()
        }
        try {
            assertTrue("the failed load wasn't reported", unavailable.poll(30, TimeUnit.SECONDS) != null)
            // The next preload loads the page again, and fails again.
            instrumentation.runOnMainSync { KodifChat.preload() }
            assertTrue("the next preload didn't reload", unavailable.poll(30, TimeUnit.SECONDS) != null)
        } finally {
            instrumentation.runOnMainSync { KodifChat.listener = null }
        }
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
