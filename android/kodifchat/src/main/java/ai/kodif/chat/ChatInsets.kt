package ai.kodif.chat

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Pads the chat's container by the system bars, display cutout and keyboard, and consumes the insets, so the page
 * never sees any (PROTOCOL.md, "Safe areas") and the composer stays above the keyboard.
 */
internal fun View.applyChatInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val types = WindowInsetsCompat.Type.systemBars() or
            WindowInsetsCompat.Type.displayCutout() or
            WindowInsetsCompat.Type.ime()
        val padding = insets.getInsets(types)
        view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
        WindowInsetsCompat.CONSUMED
    }
}
