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
        val badge = findViewById<TextView>(R.id.badge)

        KodifChat.listener = object : KodifChatListener {
            override fun onMessage(preview: String, author: KodifChat.Author) {
                badge.text = "New reply: $preview"
            }

            override fun onUnavailable() {
                badge.text = "Chat is unavailable"
            }
        }
        // From the first screen, not at launch: the chat starts once the main thread is idle.
        KodifChat.preload()

        findViewById<Button>(R.id.open_chat).setOnClickListener {
            badge.text = ""
            KodifChat.open(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The listener is held strongly; don't leak this activity.
        KodifChat.listener = null
    }
}
