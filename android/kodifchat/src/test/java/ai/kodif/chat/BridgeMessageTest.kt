package ai.kodif.chat

import ai.kodif.chat.BridgeMessage.Incoming
import ai.kodif.chat.BridgeMessage.Outgoing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeMessageTest {
    @Test
    fun parsesEveryWidgetMessage() {
        assertEquals(Incoming.Ready, Incoming.parse("""{"v":1,"type":"ready"}"""))
        assertEquals(Incoming.RequestToken("abc"), Incoming.parse("""{"v":1,"type":"requestToken","id":"abc"}"""))
        assertEquals(Incoming.Close, Incoming.parse("""{"v":1,"type":"close"}"""))
        assertEquals(
            Incoming.OpenUrl("https://example.com/p?a=1"),
            Incoming.parse("""{"v":1,"type":"openUrl","url":"https://example.com/p?a=1"}"""),
        )
        assertEquals(
            Incoming.Message("Your order shipped", KodifChat.Author.AI),
            Incoming.parse("""{"v":1,"type":"message","preview":"Your order shipped","author":"ai"}"""),
        )
        assertEquals(
            Incoming.Message("Hi, I'm Sam", KodifChat.Author.AGENT),
            Incoming.parse("""{"v":1,"type":"message","preview":"Hi, I'm Sam","author":"agent"}"""),
        )
        assertEquals(Incoming.OpenSettings, Incoming.parse("""{"v":1,"type":"openSettings"}"""))
        assertEquals(Incoming.Unavailable, Incoming.parse("""{"v":1,"type":"unavailable"}"""))
    }

    @Test
    fun ignoresExtraFields() {
        assertEquals(Incoming.Close, Incoming.parse("""{"v":1,"type":"close","reason":"later"}"""))
    }

    @Test
    fun ignoresUnknownTypesAndVersions() {
        assertNull(Incoming.parse("""{"v":1,"type":"somethingNew"}"""))
        assertNull(Incoming.parse("""{"v":2,"type":"ready"}"""))
        assertNull(Incoming.parse("""{"v":"1","type":"ready"}"""))
        assertNull(Incoming.parse("""{"type":"ready"}"""))
        assertNull(Incoming.parse("""{"v":1}"""))
    }

    @Test
    fun ignoresMalformedMessages() {
        assertNull(Incoming.parse(null))
        assertNull(Incoming.parse(""))
        assertNull(Incoming.parse("not json"))
        assertNull(Incoming.parse("""[1,2]"""))
        assertNull(Incoming.parse(""""ready""""))
        assertNull(Incoming.parse("""{"v":1,"type":"requestToken"}"""))
        assertNull(Incoming.parse("""{"v":1,"type":"requestToken","id":7}"""))
        assertNull(Incoming.parse("""{"v":1,"type":"openUrl"}"""))
        assertNull(Incoming.parse("""{"v":1,"type":"message","preview":"x","author":"robot"}"""))
        assertNull(Incoming.parse("""{"v":1,"type":"message","author":"ai"}"""))
    }

    @Test
    fun encodesTokenAnswer() {
        assertEquals(
            """{"v":1,"type":"token","id":"abc","jwt":"eyJ.x.y"}""",
            Outgoing.Token("abc", "eyJ.x.y").toJson(),
        )
    }

    @Test
    fun encodesAnonymousTokenWithExplicitNull() {
        assertEquals("""{"v":1,"type":"token","id":"abc","jwt":null}""", Outgoing.Token("abc", null).toJson())
    }

    @Test
    fun encodesLoginAndLogoutWithoutId() {
        assertEquals("""{"v":1,"type":"token","jwt":"eyJ.x.y"}""", Outgoing.Token(null, "eyJ.x.y").toJson())
        assertEquals("""{"v":1,"type":"token","jwt":null}""", Outgoing.Token(null, null).toJson())
    }

    @Test
    fun encodesVisibility() {
        assertEquals("""{"v":1,"type":"visibility","visible":true}""", Outgoing.Visibility(true).toJson())
        assertEquals("""{"v":1,"type":"visibility","visible":false}""", Outgoing.Visibility(false).toJson())
    }

    @Test
    fun encodesAppState() {
        assertEquals("""{"v":1,"type":"appState","state":"foreground"}""", Outgoing.AppState(true).toJson())
        assertEquals("""{"v":1,"type":"appState","state":"background"}""", Outgoing.AppState(false).toJson())
    }

    @Test
    fun encodesCustomContext() {
        val json = Outgoing.CustomContext(
            mapOf(
                "screen" to "product",
                "productId" to 42,
                "price" to 9.5,
                "vip" to true,
                "tags" to listOf("a", "b"),
                "nested" to mapOf("x" to null),
            ),
        ).toJson()
        assertEquals(
            """{"v":1,"type":"customContext","context":{"screen":"product","productId":42,"price":9.5,"vip":true,""" +
                """"tags":["a","b"],"nested":{"x":null}}}""",
            json,
        )
        assertEquals("""{"v":1,"type":"customContext","context":null}""", Outgoing.CustomContext(null).toJson())
    }

    @Test
    fun escapesScriptBreakingCharacters() {
        val json = Outgoing.Token(null, "</script><!--   ").toJson()
        assertFalse(json.contains("<"))
        assertFalse(json.contains(" "))
        // Still the same JSON.
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("</script><!--   ", (parsed["jwt"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun outgoingMessagesAreVersioned() {
        listOf(
            Outgoing.Token("a", null),
            Outgoing.Visibility(true),
            Outgoing.AppState(false),
            Outgoing.CustomContext(emptyMap()),
        ).forEach { message ->
            val obj = Json.parseToJsonElement(message.toJson()) as JsonObject
            assertEquals("1", obj["v"].toString())
            assertTrue(obj.containsKey("type"))
        }
    }
}
