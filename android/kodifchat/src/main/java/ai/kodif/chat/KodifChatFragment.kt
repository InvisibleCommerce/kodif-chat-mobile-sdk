package ai.kodif.chat

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

/**
 * The chat, for embedding in your own screen. It fills its container and pads itself by the system bars and the
 * keyboard. When the shopper closes the chat, [KodifChatListener.onClose] is called: remove the fragment there.
 * ([KodifChatActivity] hosts one too, and finishes instead.)
 */
public class KodifChatFragment : Fragment() {
    private lateinit var host: ChatHost
    private var container: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = ChatHost(this, { activity }, { (activity as? KodifChatActivity)?.finish() })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).also { frame ->
            frame.setBackgroundColor(Color.WHITE)
            // The page sees no insets (PROTOCOL.md, "Safe areas"): pad by the bars, cutout and keyboard here.
            ViewCompat.setOnApplyWindowInsetsListener(frame) { view, insets ->
                val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
                val padding = insets.getInsets(types)
                view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
                WindowInsetsCompat.CONSUMED
            }
            this.container = frame
        }

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
