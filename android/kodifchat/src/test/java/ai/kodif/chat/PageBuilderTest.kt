package ai.kodif.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBuilderTest {
    private fun optionsIn(html: String): JsonObject {
        val start = html.indexOf("new window.ShippedAI.ChatWidget(") + "new window.ShippedAI.ChatWidget(".length
        val end = html.indexOf(")\n", start)
        return Json.parseToJsonElement(html.substring(start, end)).jsonObject
    }

    @Test
    fun buildsTheProtocolPage() {
        val html = PageBuilder.html(
            KodifChat.Environment.Production.bundleUrl,
            PageBuilder.Options(integrationId = "int_123", microphone = false),
        )
        assertTrue(html.startsWith("<!doctype html>\n<html>\n<head>\n  <meta charset=\"utf-8\">"))
        assertTrue(
            html.contains(
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover\">",
            ),
        )
        assertTrue(html.contains("window.addEventListener('shipped:chat-script-loaded', function () {"))
        assertTrue(html.contains("<script type=\"module\" src=\"https://js.shippedsuite.com/api/chat-widget.js\" onerror=\""))
        assertTrue(html.contains("<body></body>"))
    }

    @Test
    fun encodesRequiredOptions() {
        val html = PageBuilder.html("https://x/b.js", PageBuilder.Options(integrationId = "int_123", microphone = true))
        val options = optionsIn(html)
        assertEquals("int_123", options["integrationId"]!!.jsonPrimitive.content)
        assertEquals("android", options["host"]!!.jsonPrimitive.content)
        assertTrue(options["hostCapabilities"]!!.jsonObject["microphone"]!!.jsonPrimitive.boolean)
        assertFalse(options.containsKey("language"))
        assertFalse(options.containsKey("customContext"))
    }

    @Test
    fun encodesOptionalOptions() {
        val html = PageBuilder.html(
            "https://x/b.js",
            PageBuilder.Options(
                integrationId = "int_123",
                microphone = false,
                language = "fr",
            ),
        )
        val options = optionsIn(html)
        assertFalse(options["hostCapabilities"]!!.jsonObject["microphone"]!!.jsonPrimitive.boolean)
        assertEquals("fr", options["language"]!!.jsonPrimitive.content)
        assertEquals(setOf("integrationId", "host", "hostCapabilities", "language"), options.keys)
    }

    @Test
    fun reportsABundleThatFailsToLoadAsUnavailable() {
        val html = PageBuilder.html("https://x/b.js", PageBuilder.Options(integrationId = "i", microphone = false))
        val tag = Regex("<script type=\"module\"[^>]*>").find(html)!!.value
        val handler = Regex("onerror=\"([^\"]*)\"").find(tag)!!.groupValues[1]
            .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        assertEquals(PageBuilder.BUNDLE_ERROR_HANDLER, handler)
        // Posts the protocol's unavailable message, to iOS's handler or else Android's.
        assertTrue(handler.contains("JSON.stringify({v:1,type:'unavailable'})"))
        assertTrue(handler.contains("window.webkit.messageHandlers.kodifChat"))
        assertTrue(handler.contains("window.kodifChat.postMessage(m)"))
        // The attribute value is escaped, so its && can't be read as character references.
        assertFalse(tag.contains("&&"))
    }

    @Test
    fun valuesCannotBreakOutOfTheScriptTag() {
        val html = PageBuilder.html(
            "https://x/b.js",
            PageBuilder.Options(
                integrationId = "</script><script>alert(1)</script>",
                microphone = false,
                language = "<!-- </SCRIPT>",
            ),
        )
        // The page's own two closing tags only.
        assertEquals(2, Regex("</script", RegexOption.IGNORE_CASE).findAll(html).count())
        assertFalse(html.contains("<!--"))
        val options = optionsIn(html)
        assertEquals("</script><script>alert(1)</script>", options["integrationId"]!!.jsonPrimitive.content)
        assertEquals("<!-- </SCRIPT>", options["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun usesEachEnvironmentsBundle() {
        assertEquals("https://js.shippedsuite.com/api/chat-widget.js", KodifChat.Environment.Production.bundleUrl)
        assertEquals("https://js-staging.shippedsuite.com/api/chat-widget.js", KodifChat.Environment.Staging.bundleUrl)
        assertEquals("https://js.dev.invisiblecommerce.com/api/chat-widget.js", KodifChat.Environment.Development.bundleUrl)
        assertEquals("https://example.test/stub.js", KodifChat.Environment.Custom("https://example.test/stub.js").bundleUrl)
    }

    @Test
    fun escapesTheBundleUrlAttribute() {
        val html = PageBuilder.html(
            "https://example.test/b.js?a=1&b=\"><script>",
            PageBuilder.Options(integrationId = "i", microphone = false),
        )
        assertTrue(html.contains("src=\"https://example.test/b.js?a=1&amp;b=&quot;&gt;&lt;script&gt;\" onerror="))
    }
}
