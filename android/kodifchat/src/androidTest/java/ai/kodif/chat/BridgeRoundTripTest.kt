package ai.kodif.chat

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Loads the real page (asset loader origin, generated HTML, module script) with stub widget bundles and checks the
 * bridge both ways, link interception, unread counting and a bundle that fails to load.
 */
@RunWith(AndroidJUnit4::class)
class BridgeRoundTripTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Plays the widget: takes over KodifChatNative, replays what the page buffered, asks for a token, posts a reply. */
    private val stubWidget = """
        window.ShippedAI = { ChatWidget: function (options) {
          window.__options = options;
          window.__received = window.KodifChatNative.pending.slice();
          window.KodifChatNative = { receive: function (m) { window.__received.push(m); } };
          var post = function (m) { window.kodifChat.postMessage(JSON.stringify(m)); };
          window.__click = function (href, target) {
            var a = document.createElement('a');
            a.href = href;
            if (target) a.target = target;
            document.body.appendChild(a);
            a.click();
          };
          post({ v: 1, type: 'requestToken', id: '8d5e2c1a' });
          post({ v: 1, type: 'reply' });
        } };
        window.dispatchEvent(new Event('shipped:chat-script-loaded'));
    """.trimIndent()

    private val stubBundleUrl = "data:text/javascript;base64," + Base64.encodeToString(stubWidget.toByteArray(), Base64.NO_WRAP)

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            KodifChat.discardSession()
            KodifChat.tokenProvider = null
            KodifChat.listener = null
            KodifChat.customContext = null
            KodifChat.unreadCount = 0
        }
    }

    /** Configures [bundleUrl], preloads, and returns the messages the widget posts. */
    private fun start(bundleUrl: String, listener: KodifChatListener? = null): LinkedBlockingQueue<Bridge.Incoming> {
        val incoming = LinkedBlockingQueue<Bridge.Incoming>()
        instrumentation.runOnMainSync {
            KodifChat.configure(instrumentation.targetContext, "test-integration", KodifChat.Environment.Custom(bundleUrl))
            KodifChat.listener = listener
            KodifChat.requireSession().apply { onIncoming = { incoming += it } }.preload()
        }
        return incoming
    }

    @Test
    fun tokenRoundTripStateAndUnreadCount() {
        val unreadCounts = LinkedBlockingQueue<Int>()
        instrumentation.runOnMainSync {
            KodifChat.tokenProvider = TokenProvider { callback -> Thread { callback("test-jwt") }.start() }
            KodifChat.customContext = mapOf("screen" to "cart")
        }
        val incoming = start(stubBundleUrl, object : KodifChatListener {
            override fun onUnreadCountChanged(count: Int) {
                unreadCounts += count
            }
        })
        assertEquals(Bridge.Incoming.RequestToken("8d5e2c1a"), incoming.poll(30, TimeUnit.SECONDS))
        assertEquals(Bridge.Incoming.Reply, incoming.poll(10, TimeUnit.SECONDS))
        // Not on screen, so the reply counts as unread.
        assertEquals(1, unreadCounts.poll(10, TimeUnit.SECONDS))

        val received = poll("JSON.stringify(window.__received || [])") { it.contains("\"token\"") && it.contains("\"customContext\"") }
        val messages = JSONArray(received).let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        val token = messages.first { it.getString("type") == "token" }
        assertEquals("8d5e2c1a", token.getString("id"))
        assertEquals("test-jwt", token.getString("jwt"))
        assertTrue(received, messages.any { it.getString("type") == "appState" && it.getString("state") == "foreground" })
        assertEquals("cart", messages.first { it.getString("type") == "customContext" }.getJSONObject("context").getString("screen"))

        assertEquals("true", evaluate("window.isSecureContext"))
        assertEquals("\"android\"", evaluate("window.__options.host"))
        assertEquals("\"https://appassets.androidplatform.net\"", evaluate("location.origin"))
    }

    /** Links leave the page only through navigation interception: a plain link and a target=_blank one. */
    @Test
    fun linksReachTheOpenUrlPath() {
        val opened = LinkedBlockingQueue<String>()
        val incoming = start(stubBundleUrl, object : KodifChatListener {
            override fun onOpenUrl(url: String): Boolean {
                opened += url
                return true
            }
        })
        assertTrue(incoming.poll(30, TimeUnit.SECONDS) is Bridge.Incoming.RequestToken)
        evaluate("__click('https://example.com/plain')")
        assertEquals("https://example.com/plain", opened.poll(10, TimeUnit.SECONDS))
        evaluate("__click('https://example.com/new-window', '_blank')")
        assertEquals("https://example.com/new-window", opened.poll(10, TimeUnit.SECONDS))
        assertEquals("\"https://appassets.androidplatform.net/kodif-chat/index.html\"", evaluate("location.href"))
    }

    @Test
    fun aBundleThatFailsToLoadIsReportedAndReloadedOnTheNextPreload() {
        val unavailable = LinkedBlockingQueue<Unit>()
        // .invalid never resolves, so the module script fails to load and its onerror reports it.
        start("https://kodif-chat.invalid/chat-widget.js", object : KodifChatListener {
            override fun onUnavailable() {
                unavailable += Unit
            }
        })
        assertTrue("the failed load wasn't reported", unavailable.poll(30, TimeUnit.SECONDS) != null)
        instrumentation.runOnMainSync { KodifChat.requireSession().preload() }
        assertTrue("the next preload didn't reload", unavailable.poll(30, TimeUnit.SECONDS) != null)
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

    private fun poll(script: String, done: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 15_000
        var value = ""
        while (System.currentTimeMillis() < deadline) {
            // evaluateJavascript returns the string JSON-encoded; decode one level.
            value = JSONArray("[${evaluate(script)}]").getString(0)
            if (done(value)) return value
            Thread.sleep(200)
        }
        return value
    }
}
