package it.threarth.fotosistemis

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.ProposalRepository
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.review.MovePlanner
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * The same work as leafing through, done to many photos at once.
 *
 * Leafing suits a photograph that needs looking at; a grid suits fifty that
 * belong together and are recognised at a glance — a match, an afternoon, a
 * job. Choosing the tiles and then naming the category is fewer acts than
 * deciding each in turn, and the eye does the sorting before the hand moves.
 *
 * The decision is written when the button is pressed, as everywhere else,
 * and the file moves only when the batch is applied. A tile that has been
 * decided says so on its face: a border, and the category written inside,
 * because a colour can say that something happened but never what.
 */
class GridActivity : AppCompatActivity() {

    companion object {

        /**
         * The photos to show, handed over out of band.
         *
         * An intent carries about a megabyte, which a few thousand records
         * would exceed. Set immediately before starting the screen, and
         * only within the same process.
         */
        var pendingPhotos: List<PhotoRecord> = emptyList()

        /** Line saying which folders and period these came from. */
        var pendingSummary: String = ""

        /** Small enough for three across, big enough to recognise a face. */
        private const val TILE_EDGE_PIXELS = 400

        /** Tiles handed over at a time, as the reader gets near the end. */
        private const val PAGE_SIZE = 60

        /** How many rows ahead of the end to fetch the next page. */
        private const val PAGE_MARGIN = 12
    }

    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var proposals: ProposalRepository
    private lateinit var destinationRepository: DestinationRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var settings: AppSettings
    private lateinit var grid: GridView

    /** The side of a square tile: a third of the screen. */
    private val tileSide: Int by lazy { resources.displayMetrics.widthPixels / COLUMNS }

    /** Fetches the tiles' pictures a few at a time, never all at once. */
    private val thumbnails: ThumbnailLoader by lazy {
        ThumbnailLoader(photoSource, TILE_EDGE_PIXELS)
    }

    /** How many tiles the grid is currently offering. */
    private var shown = PAGE_SIZE

    private var photos: List<PhotoRecord> = emptyList()
    private var destinations: List<Destination> = emptyList()

    /** Tiles chosen and not yet acted on. */
    private val selected = LinkedHashSet<Long>()

    /** What has been decided here, by photo: the label to write on the tile. */
    private val decided = HashMap<Long, String>()

