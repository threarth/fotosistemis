package it.threarth.fotosistemis

import android.content.Intent
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.review.ReviewSession

/**
 * Every filed photo and where it actually sits.
 *
 * Filing a photo records a decision; moving it is a separate act that can be
 * postponed, refused by the system, or half done. Nothing else in the app
 * shows the two side by side, so a photo could be filed under Famiglia and
 * living somewhere else entirely without anything saying so.
 */
class PlacementActivity : AppCompatActivity() {

    companion object {

        /** Told to the caller when the bin needs gathering, which only it can do. */
        const val EXTRA_GATHER_TRASH = "it.threarth.fotosistemis.GATHER_TRASH"
    }

    private lateinit var inventory: PhotoInventory
    private lateinit var settings: AppSettings
    private lateinit var listView: ListView
    private lateinit var summary: TextView

    private var rows: List<Row> = emptyList()
    private var onlyWrong = false

    /** Categories being shown; empty means all of them. */
    private var chosenCategories: Set<String> = emptySet()

    /** One photo: where its category says it goes, and where it is. */
    private data class Row(
        val photoId: Long,
        val displayName: String,
        val categoryLabel: String,
        val actualPath: String,
        val expectedPath: String,

        /** Bound for the bin rather than a category: fixed a different way. */
        val isTrash: Boolean = false
    ) {
        val isHome: Boolean get() = actualPath == expectedPath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_placement)
        findViewById<View>(R.id.placementRoot).padForSystemBars()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        settings = AppSettings(this)
        listView = findViewById(R.id.placementList)
        summary = findViewById(R.id.placementSummary)
        findViewById<Button>(R.id.placementFilterButton).setOnClickListener {
            onlyWrong = !onlyWrong
            redraw()
        }
        findViewById<Button>(R.id.placementFixAllButton).setOnClickListener {
            fix(shownRows().filter { !it.isHome })
        }
        findViewById<Button>(R.id.placementCategoryButton).setOnClickListener {
            chooseCategories()
        }
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = (listView.adapter as Adapter).getItem(position)
            if (!row.isHome) fix(listOf(row))
        }

        load(DestinationRepository(database))
    }

    /** Pairs each filed photo with the folder its category names. */
    private fun load(repository: DestinationRepository) {
        val entries = inventory.loadForReorganization().getOrElse {
            summary.text = getString(R.string.message_error, it.message.orEmpty())
            return
        }
        val byId: Map<Long, Destination> = repository.loadAll().getOrElse { emptyList() }
            .associateBy { it.id }

        val cestino = inventory.loadPendingTrash()
            .getOrElse { emptyList() }
            .map { photo ->
                Row(
                    photoId = photo.photoId,
                    displayName = photo.displayName,
                    categoryLabel = getString(R.string.placement_trash_label),
                    actualPath = photo.relativePath,
                    expectedPath = ReviewSession.DELETION_STAGING_PATH,
                    isTrash = true
                )
            }

        rows = cestino + entries.mapNotNull { entry ->
            val destination = byId[entry.destinationId] ?: return@mapNotNull null
            Row(
                photoId = entry.photoId,
                displayName = entry.displayName,
                categoryLabel = destination.label,
                actualPath = entry.relativePath,
                expectedPath = destination.pathFor(entry.captureMillis, settings.yearFolderPattern)
            )
        }.sortedWith(compareBy({ it.isHome }, { it.categoryLabel }, { it.displayName }))

        redraw()
    }

    /**
     * Hands photos to the reorganiser, which is the only thing that writes.
     *
     * Nothing is moved from here: the plan, the preview and the system's
     * consent all belong to one place, and duplicating them would mean two
     * paths to the same irreversible act.
     */
    private fun fix(chosen: List<Row>) {
        if (chosen.isEmpty()) return toast(getString(R.string.placement_fix_none))

        // The bin is gathered from the screen that owns the review session,
        // and categories by the reorganiser: each act keeps its one path to
        // writing, rather than growing a second.
        val (cestino, categorie) = chosen.partition { it.isTrash }
        if (categorie.isNotEmpty()) {
            startActivity(
                Intent(this, ReorganizeActivity::class.java).putExtra(
                    ReorganizeActivity.EXTRA_PHOTO_IDS,
                    categorie.map { it.photoId }.toLongArray()
                )
            )
            return
        }

        setResult(RESULT_OK, Intent().putExtra(EXTRA_GATHER_TRASH, true))
        finish()
    }

    /** Narrows the list to the categories worth looking at. */
    private fun chooseCategories() {
        val etichette = rows.map { it.categoryLabel }.distinct().sorted()
        if (etichette.isEmpty()) return toast(getString(R.string.placement_fix_none))

        val checked = BooleanArray(etichette.size) {
            chosenCategories.isEmpty() || etichette[it] in chosenCategories
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.placement_categories_title)
            .setMultiChoiceItems(etichette.toTypedArray<CharSequence>(), checked) { _, which, on ->
                checked[which] = on
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val tenute = etichette.filterIndexed { i, _ -> checked[i] }.toSet()
                chosenCategories = if (tenute.size == etichette.size) emptySet() else tenute
                redraw()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The rows the filters leave visible. */
    private fun shownRows(): List<Row> = rows
        .filter { chosenCategories.isEmpty() || it.categoryLabel in chosenCategories }
        .filter { !onlyWrong || !it.isHome }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun redraw() {
        val visibili = shownRows()
        val fuori = visibili.count { !it.isHome }
        summary.text = getString(
            R.string.placement_summary, visibili.size, visibili.size - fuori, fuori
        )
        findViewById<Button>(R.id.placementFilterButton).setText(
            if (onlyWrong) R.string.placement_show_all else R.string.placement_only_wrong
        )
        findViewById<Button>(R.id.placementCategoryButton).text =
            if (chosenCategories.isEmpty()) getString(R.string.placement_categories_all)
            else getString(R.string.placement_categories_some, chosenCategories.size)

        listView.adapter = Adapter(visibili)
    }

    /** Green and a tick when the photo is where its category says. */
    private inner class Adapter(private val shown: List<Row>) : BaseAdapter() {

        private val inflater = LayoutInflater.from(this@PlacementActivity)

        override fun getCount(): Int = shown.size

        override fun getItem(position: Int): Row = shown[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_placement, parent, false)
            val row = shown[position]

            val mark = view.findViewById<TextView>(R.id.placementMark)
            mark.text = getString(if (row.isHome) R.string.placement_ok else R.string.placement_off)
            mark.setTextColor(
                ContextCompat.getColor(
                    this@PlacementActivity,
                    if (row.isHome) R.color.state_kept else R.color.state_trashed
                )
            )

            view.findViewById<TextView>(R.id.placementName).text =
                getString(R.string.placement_name, row.categoryLabel, row.displayName)
            view.findViewById<TextView>(R.id.placementWhere).text =
                if (row.isHome) row.actualPath
                else getString(R.string.placement_where, row.actualPath, row.expectedPath)
            return view
        }
    }
}
