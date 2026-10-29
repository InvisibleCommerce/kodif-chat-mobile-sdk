package ai.kodif.chat

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/** The bridge protocol (PROTOCOL.md), v1, and the page that hosts the widget. */
internal object Bridge {
    const val ASSET_HOST = "appassets.androidplatform.net"
    const val ORIGIN = "https://$ASSET_HOST"
    const val PAGE_URL = "$ORIGIN/kodif-chat/index.html"

    /** Widget → native. */
    sealed interface Incoming {
        data class RequestToken(val id: String) : Incoming
        data object Close : Incoming
        data object Reply : Incoming
        data object OpenSettings : Incoming
        data object Unavailable : Incoming
    }

    /** Null for anything native ignores: malformed JSON, another `v`, an unknown type, a missing field. */
    fun parse(json: String?): Incoming? {
        val message = try {
            JSONObject(json ?: return null)
        } catch (_: JSONException) {
            return null
        }
        if (message.opt("v") != 1) return null
        return when (message.optString("type")) {
            "requestToken" -> (message.opt("id") as? String)?.let { Incoming.RequestToken(it) }
            "close" -> Incoming.Close
            "reply" -> Incoming.Reply
            "openSettings" -> Incoming.OpenSettings
            "unavailable" -> Incoming.Unavailable
            else -> null
        }
    }

    // Native → widget.

    fun token(id: String, jwt: String?): String = message("token", "id" to id, "jwt" to jwt)

    fun appState(foreground: Boolean): String =
        message("appState", "state" to if (foreground) "foreground" else "background")

    /** Keys whose values aren't valid JSON are dropped. */
    fun customContext(context: Map<String, Any?>?): String {
        val json = context?.let { map ->
            JSONObject().apply {
                for ((key, value) in map) {
                    try {
                        put(key, JSONObject.wrap(value) ?: throw JSONException("not a JSON value"))
                    } catch (e: JSONException) {
                        Log.w("KodifChat", "Dropped customContext[\"$key\"]: ${e.message}")
                    }
                }
            }
        }
        return message("customContext", "context" to json)
    }

    private fun message(type: String, vararg fields: Pair<String, Any?>): String {
        val message = JSONObject().put("v", 1).put("type", type)
        for ((key, value) in fields) message.put(key, value ?: JSONObject.NULL)
        return message.toString()
    }

    // The page.

    fun html(bundleUrl: String, integrationId: String, microphone: Boolean, language: String?): String {
        val options = JSONObject()
            .put("integrationId", integrationId)
            .put("host", "android")
            .put("hostCapabilities", JSONObject().put("microphone", microphone))
        if (language != null) options.put("language", language)
        return """
            |<!doctype html>
            |<html>
            |<head>
            |  <meta charset="utf-8">
            |  <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover">
            |  <script>
            |    window.KodifChatNative = { pending: [], receive: function (m) { this.pending.push(m) } };
            |    window.addEventListener('shipped:chat-script-loaded', function () {
            |      new window.ShippedAI.ChatWidget(${scriptJson(options.toString())})
            |    });
            |    var bundle = document.createElement('script');
            |    bundle.type = 'module';
            |    bundle.src = ${scriptJson(JSONObject.quote(bundleUrl))};
            |    // A bundle that fails to load never starts the widget, so the page reports it. (WKWebView fired no
            |    // error for a failed module script written in the HTML; one inserted like this does.)
            |    bundle.onerror = function () {
            |      var m = JSON.stringify({ v: 1, type: 'unavailable' });
            |      var w = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.kodifChat;
            |      if (w) { w.postMessage(m) } else if (window.kodifChat) { window.kodifChat.postMessage(m) }
            |    };
            |    document.head.appendChild(bundle);
            |  </script>
            |</head>
            |<body></body>
            |</html>
            |
        """.trimMargin()
    }

    /** JSON inside a `<script>`: `<` only occurs inside JSON strings, so escaping it keeps a value from closing the tag. */
    private fun scriptJson(json: String): String = json.replace("<", "\\u003c")
}