    /** The moves those decisions have queued, by photo. */
    private val queued = LinkedHashMap<Long, ReviewSession.PendingMove>()

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) carryOut() else toastRefused()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_grid)
        findViewById<View>(R.id.gridRoot).padForSystemBars()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        proposals = ProposalRepository(database, stateRepository)
        destinationRepository = DestinationRepository(database)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) {
            // Handed over or not, the files have moved: the caller re-reads.
            setResult(RESULT_OK)
            finish()
        }
        settings = AppSettings(this)

        photos = pendingPhotos
        shown = PAGE_SIZE.coerceAtMost(photos.size)
        destinations = destinationRepository.loadAll().getOrElse { emptyList() }
        grid = findViewById(R.id.gridPhotos)

        findViewById<TextView>(R.id.gridWhere).text = pendingSummary
        findViewById<TextView>(R.id.gridHint).setText(R.string.grid_hint)
        findViewById<Button>(R.id.gridApplyButton).setOnClickListener { askConsent() }

        grid.adapter = TileAdapter()
        grid.setOnItemClickListener { _, _, position, _ -> toggle(photos[position].photoId) }
        showMoreWhileScrolling()
        buildActions()
        showCounts()
    }

    override fun onDestroy() {
        thumbnails.stop()
        super.onDestroy()
    }

    /**
     * Hands the grid another chapter when the reader nears the end of this
     * one.
     *
     * Building four thousand tiles to show nine of them is work spent on
     * what nobody is looking at, and it is the thumbnails behind them that
     * cost. A page at a time keeps the first screen quick and the rest
     * arrives before it is wanted.
     */
    private fun showMoreWhileScrolling() {
        grid.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, state: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisible: Int,
                visibleCount: Int,
                totalCount: Int
            ) {
                if (shown >= photos.size) return
                if (firstVisible + visibleCount < totalCount - PAGE_MARGIN) return

                shown = (shown + PAGE_SIZE).coerceAtMost(photos.size)
                (grid.adapter as TileAdapter).notifyDataSetChanged()
            }
        })
    }

    /** Selecting is not deciding: it says which photos the next act is about. */
    private fun toggle(photoId: Long) {
        if (!selected.remove(photoId)) selected.add(photoId)
        (grid.adapter as TileAdapter).notifyDataSetChanged()
        showCounts()
    }

    /** One button per category, plus the two decisions that are not one. */
    private fun buildActions() {
        val row = findViewById<LinearLayout>(R.id.gridActions)
        row.removeAllViews()

        for (destination in destinations) {
            row.addView(actionButton(destination.label) { fileInto(destination) })
        }
        row.addView(actionButton(getString(R.string.grid_trash)) { trashSelected() })
        row.addView(actionButton(getString(R.string.grid_clear)) { clearSelected() })
    }

    private fun actionButton(label: String, action: () -> Unit): Button {
        val button = Button(this)
        button.text = label
        button.setOnClickListener {
            if (selected.isEmpty()) toast(getString(R.string.grid_nothing_selected))
            else action()
        }
        return button
    }

    /**
     * Files every chosen tile into [destination], and queues the moves.
     *
     * Planned by the same planner as the review screen, so a photo filed
     * from a tile gets the stamped name it would have got one at a time.
     */
    private fun fileInto(destination: Destination) {
        forEachSelected { photo ->
            proposals.propose(photo, Proposal.Action.FILE, destination.id)
            decided[photo.photoId] = destination.label
            queued[photo.photoId] =
                MovePlanner.toCategory(photo, destination, settings.yearFolderPattern)
        }
    }

    private fun trashSelected() {
        forEachSelected { photo ->
            proposals.propose(photo, Proposal.Action.TRASH, null)
            decided[photo.photoId] = getString(R.string.grid_trash_label)
            queued[photo.photoId] = MovePlanner.toBin(photo)
        }
    }

    /**
     * Takes the request back off the chosen tiles.
     *
     * Both halves have to go: the proposal and the move it planned.
     * Withdrawing one would leave a photo that says nothing is asked of it
     * and still moves when the batch is applied. What has already happened
     * to the photo is not touched: it goes on being what it was.
     */
    private fun clearSelected() {
        forEachSelected { photo ->
            proposals.withdraw(photo.photoId)
            decided.remove(photo.photoId)
            queued.remove(photo.photoId)
        }
    }

    private fun forEachSelected(action: (PhotoRecord) -> Unit) {
        photos.filter { it.photoId in selected }.forEach(action)
        selected.clear()
        (grid.adapter as TileAdapter).notifyDataSetChanged()
        showCounts()
    }

    private fun showCounts() {
        findViewById<Button>(R.id.gridApplyButton).apply {
            text = getString(R.string.grid_apply, queued.size)
            isEnabled = queued.isNotEmpty()
        }
        findViewById<TextView>(R.id.gridHint).text =
            if (selected.isEmpty()) getString(R.string.grid_hint)
            else getString(R.string.grid_selected, selected.size)
    }

    /** One tile: the photograph, its state, and whether it is chosen. */
    private inner class TileAdapter : BaseAdapter() {

        private val inflater = LayoutInflater.from(this@GridActivity)

        override fun getCount(): Int = shown

        override fun getItem(position: Int): PhotoRecord = photos[position]

        override fun getItemId(position: Int): Long = photos[position].photoId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: inflater.inflate(R.layout.item_grid_tile, parent, false)
            val photo = photos[position]

            // Square tiles, measured from the screen rather than from the
            // grid: on the first pass the grid has no width yet, so the
            // tiles kept whatever height their content suggested and the
            // rows came out ragged, with holes where a thumbnail had not
            // arrived. And a child of a GridView needs that grid's own kind
            // of layout parameters, or it is measured as if it had none.
            view.layoutParams = AbsListView.LayoutParams(
                AbsListView.LayoutParams.MATCH_PARENT, tileSide
            )

            val label = view.findViewById<TextView>(R.id.tileLabel)
            val decision = decided[photo.photoId]
            label.text = decision.orEmpty()
            label.visibility = if (decision == null) View.GONE else View.VISIBLE

            view.foreground = when {
                photo.photoId in selected ->
                    ContextCompat.getDrawable(this@GridActivity, R.drawable.tile_selected)

                decision != null ->
                    ContextCompat.getDrawable(this@GridActivity, R.drawable.tile_decided)

                else -> null
            }

            val image = view.findViewById<ImageView>(R.id.tileImage)
            // Until the picture arrives the tile shows a plain ground: an
            // empty square reads as a gap in the archive, which it is not.
            image.setBackgroundColor(
                ContextCompat.getColor(this@GridActivity, R.color.brand_blue_light)
            )
            thumbnails.into(image, photo)
            return view
        }
    }

    /** Moving files needs the system's permission, whoever asked for it. */
    private fun askConsent() {
        if (queued.isEmpty()) return
        try {
            consentLauncher.launch(
                IntentSenderRequest.Builder(mover.buildConsent(queued.values.toList())).build()
            )
        } catch (error: Exception) {
            toast(getString(R.string.message_error, error.message.orEmpty()))
        }
    }

    private fun carryOut() {
        val moves = queued.values.toList()
        thread {
            val result = mover.applyAll(moves)
            runOnUiThread {
                toast(getString(R.string.grid_applied, result.succeeded, result.failed.size))
                val handover = result.forSystemBin + result.copiedOriginals
                if (handover.isNotEmpty()) {
                    systemBin.offer(handover, result.copiedOriginals.size)
                } else {
                    setResult(RESULT_OK)
                    finish()
                }
            }
        }
    }

    private fun toastRefused() = toast(getString(R.string.message_consent_refused))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

/** Three across, as a gallery shows them. */
private const val COLUMNS = 3
