package ai.kodif.chat.example

import ai.kodif.chat.KodifChat
import ai.kodif.chat.KodifChatListener
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val status = findViewById<TextView>(R.id.status)
        val openChat = findViewById<Button>(R.id.open_chat)
        fun showUnread(count: Int) {
            openChat.text = if (count > 0) "Chat with us ($count)" else "Chat with us"
        }

        KodifChat.listener = object : KodifChatListener {
            override fun onUnreadCountChanged(count: Int) = showUnread(count)

            override fun onUnavailable() {
                status.text = "Chat is unavailable"
            }
        }
        showUnread(KodifChat.unreadCount)
        // From the first screen, not at launch: the chat starts once the main thread is idle.
        KodifChat.preload()

        openChat.setOnClickListener {
            status.text = ""
            KodifChat.open(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The listener is held strongly; don't leak this activity.
        KodifChat.listener = null
    }
}
