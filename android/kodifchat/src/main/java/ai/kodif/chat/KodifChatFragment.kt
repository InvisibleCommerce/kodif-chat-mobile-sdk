package ai.kodif.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment

/**
 * The chat, for embedding in your own screen. It fills its container and pads itself by the system bars and the
 * keyboard. When the shopper closes the chat, [KodifChatListener.onClose] is called: remove the fragment there.
 */
public class KodifChatFragment : Fragment() {
    private lateinit var host: ChatHost
    private var container: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = ChatHost(this, { activity }, {})
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).apply {
            setBackgroundColor(Color.WHITE)
            applyChatInsets()
        }.also { this.container = it }

    override fun onStart() {
        super.onStart()
        val container = container ?: return
        if (KodifChat.configuration == null) return
        val session = KodifChat.requireSession()
        session.attach(host, container)
        session.setVisible(host, true)
    }

    override fun onStop() {
        super.onStop()
        KodifChat.session?.setVisible(host, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        KodifChat.session?.detach(host)
        container = null
    }
}
