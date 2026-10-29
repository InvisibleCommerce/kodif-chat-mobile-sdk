package ai.kodif.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The Kodif chat. Call [configure] once (e.g. in `Application.onCreate`), then [open] the chat, or embed a
 * [KodifChatFragment].
 */
public object KodifChat {
    /** Where the chat widget is loaded from. */
    public sealed class Environment {
        public abstract val bundleUrl: String

        public data object Production : Environment() {
            override val bundleUrl: String get() = "https://js.shippedsuite.com/api/chat-widget.js"
        }

        public data object Staging : Environment() {
            override val bundleUrl: String get() = "https://js-staging.shippedsuite.com/api/chat-widget.js"
        }

        public data object Development : Environment() {
            override val bundleUrl: String get() = "https://js.dev.invisiblecommerce.com/api/chat-widget.js"
        }

        /** Any bundle URL, for tests. */
        public data class Custom(override val bundleUrl: String) : Environment()
    }

    /** Who wrote a message. */
    public enum class Author(internal val wire: String) {
        AI("ai"),
        AGENT("agent"),
        ;

        internal companion object {
            fun fromWire(value: String?): Author? = entries.firstOrNull { it.wire == value }
        }
    }

    internal data class Configuration(val integrationId: String, val environment: Environment)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    internal var configuration: Configuration? = null
        private set
    // One shared WebView is the point: it lives on the application context (see KodifChatSession) and only borrows
    // an activity's context while that activity shows it.
    @SuppressLint("StaticFieldLeak")
    internal var session: KodifChatSession? = null
        private set
    internal var isForeground: Boolean = true
        private set
    private var lifecycleObserverAdded = false

    /**
     * Supplies the shopper's JWT, fetched from your backend, when the chat asks for one: when it starts and when a
     * token expires. Null: the shopper is anonymous. See [TokenProvider.fetchToken].
     */
    @JvmStatic
    public var tokenProvider: TokenProvider? = null

    /** Receives the chat's events. Held strongly: clear it when its owner goes away. */
    @JvmStatic
    public var listener: KodifChatListener? = null

    /**
     * Offers voice mode. Your app must declare `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`; the SDK asks for the
     * microphone permission when the shopper starts a call. Set it before [preload] / [open].
     */
    @JvmStatic
    public var voiceEnabled: Boolean = false

    /** The chat's locale (e.g. `fr`). Null: the widget detects it. Set it before [preload] / [open]. */
    @JvmStatic
    public var language: String? = null

    /**
     * Key/values attached to the conversation (screen, product, app version). Values must be JSON-compatible:
     * strings, numbers, booleans, null, maps and lists. Setting it replaces the running chat's context (null clears
     * it); it's sent to the chat each time the chat starts.
     */
    @JvmStatic
    public var customContext: Map<String, Any?>? = null
        set(value) {
            field = value
            onMain { session?.send(BridgeMessage.Outgoing.CustomContext(value)) }
        }

    /**
     * Sets up the chat for [integrationId], the id of your web chat channel's integration. Calling it again with a
     * different id or environment discards the running chat.
     */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, integrationId: String, environment: Environment = Environment.Production) {
        val applicationContext = context.applicationContext
        onMain {
            appContext = applicationContext
            val newConfiguration = Configuration(integrationId, environment)
            if (configuration != newConfiguration) {
                session?.destroy()
                session = null
                configuration = newConfiguration
            }
            observeAppState()
        }
    }

    /**
     * Tells a running chat the shopper signed in or out. A JWT signs the chat in with that token directly; null
     * starts a fresh anonymous shopper (the previous shopper's conversations are no longer reachable on this device).
     * The [tokenProvider] still answers the chat's own requests (at start, on expiry), so keep it in step too.
     */
    @JvmStatic
    public fun setShopperToken(jwt: String?) {
        onMain { session?.send(BridgeMessage.Outgoing.Token(id = null, jwt = jwt)) }
    }

    /** Starts the chat in the background so [open] shows it instantly. */
    @JvmStatic
    public fun preload() {
        onMain { requireSession().preload() }
    }

    /** Opens the chat full screen in [KodifChatActivity]. */
    @JvmStatic
    public fun open(context: Context) {
        requireNotNull(configuration) { "Call KodifChat.configure(...) before KodifChat.open(...)" }
        val intent = Intent(context, KodifChatActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Tests: discard the running chat. */
    internal fun discardSession() {
        session?.destroy()
        session = null
    }

    internal fun requireSession(): KodifChatSession {
        session?.let { return it }
        val context = checkNotNull(appContext) { "Call KodifChat.configure(...) first" }
        val config = checkNotNull(configuration) { "Call KodifChat.configure(...) first" }
        return KodifChatSession(context, config).also { session = it }
    }

    private fun observeAppState() {
        if (lifecycleObserverAdded) return
        lifecycleObserverAdded = true
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = setForeground(true)
            override fun onStop(owner: LifecycleOwner) = setForeground(false)
        })
    }

    private fun setForeground(foreground: Boolean) {
        isForeground = foreground
        session?.setForeground(foreground)
    }

    internal fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    internal fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
