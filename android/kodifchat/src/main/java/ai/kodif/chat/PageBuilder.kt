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
        val customContext: Map<String, Any?>? = null,
        val host: String = "android",
    )

    /** The widget options, as JSON that is safe inside a `<script>`. */
    fun optionsJson(options: Options): String {
        val fields = linkedMapOf<String, JsonElement>(
            "integrationId" to JsonPrimitive(options.integrationId),
            "host" to JsonPrimitive(options.host),
            "hostCapabilities" to JsonObject(mapOf("microphone" to JsonPrimitive(options.microphone))),
        )
        options.language?.let { fields["language"] = JsonPrimitive(it) }
        options.customContext?.let { fields["customContext"] = JsonEmbedding.toJsonElement(it) }
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
        |  <script type="module" src="${escapeAttribute(bundleUrl)}"></script>
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
