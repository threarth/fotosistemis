package it.threarth.fotosistemis

import android.view.View
import android.widget.ProgressBar

/**
 * The bar every scan in this app shows while it works.
 *
 * Written once because the alternative is five bars that drift apart, and
 * because the rule they share is easy to get wrong: a bar is determinate
 * only when the total is genuinely known. Where it is not — a database read,
 * a query to the archive — it spins instead of inventing a percentage, since
 * a bar that lies about how far along it is tells the reader less than no
 * bar at all.
 */
class ScanProgress(private val bar: ProgressBar) {

    /** Starts a scan whose length is not known: the bar spins. */
    fun startSpinning() {
        bar.isIndeterminate = true
        bar.visibility = View.VISIBLE
    }

    /** Starts a scan of [total] steps, or spins when there is nothing to count. */
    fun start(total: Int) {
        if (total <= 0) return startSpinning()

        bar.isIndeterminate = false
        bar.max = total
        bar.progress = 0
        bar.visibility = View.VISIBLE
    }

    /** Moves the bar to [done] of the total given at the start. */
    fun advance(done: Int) {
        if (bar.isIndeterminate) return

        bar.progress = done.coerceIn(0, bar.max)
    }

    /** The work is over, well or badly: the bar goes away either way. */
    fun stop() {
        bar.visibility = View.GONE
    }
}
