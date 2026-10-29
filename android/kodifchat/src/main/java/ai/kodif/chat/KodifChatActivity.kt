package ai.kodif.chat

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/** The chat, full screen: a [KodifChatFragment] edge to edge. Open it with [KodifChat.open]. */
public class KodifChatActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // White behind the bars, so dark bar icons.
        val barStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        // The process was recreated without the app configuring the chat first.
        if (KodifChat.configuration == null) return finish()
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(android.R.id.content, KodifChatFragment()).commit()
        }
    }
}
