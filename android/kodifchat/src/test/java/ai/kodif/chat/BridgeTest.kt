package ai.kodif.chat

import ai.kodif.chat.Bridge.Incoming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    @Test
    fun parsesEveryWidgetMessage() {
        assertEquals(Incoming.RequestToken("5f0c"), Bridge.parse("""{"v":1,"type":"requestToken","id":"5f0c"}"""))
        assertEquals(Incoming.Close, Bridge.parse("""{"v":1,"type":"close"}"""))
        assertEquals(Incoming.Reply, Bridge.parse("""{"v":1,"type":"reply"}"""))
        assertEquals(Incoming.OpenSettings, Bridge.parse("""{"v":1,"type":"openSettings"}"""))
        assertEquals(Incoming.Unavailable, Bridge.parse("""{"v":1,"type":"unavailable","extra":true}"""))
    }

    @Test
    fun ignoresUnknownAndMalformedMessages() {
        listOf(
            """{"v":1,"type":"somethingNew"}""", """{"v":1,"type":"ready"}""", """{"v":1,"type":"openUrl","url":"x"}""",
            """{"v":2,"type":"close"}""", """{"v":"1","type":"close"}""", """{"type":"close"}""",
            """{"v":1,"type":"requestToken"}""", """{"v":1,"type":"requestToken","id":7}""",
            "", "not json", "[1,2]", null,
        ).forEach { assertNull(it, Bridge.parse(it)) }
    }

    @Test
    fun encodesNativeMessages() {
        assertJson("""{"v":1,"type":"token","id":"5f0c","jwt":"eyJ.x.y"}""", Bridge.token("5f0c", "eyJ.x.y"))
        assertJson("""{"v":1,"type":"token","id":"5f0c","jwt":null}""", Bridge.token("5f0c", null))
        assertJson("""{"v":1,"type":"appState","state":"foreground"}""", Bridge.appState(true))
        assertJson("""{"v":1,"type":"appState","state":"background"}""", Bridge.appState(false))
        assertJson("""{"v":1,"type":"customContext","context":null}""", Bridge.customContext(null))
    }

    @Test
    fun encodesCustomContextDroppingKeysThatArentJson() {
        val json = Bridge.customContext(
            mapOf("screen" to "product", "productId" to 42, "vip" to true, "tags" to listOf("a", "b"), "nan" to Double.NaN),
        )
        assertJson(
            """{"v":1,"type":"customContext","context":{"screen":"product","productId":42,"vip":true,"tags":["a","b"]}}""",
            json,
        )
    }

    @Test
    fun buildsThePage() {
        val html = Bridge.html(KodifChat.Environment.Production.bundleUrl, "int_123", microphone = true, language = "fr")
        // The buffer comes first, so native can send before the widget starts.
        assertTrue(
            html.substringAfter("<script>\n").trimStart()
                .startsWith("window.KodifChatNative = { pending: [], receive: function (m) { this.pending.push(m) } };"),
        )
        assertEquals("https://js.shippedsuite.com/api/chat-widget.js", bundleSource(html))
        assertTrue(html.contains("bundle.type = 'module';"))
        assertTrue(html.contains("document.head.appendChild(bundle);"))
        assertTrue(html.contains("viewport-fit=cover"))
        assertJson(
            """{"integrationId":"int_123","host":"android","hostCapabilities":{"microphone":true},"language":"fr"}""",
            optionsIn(html),
        )
    }

    @Test
    fun reportsABundleThatFailsToLoad() {
        val html = Bridge.html("https://x/b.js?a=1&b=2", "i", microphone = false, language = null)
        assertEquals("https://x/b.js?a=1&b=2", bundleSource(html))
        val handler = html.substringAfter("bundle.onerror = function () {")
        assertTrue(handler.contains("var m = JSON.stringify({ v: 1, type: 'unavailable' });"))
        assertTrue(handler.contains("window.webkit.messageHandlers.kodifChat"))
        assertTrue(handler.contains("window.kodifChat.postMessage(m)"))
        // Set before the script is inserted, so a failure can't be missed.
        assertTrue(html.indexOf("bundle.onerror") < html.indexOf("document.head.appendChild(bundle)"))
    }

    @Test
    fun valuesCannotCloseTheScriptTag() {
        val html = Bridge.html("https://x/b.js#</script>", "</script><!--", microphone = false, language = null)
        assertEquals(1, Regex("</script", RegexOption.IGNORE_CASE).findAll(html).count())
        assertFalse(html.contains("<!--"))
        assertEquals("</script><!--", JSONObject(optionsIn(html)).getString("integrationId"))
        assertEquals("https://x/b.js#</script>", bundleSource(html))
    }

    @Test
    fun environmentBundles() {
        assertEquals("https://js-staging.shippedsuite.com/api/chat-widget.js", KodifChat.Environment.Staging.bundleUrl)
        assertEquals("https://js.dev.invisiblecommerce.com/api/chat-widget.js", KodifChat.Environment.Development.bundleUrl)
        assertEquals("https://example.test/stub.js", KodifChat.Environment.Custom("https://example.test/stub.js").bundleUrl)
    }

    /** The bundle URL the page's script loads (a JSON string). */
    private fun bundleSource(html: String): String =
        JSONArray("[" + html.substringAfter("bundle.src = ").substringBefore(";\n") + "]").getString(0)

    private fun optionsIn(html: String) = html.substringAfter("new window.ShippedAI.ChatWidget(").substringBefore(")\n")

    /** Same JSON, whatever the key order. */
    private fun assertJson(expected: String, actual: String) =
        assertEquals(JSONObject(expected).toMap(), JSONObject(actual).toMap())
}
