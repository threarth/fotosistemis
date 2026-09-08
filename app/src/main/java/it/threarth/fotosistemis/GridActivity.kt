package it.threarth.fotosistemis

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
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
import it.threarth.fotosistemis.core.model.ReviewStatus
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

        /**
         * The strip down the right edge left to the scroll handle.
         *
         * With a selection open the finger chooses instead of scrolling, so
         * the handle is the only way left to travel: touches that start
         * inside this strip are its own and are never read as choosing.
         */
        private const val SCROLL_HANDLE_DP = 48

        /** How long the handle takes to appear or fade. */
        private const val HANDLE_FADE_MILLIS = 160L

        /** How long it stays after the finger has finished with it. */
        private const val HANDLE_LINGER_MILLIS = 1400L
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
    private lateinit var stage: FrameLayout
    private lateinit var handle: ImageView
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView

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

    /** True while a finger is held down and choosing as it travels. */
    private var sweeping = false

    /** Whether this sweep is choosing or unchoosing; set by the hold. */
    private var sweepChooses = true

    /** Tiles this sweep has already crossed, so jitter changes nothing. */
    private val swept = HashSet<Int>()

    /** Where the finger came down, to tell the right edge from the rest. */
    private var touchDownAt = PointF()

    /** Where the handle was grabbed, so it does not jump under the finger. */
    private var grabbedAt = 0f

    /** The strip down the right edge that wakes the handle, in pixels. */
    private val handleStrip: Int by lazy {
        (SCROLL_HANDLE_DP * resources.displayMetrics.density).toInt()
    }

    private val hideHandle = Runnable {
        handle.animate().alpha(0f).setDuration(HANDLE_FADE_MILLIS).start()
    }

    /** What is decided about each photo: the label to write on the tile. */
    private val decided = HashMap<Long, String>()

    /**
     * The state each label describes, for the colour of the border.
     *
     * Kept beside the label rather than parsed back out of it: the label is
     * a category's name, chosen by the user, and reading a state out of a
     * name would break the day somebody calls a category "Cestino".
     */
    private val shownStatus = HashMap<Long, ReviewStatus>()

    /**
     * Photos whose decision is a request, not yet carried out.
     *
     * The difference has to be visible. A tile that has already been filed
     * and one that is only asked to be look identical otherwise, and the
     * second will move when the batch is applied while the first will not.
     */
    private val asked = HashSet<Long>()

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
        stage = findViewById(R.id.gridStage)
        handle = findViewById(R.id.gridScrollHandle)
        selectionBar = findViewById(R.id.gridSelectionBar)
        selectionCount = findViewById(R.id.gridSelectionCount)
        findViewById<Button>(R.id.gridCloseSelection).setOnClickListener { closeSelection() }

        findViewById<TextView>(R.id.gridWhere).text = pendingSummary
        findViewById<TextView>(R.id.gridHint).setText(R.string.grid_hint)
        findViewById<Button>(R.id.gridApplyButton).setOnClickListener { askConsent() }

        grid.adapter = TileAdapter()
        grid.setOnItemClickListener { _, _, position, _ -> tap(position) }
        listenToSweep()
        listenToHandle()
        showMoreWhileScrolling()
        buildActions()
        showCounts()
        seedFromDatabase()
    }

    /**
     * Reads what is already decided about these photos, and shows it.
     *
     * Bugfix: the grid used to start blank and learn only what happened
     * inside it. Everything decided while leafing through was in the
     * database and invisible here, so a pass over a month already worked on
     * looked untouched — and the tiles carried no border, which is what the
     * border exists to say.
     *
     * Off the main thread: the tiles and their pictures do not wait for it,
     * and the borders arrive a moment later.
     */
    private fun seedFromDatabase() {
        thread {
            val truths = stateRepository.loadAll().getOrElse { emptyMap() }
            val requests = proposals.loadAll().getOrElse { emptyMap() }
            val origins = stateRepository.loadOriginalPaths().getOrElse { emptyMap() }
            val byId = destinations.associateBy { it.id }

            for (photo in photos) {
                val request = requests[photo.photoId]
                if (request != null) remember(photo, request, byId, origins)
                else truths[photo.photoId]?.let { remember(photo.photoId, it, byId) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                (grid.adapter as TileAdapter).notifyDataSetChanged()
                showCounts()
            }
        }
    }

    /** A request: the tile says so, and the move waits with the others. */
    private fun remember(
        photo: PhotoRecord,
        request: Proposal,
        byId: Map<Long, Destination>,
        origins: Map<Long, PhotoStateRepository.Location>
    ) {
        val destination = byId[request.destinationId]
        decided[photo.photoId] = label(request.shownStatus, destination)
        shownStatus[photo.photoId] = request.shownStatus
        asked.add(photo.photoId)
        MovePlanner.plan(
            photo, request, destination, settings.yearFolderPattern, origins[photo.photoId]
        )?.let { queued[photo.photoId] = it }
    }

    /** Something already carried out: the tile says it, and nothing is queued. */
    private fun remember(
        photoId: Long,
        truth: PhotoStateRepository.StoredState,
        byId: Map<Long, Destination>
    ) {
        decided[photoId] = label(truth.status, byId[truth.destinationId])
        shownStatus[photoId] = truth.status
    }

    /** A state in the fewest words that fit across a tile. */
    private fun label(status: ReviewStatus, destination: Destination?): String = when (status) {
        ReviewStatus.CATEGORIZED -> destination?.label ?: getString(R.string.grid_trash_label)
        ReviewStatus.TRASHED -> getString(R.string.grid_trash_label)
        ReviewStatus.KEPT -> getString(R.string.grid_label_kept)
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
            override fun onScrollStateChanged(view: AbsListView?, state: Int) {
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) hideHandleLater()
                else showHandle()
            }

            override fun onScroll(
                view: AbsListView?,
                firstVisible: Int,
                visibleCount: Int,
                totalCount: Int
            ) {
                placeHandle()
                if (shown >= photos.size) return
                if (firstVisible + visibleCount < totalCount - PAGE_MARGIN) return

                shown = (shown + PAGE_SIZE).coerceAtMost(photos.size)
                (grid.adapter as TileAdapter).notifyDataSetChanged()
            }
        })
    }

    /**
     * Holding a tile and dragging chooses everything the finger crosses.
     *
     * Choosing fifty photographs one tap at a time is fifty chances to miss,
     * and the gesture is the one every gallery already teaches. What the
     * hold finds decides the whole sweep: begun on an unchosen tile it
     * chooses, begun on a chosen one it unchooses, so the same gesture
     * corrects itself.
     *
     * The grid must not scroll under the finger while this is happening,
     * which is why the move is swallowed rather than passed on.
     */
    private fun listenToSweep() {
        grid.setOnItemLongClickListener { _, _, position, _ ->
            beginSweep(position)
            true
        }
        grid.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownAt.set(event.x, event.y)
                    // Reaching towards the right edge is how the handle is
                    // asked for: it appears there before it is needed.
                    if (event.x > grid.width - handleStrip) {
                        showHandle()
                        hideHandleLater()
                    }
                    false
                }

                MotionEvent.ACTION_MOVE -> onDrag(event)

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasSweeping = sweeping
                    sweeping = false
                    wasSweeping
                }

                else -> false
            }
        }
    }

    /**
     * A travelling finger: scrolling, unless a hold has turned it into a
     * sweep.
     *
     * Dragging always scrolls. Choosing many at once is the hold — pressed
     * and kept pressed — and only then does travelling choose. The two
     * gestures have to be told apart by how long the finger waits before it
     * moves, because a screenful of photographs where every drag chooses
     * something is a screenful of decisions nobody made.
     */
    private fun onDrag(event: MotionEvent): Boolean {
        if (!sweeping) return false

        sweepOnto(grid.pointToPosition(event.x.toInt(), event.y.toInt()))
        return true
    }

    /** The hold that starts a sweep, and settles which way it goes. */
    private fun beginSweep(position: Int) {
        if (position == AdapterView.INVALID_POSITION) return

        val photoId = photos[position].photoId
        sweeping = true
        sweepChooses = photoId !in selected
        swept.clear()
        sweepOnto(position)
    }

    /** One tile the finger has reached. Crossing it twice changes nothing. */
    private fun sweepOnto(position: Int) {
        if (position == AdapterView.INVALID_POSITION || !swept.add(position)) return

        val photoId = photos[position].photoId
        if (sweepChooses) selected.add(photoId) else selected.remove(photoId)
        (grid.adapter as TileAdapter).notifyDataSetChanged()
        showCounts()
    }

    /**
     * A tap, which chooses only once there is a choosing going on.
     *
     * The hold is what opens a selection; from then on a tap adds and
     * removes, and so does a sweep. A tap on a grid with nothing chosen
     * does nothing on purpose: the fingers that cross these tiles are
     * mostly scrolling, and a screenful of photographs where every graze
     * chooses something is a screenful of decisions nobody made.
     */
    private fun tap(position: Int) {
        if (selected.isEmpty()) return toast(getString(R.string.grid_hold_first))

        toggle(photos[position].photoId)
    }

    /**
     * Shows the handle when the finger comes near the right edge, and again
     * whenever the grid is moving.
     *
     * Hidden the rest of the time: it is a thing to reach for, not a
     * fixture, and a control that never goes away over a wall of
     * photographs is a control in the way of them.
     */
    private fun listenToHandle() {
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    grabbedAt = event.y
                    showHandle()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    dragHandle(event.y)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    hideHandleLater()
                    true
                }

                else -> false
            }
        }
    }

    /** The handle follows the grid, so it always says where one is. */
    private fun placeHandle() {
        val travel = (stage.height - handle.height).toFloat()
        val hidden = grid.count - grid.childCount
        if (travel <= 0f || hidden <= 0) return

        handle.translationY = travel * grid.firstVisiblePosition / hidden
    }

    /** Dragging the handle moves the grid, not the other way round. */
    private fun dragHandle(touchY: Float) {
        val travel = (stage.height - handle.height).toFloat()
        if (travel <= 0f) return

        val moved = (handle.translationY + touchY - grabbedAt).coerceIn(0f, travel)
        handle.translationY = moved
        grid.setSelection((grid.count * moved / travel).toInt())
    }

    private fun showHandle() {
        handle.removeCallbacks(hideHandle)
        handle.animate().alpha(1f).setDuration(HANDLE_FADE_MILLIS).start()
    }

    private fun hideHandleLater() {
        handle.removeCallbacks(hideHandle)
        handle.postDelayed(hideHandle, HANDLE_LINGER_MILLIS)
    }

    /** Selecting is not deciding: it says which photos the next act is about. */
    private fun toggle(photoId: Long) {
        if (!selected.remove(photoId)) selected.add(photoId)
        (grid.adapter as TileAdapter).notifyDataSetChanged()
        showCounts()
    }

    /**
     * Elimina first, then the categories, then the two that take something
     * back.
     *
     * Elimina leads because it is the one act with no name of its own to
     * look for: the categories are found by reading their labels, while
     * "the other one" is found by position. Deselecting sits apart from the
     * rest — it decides nothing about a photograph, it only puts the
     * choosing down.
     */
    private fun buildActions() {
        val row = findViewById<LinearLayout>(R.id.gridActions)
        row.removeAllViews()

        row.addView(actionButton(getString(R.string.grid_trash)) { trashSelected() })
        for (destination in destinations) {
            row.addView(actionButton(destination.label) { fileInto(destination) })
        }
        row.addView(actionButton(getString(R.string.grid_clear)) { clearSelected() })
    }

    /** Puts the choosing down. Nothing is decided and nothing is undone. */
    private fun closeSelection() {
        selected.clear()
        (grid.adapter as TileAdapter).notifyDataSetChanged()
        showCounts()
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
            shownStatus[photo.photoId] = ReviewStatus.CATEGORIZED
            asked.add(photo.photoId)
            queued[photo.photoId] =
                MovePlanner.toCategory(photo, destination, settings.yearFolderPattern)
        }
    }

    private fun trashSelected() {
        forEachSelected { photo ->
            proposals.propose(photo, Proposal.Action.TRASH, null)
            decided[photo.photoId] = getString(R.string.grid_trash_label)
            shownStatus[photo.photoId] = ReviewStatus.TRASHED
            asked.add(photo.photoId)
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
        val truths = stateRepository.loadAll().getOrElse { emptyMap() }
        val byId = destinations.associateBy { it.id }

        forEachSelected { photo ->
            proposals.withdraw(photo.photoId)
            queued.remove(photo.photoId)
            asked.remove(photo.photoId)
            // What has already happened to the photo stands: withdrawing a
            // request takes back the request, not the history. A photo filed
            // last week and asked into another category is, once the request
            // is taken back, still filed where it was.
            val truth = truths[photo.photoId]
            if (truth == null) {
                decided.remove(photo.photoId)
                shownStatus.remove(photo.photoId)
            } else {
                remember(photo.photoId, truth, byId)
            }
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

        // The bar exists for the selection: with none there is nothing for
        // it to act on, and the buttons would only be there to refuse.
        selectionBar.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
        selectionCount.text = selected.size.toString()
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
            // The arrow says the decision is a request and the file has not
            // moved. The border's colour carries the state, and a colour
            // must never be the only thing carrying a meaning.
            label.text = when {
                decision == null -> ""
                photo.photoId in asked -> getString(R.string.grid_label_asked, decision)
                else -> decision
            }
            label.visibility = if (decision == null) View.GONE else View.VISIBLE

            view.foreground = when {
                photo.photoId in selected ->
                    ContextCompat.getDrawable(this@GridActivity, R.drawable.tile_selected)

                decision != null -> ContextCompat.getDrawable(
                    this@GridActivity, frameFor(shownStatus[photo.photoId])
                )

                else -> null
            }

            // The ring appears with the selection, and says of this tile
            // what the count in the bar says of all of them.
            view.findViewById<ImageView>(R.id.tileCheck).apply {
                visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE
                setImageResource(
                    if (photo.photoId in selected) R.drawable.tile_check_on
                    else R.drawable.tile_check_off
                )
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

    /** The border for one state; the plain one when the state is unknown. */
    private fun frameFor(status: ReviewStatus?): Int = when (status) {
        ReviewStatus.CATEGORIZED -> R.drawable.tile_state_categorized
        ReviewStatus.TRASHED -> R.drawable.tile_state_trashed
        ReviewStatus.KEPT -> R.drawable.tile_state_kept
        null -> R.drawable.tile_state_categorized
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
