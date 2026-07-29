package it.threarth.fotosistemis

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Spinner adapter that colours each row.
 *
 * Used to show at a glance how much of a month is still to review. Every row
 * also carries a symbol, so the meaning survives colour blindness and a
 * screen read in sunlight: colour alone must never be the only signal.
 */
class TintedSpinnerAdapter(
    context: Context,
    private val items: List<Item>
) : ArrayAdapter<String>(
    context,
    android.R.layout.simple_spinner_item,
    items.map { it.label }
) {

    /** One row: its text and the colour it should be drawn in. */
    data class Item(val label: String, val colorRes: Int?)

    /** Colour of a row that carries no state of its own. */
    private val defaultColor: Int = TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
        ContextCompat.getColor(context, value.resourceId)
    }

    init {
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        tint(super.getView(position, convertView, parent), position)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        tint(super.getDropDownView(position, convertView, parent), position)

    /**
     * Applies the row colour. The colour is always set, never left alone:
     * spinner rows are recycled, so an untinted row would otherwise inherit
     * the colour of whichever row used the view before it.
     */
    private fun tint(view: View, position: Int): View {
        val text = view as? TextView ?: return view
        val colorRes = items.getOrNull(position)?.colorRes
        text.setTextColor(
            if (colorRes == null) defaultColor else ContextCompat.getColor(context, colorRes)
        )
        return view
    }
}
