package it.threarth.fotosistemis

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
import it.threarth.fotosistemis.core.reorg.Reorganizer

/**
 * Every filed photo and where it actually sits.
 *
 * Filing a photo records a decision; moving it is a separate act that can be
 * postponed, refused by the system, or half done. Nothing else in the app
 * shows the two side by side, so a photo could be filed under Famiglia and
 * living somewhere else entirely without anything saying so.
 */
class PlacementActivity : AppCompatActivity() {

    private lateinit var inventory: PhotoInventory
    private lateinit var settings: AppSettings
    private lateinit var listView: ListView
    private lateinit var summary: TextView

    private var rows: List<Row> = emptyList()
    private var onlyWrong = false

    /** One photo: where its category says it goes, and where it is. */
    private data class Row(
        val displayName: String,
        val categoryLabel: String,
        val actualPath: String,
        val expectedPath: String
    ) {
        val isHome: Boolean get() = actualPath == expectedPath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_placement)
        applySystemBarInsets()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        settings = AppSettings(this)
        listView = findViewById(R.id.placementList)
        summary = findViewById(R.id.placementSummary)
        findViewById<Button>(R.id.placementFilterButton).setOnClickListener {
            onlyWrong = !onlyWrong
            redraw()
        }

        load(DestinationRepository(database))
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.placementRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Pairs each filed photo with the folder its category names. */
    private fun load(repository: DestinationRepository) {
        val entries = inventory.loadForReorganization().getOrElse {
            summary.text = getString(R.string.message_error, it.message.orEmpty())
            return
        }
        val byId: Map<Long, Destination> = repository.loadAll().getOrElse { emptyList() }
            .associateBy { it.id }

        rows = entries.mapNotNull { entry ->
            val destination = byId[entry.destinationId] ?: return@mapNotNull null
            Row(
                displayName = entry.displayName,
                categoryLabel = destination.label,
                actualPath = entry.relativePath,
                expectedPath = destination.pathFor(entry.captureMillis, settings.yearFolderPattern)
            )
        }.sortedWith(compareBy({ it.isHome }, { it.categoryLabel }, { it.displayName }))

        redraw()
    }

    private fun redraw() {
        val fuori = rows.count { !it.isHome }
        summary.text = getString(R.string.placement_summary, rows.size, rows.size - fuori, fuori)
        findViewById<Button>(R.id.placementFilterButton).setText(
            if (onlyWrong) R.string.placement_show_all else R.string.placement_only_wrong
        )
        listView.adapter = Adapter(if (onlyWrong) rows.filter { !it.isHome } else rows)
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
