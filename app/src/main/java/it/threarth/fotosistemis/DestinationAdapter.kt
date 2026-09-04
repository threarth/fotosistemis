package it.threarth.fotosistemis

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.TextView
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.model.Destination

/**
 * One row per category: what it is called, where it puts photos, and whether
 * it splits them by year.
 *
 * The year switch sits in the row rather than inside the form because it is
 * the one thing about a category that gets changed often, and the folder it
 * produces is shown right beside it.
 */
class DestinationAdapter(
    context: Context,
    private val destinations: List<Destination>,

    /** Photos filed here, and how many are still somewhere else. */
    private val uses: Map<Long, PhotoInventory.DestinationUse>,
    private val yearFolderPattern: String,
    private val currentYear: Int,
    private val onYearToggled: (Destination, Boolean) -> Unit
) : BaseAdapter() {

    private val inflater = LayoutInflater.from(context)

    override fun getCount(): Int = destinations.size

    override fun getItem(position: Int): Destination = destinations[position]

    override fun getItemId(position: Int): Long = destinations[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_destination, parent, false)
        val destination = destinations[position]

        view.findViewById<TextView>(R.id.destinationName).text = destination.label
        view.findViewById<TextView>(R.id.destinationPathLine).text = describePath(destination)
        view.findViewById<TextView>(R.id.destinationCounts).text = describeUse(view, destination)

        val check = view.findViewById<CheckBox>(R.id.destinationYearCheck)
        check.setOnCheckedChangeListener(null)
        check.isChecked = destination.yearSubfolder
        check.setOnCheckedChangeListener { _, checked -> onYearToggled(destination, checked) }
        return view
    }

    /**
     * How many photos it holds, and how many are not in its folder yet.
     *
     * The second number is the work a reorganisation would do: after a merge
     * the category is whole here while the photos are still scattered on
     * disk, and nothing else on the screen would say so.
     */
    private fun describeUse(view: View, destination: Destination): String {
        val use = uses[destination.id] ?: return view.context.getString(R.string.destination_empty)
        if (use.elsewhereCount == 0) {
            return view.context.getString(R.string.destination_count, use.photoCount)
        }

        return view.context.getString(
            R.string.destination_count_elsewhere, use.photoCount, use.elsewhereCount
        )
    }

    /** The folder a photo taken this year would land in. */
    private fun describePath(destination: Destination): String {
        val base = destination.relativePath.trim('/')
        if (!destination.yearSubfolder) return "$base/"

        return "$base/${destination.yearFolderName(currentYear, yearFolderPattern)}/"
    }
}
