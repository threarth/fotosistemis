package it.threarth.fotosistemis

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import it.threarth.fotosistemis.core.review.FolderTree
import it.threarth.fotosistemis.core.model.FolderPath

/**
 * Draws the folder tree one open level at a time.
 *
 * A phone holds photos in dozens of folders, and a flat indented list of all
 * of them is no easier to read than the spinner it replaces. Showing only
 * what has been opened keeps the choice small at every step.
 */
class FolderTreeAdapter(
    context: Context,
    private val nodes: List<FolderTree.Node>,
    private val selected: MutableSet<String>,

    /** True when picking one folder unpicks whatever was picked before. */
    private val singleChoice: Boolean = false,

    /**
     * Told whenever the picked set changes.
     *
     * A tree offered beside a "everything" checkbox has to be able to say
     * it was used, or the two go on disagreeing in silence and one of them
     * quietly wins at save time.
     */
    private val onPicked: (() -> Unit)? = null
) : BaseAdapter() {

    private companion object {

        /** How far each level is pushed right, in density-independent pixels. */
        const val INDENT_DP = 16

        const val OPEN = "▾"
        const val CLOSED = "▸"

        /** How faded the tree looks while the whole device is chosen. */
        const val DISABLED_ALPHA = 0.35f

        /** A folder that only holds other folders reads fainter. */
        const val EMPTY_FOLDER_ALPHA = 0.4f
    }

    private val inflater = LayoutInflater.from(context)
    private val density = context.resources.displayMetrics.density
    private val open = HashSet<String>()
    private var shown: List<FolderTree.Node> = FolderTree.visible(nodes, open)
    private var enabled = true

    /**
     * Greys the tree out without forgetting what was ticked.
     *
     * Choosing the whole device makes every folder below it irrelevant, and
     * leaving them looking live suggests they are still part of the answer.
     * Clearing them instead would lose a choice the user may want back.
     */
    fun setEnabled(value: Boolean) {
        enabled = value
        notifyDataSetChanged()
    }

    /** Opens every folder leading to something already chosen. */
    fun revealSelection() {
        for (path in selected) {
            var parent = path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                open.add(parent)
                parent = parent.substringBeforeLast('/', "")
            }
        }
        refresh()
    }

    override fun getCount(): Int = shown.size

    override fun getItem(position: Int): FolderTree.Node = shown[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_folder_node, parent, false)
        val node = shown[position]

        bindToggle(view, node)
        bindLabel(view, node)
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        return view
    }

    /** The arrow opens and closes; folders with nothing under them show none. */
    private fun bindToggle(view: View, node: FolderTree.Node) {
        val toggle = view.findViewById<TextView>(R.id.nodeToggle)
        toggle.text = when {
            !node.hasChildren -> ""
            open.any { FolderPath.sameFolder(it, node.relativePath) } -> OPEN
            else -> CLOSED
        }
        toggle.setPadding((node.depth * INDENT_DP * density).toInt(), 0, 0, 0)
        toggle.setOnClickListener {
            if (!enabled || !node.hasChildren) return@setOnClickListener
            if (!open.removeAll { FolderPath.sameFolder(it, node.relativePath) }) {
                open.add(node.relativePath)
            }
            refresh()
        }
    }

    /** The rest of the row picks the folder, and everything beneath it. */
    private fun bindLabel(view: View, node: FolderTree.Node) {
        val check = view.findViewById<CheckBox>(R.id.nodeCheck)
        val label = view.findViewById<TextView>(R.id.nodeLabel)

        check.isChecked = selected.any { FolderPath.sameFolder(it, node.relativePath) }

        // Dimmed on a folder that holds nothing itself: it can still be
        // chosen, because choosing it takes everything beneath, but the eye
        // should be able to tell a folder of photos from a fork in the path.
        view.findViewById<ImageView>(R.id.nodeIcon).alpha =
            if (node.ownPhotoCount > 0) 1f else EMPTY_FOLDER_ALPHA
        label.text = view.context.getString(
            R.string.roots_entry,
            node.label,
            node.photoCount
        )

        // Choosing a folder takes everything under it, so the row has to say
        // how much of that total is actually here and how much is below —
        // and where the folder really sits, since a collapsed run hides the
        // levels in between and two folders can end in the same word.
        view.findViewById<TextView>(R.id.nodePath).text = view.context.getString(
            R.string.roots_entry_detail,
            node.relativePath,
            node.ownPhotoCount,
            node.photoCount - node.ownPhotoCount
        )
        val pick = View.OnClickListener {
            if (!enabled) return@OnClickListener
            val era = selected.removeAll { FolderPath.sameFolder(it, node.relativePath) }
            if (singleChoice) selected.clear()
            if (!era) selected.add(node.relativePath)
            onPicked?.invoke()
            notifyDataSetChanged()
        }
        check.setOnClickListener(pick)
        label.setOnClickListener(pick)
    }

    private fun refresh() {
        shown = FolderTree.visible(nodes, open)
        notifyDataSetChanged()
    }
}
