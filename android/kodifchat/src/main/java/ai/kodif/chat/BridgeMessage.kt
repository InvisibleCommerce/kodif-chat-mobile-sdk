package ai.kodif.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The messages of the bridge protocol (PROTOCOL.md), v1. */
internal object BridgeMessage {
    const val VERSION: Int = 1

    /** Widget → native. */
    sealed class Incoming {
        data object Ready : Incoming()
        data class RequestToken(val id: String) : Incoming()
        data object Close : Incoming()
        data class Message(val preview: String, val author: KodifChat.Author) : Incoming()
        data object OpenSettings : Incoming()
        data object Unavailable : Incoming()

        companion object {
            /**
             * Parses a message the widget posted. Returns null for anything native must ignore: malformed JSON, a
             * `v` other than [VERSION], an unknown `type`, or a known type missing its fields.
             */
            fun parse(json: String?): Incoming? {
                if (json == null) return null
                val obj = try {
                    Json.parseToJsonElement(json) as? JsonObject
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return null
                val version = obj["v"] as? JsonPrimitive
                if (version == null || version.isString || version.intOrNull != VERSION) return null
                return when (obj.string("type")) {
                    "ready" -> Ready
                    "requestToken" -> obj.string("id")?.let { RequestToken(it) }
                    "close" -> Close
                    "message" -> {
                        val preview = obj.string("preview")
                        val author = KodifChat.Author.fromWire(obj.string("author"))
                        if (preview != null && author != null) Message(preview, author) else null
                    }
                    "openSettings" -> OpenSettings
                    "unavailable" -> Unavailable
                    else -> null
                }
            }

            private fun JsonObject.string(key: String): String? =
                (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
    }

    /** Native → widget. */
    sealed class Outgoing {
        protected abstract val type: String
        protected abstract fun fields(): Map<String, JsonElement>

        /** The message as JSON that is safe to embed in a script (see [JsonEmbedding]). */
        fun toJson(): String = JsonEmbedding.encode(
            JsonObject(linkedMapOf<String, JsonElement>("v" to JsonPrimitive(VERSION), "type" to JsonPrimitive(type)) + fields()),
        )

        /** Answers `requestToken` (with its [id]), or pushes a login / logout when [id] is null. */
        data class Token(val id: String?, val jwt: String?) : Outgoing() {
            override val type: String get() = "token"
            override fun fields(): Map<String, JsonElement> = buildJsonObject {
                if (id != null) put("id", id)
                put("jwt", jwt)
            }
        }

        data class AppState(val foreground: Boolean) : Outgoing() {
            override val type: String get() = "appState"
            override fun fields(): Map<String, JsonElement> =
                mapOf("state" to JsonPrimitive(if (foreground) "foreground" else "background"))
        }

        data class CustomContext(val context: Map<String, Any?>?) : Outgoing() {
            override val type: String get() = "customContext"
            override fun fields(): Map<String, JsonElement> =
                mapOf("context" to JsonEmbedding.toJsonElement(context))
        }
    }
}

/** Turns app values into JSON that can be embedded in a `<script>` or passed to `evaluateJavascript`. */
internal object JsonEmbedding {
    fun encode(element: JsonElement): String = escapeForScript(element.toString())

    /**
     * `<` only occurs inside JSON strings, so writing it as `<` keeps the JSON identical while making sure a
     * value can't close the script tag (`</script>`) or open an HTML comment. U+2028 / U+2029 are escaped for older
     * JavaScript engines, where they end a line inside a string literal.
     */
    fun escapeForScript(json: String): String = json
        .replace("<", "\\u003c")
        .replace(" ", "\\u2028")
        .replace(" ", "\\u2029")

    /** Converts JSON-compatible app values (strings, numbers, booleans, null, maps, lists, arrays) to JSON. */
    fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
        is Float -> if (value.isFinite()) JsonPrimitive(value) else JsonNull
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJsonElement(v) })
        is Iterable<*> -> JsonArray(value.map { toJsonElement(it) })
        is Array<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
