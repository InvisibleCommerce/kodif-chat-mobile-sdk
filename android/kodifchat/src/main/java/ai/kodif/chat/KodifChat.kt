package ai.kodif.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The Kodif chat. Call [configure] once (e.g. in `Application.onCreate`), then [open] the chat or embed a
 * [KodifChatFragment].
 */
public object KodifChat {
    /** Where the chat widget is loaded from. */
    public sealed class Environment(public val bundleUrl: String) {
        public data object Production : Environment("https://js.shippedsuite.com/api/chat-widget.js")
        public data object Staging : Environment("https://js-staging.shippedsuite.com/api/chat-widget.js")
        public data object Development : Environment("https://js.dev.invisiblecommerce.com/api/chat-widget.js")

        /** Any bundle URL, for tests. */
        public class Custom(bundleUrl: String) : Environment(bundleUrl)
    }

    internal data class Configuration(val integrationId: String, val environment: Environment)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    internal var configuration: Configuration? = null
    internal var isForeground: Boolean = true
    private var preloadPending = false

    // One shared WebView is the point: it lives on the application context (see KodifChatSession).
    @SuppressLint("StaticFieldLeak")
    internal var session: KodifChatSession? = null

    /** Supplies the shopper's JWT whenever the chat asks (at start, when a token expires). Null: anonymous. */
    @JvmStatic
    public var tokenProvider: TokenProvider? = null

    /**
     * Receives the chat's events. Held strongly: set it from something that outlives the chat (your Application), or
     * set it back to null when its owner (an activity) is destroyed.
     */
    @JvmStatic
    public var listener: KodifChatListener? = null

    /** Offers voice mode (declare RECORD_AUDIO and MODIFY_AUDIO_SETTINGS). Set it before [preload] / [open]. */
    @JvmStatic
    public var voiceEnabled: Boolean = false

    /** The chat's locale (e.g. `fr`). Null: the widget detects it. Set it before [preload] / [open]. */
    @JvmStatic
    public var language: String? = null

    /**
     * Key/values attached to the conversation (screen, product, app version), as JSON values. Setting it replaces
     * the conversation's context (null clears it). Keys whose values aren't JSON are dropped.
     */
    @JvmStatic
    public var customContext: Map<String, Any?>? = null
        set(value) {
            field = value
            onMain { session?.sendCustomContext() }
        }

    /** Replies that arrived while the shopper wasn't looking (chat off screen, or app in the background). */
    @JvmStatic
    public var unreadCount: Int = 0
        internal set(value) {
            if (field == value) return
            field = value
            listener?.onUnreadCountChanged(value)
        }

    /** Sets up the chat for your web chat channel's [integrationId]. A new id or environment discards the chat. */
    @JvmStatic
    @JvmOverloads
    public fun configure(context: Context, integrationId: String, environment: Environment = Environment.Production) {
        val applicationContext = context.applicationContext
        onMain {
            if (appContext == null) {
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                isForeground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = setForeground(true)

                    override fun onStop(owner: LifecycleOwner) = setForeground(false)
                })
            }
            appContext = applicationContext
            val newConfiguration = Configuration(integrationId, environment)
            if (configuration != newConfiguration) {
                discardSession()
                configuration = newConfiguration
            }
        }
    }

    private fun setForeground(foreground: Boolean) {
        isForeground = foreground
        session?.onAppStateChanged()
    }

    /**
     * Call when the shopper signs in or out. Asks [tokenProvider]: null means they signed out, so the chat forgets the
     * previous shopper (its data on this device is cleared). Either way the chat restarts with the new shopper.
     */
    @JvmStatic
    public fun shopperChanged() {
        val provider = tokenProvider ?: return onMain { restart(signedOut = true) }
        provider.fetchToken { jwt -> onMain { restart(signedOut = jwt == null) } }
    }

    private fun restart(signedOut: Boolean) {
        if (signedOut) {
            WebStorage.getInstance().deleteOrigin(Bridge.ORIGIN)
            val cookies = CookieManager.getInstance()
            cookies.getCookie(Bridge.ORIGIN)?.split(";")?.forEach {
                cookies.setCookie(Bridge.ORIGIN, "${it.substringBefore("=").trim()}=; Max-Age=0")
            }
            cookies.flush()
        }
        session?.reload()
    }

    /**
     * Starts the chat in the background so [open] shows it instantly. Call it from your first screen: the WebView is
     * created once the main thread is idle, so it doesn't hold up that screen.
     */
    @JvmStatic
    public fun preload() {
        onMain {
            if (preloadPending) return@onMain
            preloadPending = true
            Looper.myQueue().addIdleHandler {
                preloadPending = false
                if (configuration != null) requireSession().preload()
                false
            }
        }
    }

    /** Opens the chat full screen in [KodifChatActivity]. */
    @JvmStatic
    public fun open(context: Context) {
        requireNotNull(configuration) { "Call KodifChat.configure(...) before KodifChat.open(...)" }
        val intent = Intent(context, KodifChatActivity::class.java)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    internal fun requireSession(): KodifChatSession = session ?: KodifChatSession(
        checkNotNull(appContext) { "Call KodifChat.configure(...) first" },
        checkNotNull(configuration) { "Call KodifChat.configure(...) first" },
    ).also { session = it }

    internal fun discardSession() {
        session?.destroy()
        session = null
    }

    internal fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
