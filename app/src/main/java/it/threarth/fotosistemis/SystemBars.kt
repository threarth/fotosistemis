package it.threarth.fotosistemis

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps a screen clear of the status and navigation bars.
 *
 * The insets are added to the padding the layout already asks for, never put
 * in its place. Replacing it throws away the margin the screen was designed
 * with and leaves the content against the edge of the glass — which is what
 * happened here, silently, to every screen at once.
 */
fun View.padForSystemBars() {
    val left = paddingLeft
    val top = paddingTop
    val right = paddingRight
    val bottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            left + bars.left,
            top + bars.top,
            right + bars.right,
            bottom + bars.bottom
        )
        insets
    }
}
