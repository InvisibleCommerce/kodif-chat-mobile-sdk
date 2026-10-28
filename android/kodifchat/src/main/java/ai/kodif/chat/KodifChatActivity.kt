package ai.kodif.chat

import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/** The chat, full screen. Open it with [KodifChat.open]. */
public class KodifChatActivity : AppCompatActivity() {
    private lateinit var host: ChatHost
    private lateinit var container: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // White behind the bars, so dark bar icons.
        val barStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        if (KodifChat.configuration == null) {
            // The process was recreated without the app configuring the chat first.
            finish()
            return
        }
        host = ChatHost(this, { this }, { finish() })
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            applyChatInsets()
        }
        setContentView(container)
    }

    override fun onStart() {
        super.onStart()
        if (!::host.isInitialized) return
        val session = KodifChat.requireSession()
        session.attach(host, container)
        session.setVisible(host, true)
    }

    override fun onStop() {
        super.onStop()
        if (!::host.isInitialized) return
        KodifChat.session?.setVisible(host, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!::host.isInitialized) return
        KodifChat.session?.detach(host)
    }
}
