package ai.kodif.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Builds the page that hosts the widget (PROTOCOL.md, "The page"). */
internal object PageBuilder {
    data class Options(
        val integrationId: String,
        val microphone: Boolean,
        val language: String? = null,
    )

    /**
     * Posted straight to the native handler when the bundle fails to load, so native treats it like the widget's
     * own `unavailable` (the widget, and so its bridge, never started). iOS's handler, else Android's.
     */
    const val BUNDLE_ERROR_HANDLER: String =
        "(function(){var m=JSON.stringify({v:1,type:'unavailable'});" +
            "var w=window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.kodifChat;" +
            "if(w){w.postMessage(m)}else if(window.kodifChat){window.kodifChat.postMessage(m)}})()"

    /**
     * The widget options, as JSON that is safe inside a `<script>`. Custom context isn't one: native sends it as a
     * `customContext` message after `ready`, so later changes replace it rather than merge into it.
     */
    fun optionsJson(options: Options): String {
        val fields = linkedMapOf<String, JsonElement>(
            "integrationId" to JsonPrimitive(options.integrationId),
            "host" to JsonPrimitive("android"),
            "hostCapabilities" to JsonObject(mapOf("microphone" to JsonPrimitive(options.microphone))),
        )
        options.language?.let { fields["language"] = JsonPrimitive(it) }
        return JsonEmbedding.encode(JsonObject(fields))
    }

    fun html(bundleUrl: String, options: Options): String = """
        |<!doctype html>
        |<html>
        |<head>
        |  <meta charset="utf-8">
        |  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
        |  <script>
        |    window.addEventListener('shipped:chat-script-loaded', function () {
        |      new window.ShippedAI.ChatWidget(${optionsJson(options)})
        |    })
        |  </script>
        |  <script type="module" src="${escapeAttribute(bundleUrl)}" onerror="${escapeAttribute(BUNDLE_ERROR_HANDLER)}"></script>
        |</head>
        |<body></body>
        |</html>
        |
    """.trimMargin()

    private fun escapeAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
