package it.threarth.fotosistemis

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.IgnoredRepository
import it.threarth.fotosistemis.core.date.CaptureDateCheck
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.RatingRepository
import it.threarth.fotosistemis.core.data.TagRepository
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.port.PhotoSource as PhotoSourcePort
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.review.FolderTree
import it.threarth.fotosistemis.core.review.PhotoFilter
import it.threarth.fotosistemis.core.review.ReviewSession
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * The review screen: one photo at a time, with navigation that decides
 * nothing and buttons that decide something.
 *
 * Only wires views to ReviewSession. Ordering, decisions and the queue live
 * there; MediaStore and SQLite access live in their repositories.
 */
class MainActivity : AppCompatActivity() {

    private companion object {

        /**
         * How many fingerprints one load takes.
         *
         * Small enough to finish while the screen is being looked at, and
         * enough that a few hundred catalogued photos are covered in a
         * handful of sessions.
         */
        const val FINGERPRINTS_PER_LOAD = 40

        /** The volume every phone has, and the only one these photos use. */
        const val PRIMARY_VOLUME = "external_primary"

        /**
         * How long a full scan stands before another is worth running.
         *
         * Ten minutes: long enough that standby and a glance cost nothing,
         * short enough that photos taken while the app was away show up.
         */
        const val FULL_SCAN_REST_MILLIS = 10 * 60 * 1000L

        /** Big enough to tap, small enough that five fit beside a button. */
        /** How long the way back stays on screen after filing a folder. */
        const val UNDO_VISIBLE_MILLIS = 12_000

        const val STAR_TEXT_SIZE = 22f
        const val STAR_PADDING = 10


        /** Preselected source folder when it exists: the camera roll. */
        const val DEFAULT_SOURCE_FOLDER = "DCIM/Camera/"

        const val DATE_PATTERN = "dd/MM/yyyy HH:mm"
        const val DAY_PATTERN = "dd/MM/yyyy"
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val IMAGE_MIME_TYPE = "image/*"

        /** Longest edge of the preview: larger than any phone screen needs. */
        const val THUMBNAIL_EDGE_PIXELS = 1024

        /** Shortest movement accepted as a deliberate swipe. */
        const val MIN_FLING_PIXELS = 80f

        /** Movement after which the drag axis is fixed. */
        const val AXIS_LOCK_PIXELS = 18f

        /** Fraction of the stage a drag must cover to perform its action. */
        const val COMMIT_FRACTION = 0.22f

        /** How much of the drag survives when there is nowhere to go. */
        const val EDGE_RESISTANCE = 0.22f

        /** Time taken to finish a committed drag, or to return home. */
        const val SETTLE_DURATION_MILLIS = 170L

        /** The confirmation stays put briefly, then fades. */
        const val FLASH_HOLD_MILLIS = 350L
        const val FLASH_FADE_MILLIS = 260L

        /** Thickness of the coloured frame showing the recorded state. */
        const val FRAME_WIDTH_DP = 5f

        /** Current photo plus its two neighbours, with a little slack. */
        const val THUMBNAIL_CACHE_SIZE = 4
    }

    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var destinationRepository: DestinationRepository
    private lateinit var mover: BatchMover
    private lateinit var settings: AppSettings
    private lateinit var session: ReviewSession

    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private lateinit var photoInfo: TextView
    private lateinit var photoTags: TextView
    private lateinit var destinationActions: LinearLayout
    private lateinit var folderButton: Button
    private lateinit var periodSpinner: Spinner
    private lateinit var scopeSpinner: Spinner
    private lateinit var mediaStage: View
    private lateinit var previewAdjacent: ImageView
    private lateinit var actionFlash: TextView
    private lateinit var tagButton: Button
    private lateinit var dateButton: Button
    private lateinit var stateBadge: TextView
    private lateinit var photoFolder: TextView
    private lateinit var swipeLegend: TextView
    private lateinit var progress: ScanProgress

    /** The category folders are checked once each time the app opens. */
    private var filedFoldersChecked = false

    /** True while the photograph has the screen to itself. */
    private var fullScreen = false

    /** Back leaves full screen before it leaves the app. */
    private val exitFullScreen = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFullScreen(false)
    }

    /** Photos the user has said carry a wrong date. */
    private var suspectDates: Set<Long> = emptySet()
    private lateinit var printButton: Button
    private lateinit var starBar: LinearLayout
    private lateinit var ratings: RatingRepository

    /** Stars per photo, read once and kept in step with what is written. */
    private var ratingByPhoto: Map<Long, Int> = emptyMap()

    private lateinit var stagingButton: Button
    private lateinit var openExternalButton: Button
    private lateinit var undoButton: Button
    private lateinit var applyButton: Button

    private val offeredPeriods = ArrayList<PhotoFilter.Period>()

    /** Folders offered by the spinner; null at index 0 means "every folder". */
    /**
     * The branch being worked on, or null for everything in scope.
     *
     * One field where there used to be two: choosing a folder from a tree
     * always means that folder and what hangs from it, so "just this one"
     * and "this one and below" stopped being different questions.
     */
    private var preferredSubtree: String? = null

    /**
     * True once the scope has been put to the user in this session.
     *
     * Asked before anything is loaded: what is in scope decides what the
     * screen means, and loading first would show a set the user never chose.
     */
    private var sourceAsked = false

    /**
     * The scope dialog while it is up.
     *
     * Folders arrive twice — once from our own inventory, once after the
     * platform has been reconciled — and the second arrival must not start
     * loading photos behind a dialog whose whole purpose is to decide which
     * photos those are.
     */
    private var sourcePicker: AlertDialog? = null

    /**
     * What the last reconciliation found, for the scope dialog to show.
     *
     * A toast for it lands in the same instant that dialog opens, which is
     * three seconds nobody reads. The dialog is where the user is looking.
     */
    private var lastReport: String? = null

    /** Every folder the platform reported, before any narrowing. */
    private var allFolders: List<FolderSummary> = emptyList()

    private var destinations: List<Destination> = emptyList()
    /**
     * Period the user last chose. Kept apart from the spinner because the
     * spinner is rebuilt from the photos of each folder, and rebuilding it
     * must not silently change what is being reviewed.
     */
    private var preferredPeriod: PhotoFilter.Period = PhotoFilter.Period.Any


    private var customRange: PhotoFilter.Period.Range? = null
    private var busy = false

    /**
     * Identifies the newest load request. Results carrying an older
     * number are discarded: without this the reply to a superseded
     * filter could arrive last and win, showing photos the user did not
     * ask for.
     */
    private var loadGeneration = 0

    /** Photos currently waiting to be deleted. */
    private var stagingCount = 0

    /** False until the first onResume, which follows onCreate. */
    private var resumedBefore = false

    /** Restores awaiting consent. Never enters the pending queue. */
    private var pendingRestore: List<ReviewSession.PendingMove> = emptyList()

    /** Which way a drag has committed, once it is clear. */
    private enum class DragAxis { UNDECIDED, HORIZONTAL, VERTICAL }

    private var dragAxis = DragAxis.UNDECIDED
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var adjacentRestingOffset = 0f

    /** Set when a gesture asked for an action the folder does not allow. */
    private var dragBlocked = false

    /** Set when the drag is pulling past the first or last photo. */
    private var dragAtEdge = false

    /**
     * Recently decoded thumbnails, keyed by MediaStore id.
     *
     * Holds the current photo and its neighbours, so completing a gesture
     * shows the next photo immediately instead of blanking the view while it
     * is decoded again, and a drag can reveal the neighbour at once.
     */
    private val thumbnails = object : LinkedHashMap<Long, Bitmap>(
        THUMBNAIL_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) =
            size > THUMBNAIL_CACHE_SIZE
    }

    private val previousBitmap: Bitmap? get() = session.peek(-1)?.let { thumbnails[it.platformId] }
    private val nextBitmap: Bitmap? get() = session.peek(1)?.let { thumbnails[it.platformId] }

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) refreshFolders()
            else statusText.setText(R.string.status_permission_needed)
        }

    /**
     * One consent for the whole queue. Filing and trashing are both moves,
     * so a mixed batch costs a single dialog.
     */
    private val requestMoveConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) applyMoves()
            else refuseConsent()
        }

    /** The queue can change what is waiting, so the counts are re-read. */
    private val queueLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshFolders()
        }

    /**
     * Placement hands work back: either photos to reorganise, or a request
     * to gather what is waiting for the bin.
     */
    private val placementLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            refreshFolders()
            if (result.data?.getBooleanExtra(PlacementActivity.EXTRA_GATHER_TRASH, false) == true) {
                showStagingFolder()
            }
        }

    /** Photos this app cannot move end up in Android's bin, if allowed. */
    private lateinit var systemBin: SystemBinHandover


    private val requestRestoreConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) applyRestore()
            else {
                pendingRestore = emptyList()
                refuseConsent()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        bindViews()
        findViewById<View>(R.id.main).padForSystemBars()

        val database = AndroidDatabase(this)
        settings = AppSettings(this)
        photoSource = MediaStorePhotoSource(this)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        ratings = RatingRepository(database)
        tagRepository = TagRepository(database)
        destinationRepository = DestinationRepository(database)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) {
            refreshFolders()
        }
        session = ReviewSession(stateRepository, tagRepository) { settings.yearFolderPattern }

        buildScopeSpinner()
        wireActions()
        ensureReadPermission()
    }

    /**
     * Destinations may have changed in the other screen, and the deletion
     * folder may have been emptied from Google Photos while the app was in
     * the background: neither change announces itself, so both are checked
     * on the way back.
     */
    override fun onResume() {
        super.onResume()
        buildDestinationButtons()
        // Skipped the first time: the initial folder scan is already running
        // and will set the count itself.
        if (resumedBefore && ::session.isInitialized) refreshStagingCount()
        resumedBefore = true
    }

    /**
     * Re-counts the photos awaiting deletion without disturbing the review.
     *
     * A full reload would lose the current position every time the app is
     * reopened, which is too high a price for a number in a button.
     */
    /**
     * Counts the bin from the platform, not from our own inventory.
     *
     * The point of the bin is that it gets emptied elsewhere — from Google
     * Photos, so the copy in the cloud goes too — and our inventory learns
     * that only at the next full scan. Asking the platform is what makes the
     * number drop when the folder is actually emptied.
     */
    private fun refreshStagingCount() {
        thread {
            val counted = photoSource
                .countPhotosIn(ReviewSession.DELETION_STAGING_PATH)
                .getOrNull() ?: return@thread
            runOnUiThread { onStagingCountRefreshed(counted) }
        }
    }

    private fun onStagingCountRefreshed(counted: Int) {
        if (counted == stagingCount) return
        stagingCount = counted
        stagingButton.text = getString(R.string.action_staging, stagingCount)
        findViewById<Button>(R.id.drawerTrashButton).text =
            getString(R.string.action_staging, stagingCount)
        refreshWaitingCount()
        updateButtonState()

        // Emptied while we were looking at it: what is on screen no longer
        // exists, so the list has to be rebuilt.
        if (preferredSubtree == stagingPath()) refreshFolders()
    }

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        photoInfo = findViewById(R.id.photoInfo)
        photoTags = findViewById(R.id.photoTags)
        destinationActions = findViewById(R.id.destinationActions)
        folderButton = findViewById(R.id.folderButton)
        folderButton.setOnClickListener { editWorkingFolder() }
        findViewById<Button>(R.id.sourceButton).setOnClickListener { editSourceRoots() }
        periodSpinner = findViewById(R.id.periodSpinner)
        scopeSpinner = findViewById(R.id.scopeSpinner)
        mediaStage = findViewById(R.id.mediaStage)
        previewAdjacent = findViewById(R.id.previewAdjacent)
        actionFlash = findViewById(R.id.actionFlash)
        tagButton = findViewById(R.id.tagButton)
        dateButton = findViewById(R.id.dateButton)
        stateBadge = findViewById(R.id.stateBadge)
        photoFolder = findViewById(R.id.photoFolder)
        swipeLegend = findViewById(R.id.swipeLegend)
        progress = ScanProgress(findViewById(R.id.mainProgress))
        photoFolder.setOnClickListener { offerFolderAsCategory() }
        printButton = findViewById(R.id.printButton)
        starBar = findViewById(R.id.starBar)
        buildStarBar()

        stagingButton = findViewById(R.id.stagingButton)
        openExternalButton = findViewById(R.id.openExternalButton)
        undoButton = findViewById(R.id.undoButton)
        applyButton = findViewById(R.id.applyButton)
    }

    /**
     * Wires the drawer: everything that is not sorting a photo lives there.
     *
     * Nine buttons around the picture made the rare and the constant look
     * equally reachable, and a thumb swiping through an archive should not
     * be a thumb's width from rebuilding the inventory.
     */
    private fun bindDrawer() {
        val drawer = findViewById<DrawerLayout>(R.id.drawer)
        findViewById<Button>(R.id.menuButton).setOnClickListener { drawer.open() }

        fun voce(id: Int, azione: () -> Unit) {
            findViewById<Button>(id).setOnClickListener {
                drawer.close()
                azione()
            }
        }
        voce(R.id.drawerSourceButton) { editSourceRoots() }
        voce(R.id.drawerOutputButton) {
            startActivity(Intent(this, DestinationsActivity::class.java))
        }
        voce(R.id.drawerQueueButton) {
            queueLauncher.launch(Intent(this, QueueActivity::class.java))
        }
        voce(R.id.drawerPlacementButton) {
            placementLauncher.launch(Intent(this, PlacementActivity::class.java))
        }
        voce(R.id.drawerTrashButton) { showStagingFolder() }
        voce(R.id.drawerSystemBinButton) { showSystemBin() }
        voce(R.id.drawerCheckFoldersButton) { openCheck(IgnoredRepository.Check.STRANGERS) }
        voce(R.id.drawerCheckPlacementButton) { openCheck(IgnoredRepository.Check.MISPLACED) }
        voce(R.id.drawerAdoptButton) { openOutput(DestinationsActivity.ACTION_ADOPT) }
        voce(R.id.drawerReorganizeButton) {
            startActivity(Intent(this, ReorganizeActivity::class.java))
        }
        voce(R.id.drawerSuspectButton) {
            startActivity(Intent(this, SuspectDatesActivity::class.java))
        }
        voce(R.id.drawerExportButton) { openOutput(DestinationsActivity.ACTION_EXPORT) }
        voce(R.id.drawerImportButton) { openOutput(DestinationsActivity.ACTION_IMPORT) }
        voce(R.id.drawerBackupButton) { openOutput(DestinationsActivity.ACTION_BACKUP) }
        voce(R.id.drawerDuplicatesButton) {
            queueLauncher.launch(Intent(this, DuplicatesActivity::class.java))
        }
        voce(R.id.drawerRepairDatesButton) { openCheck(IgnoredRepository.Check.DATES) }
        voce(R.id.drawerRestoreButton) { showRestoreDialog() }
        voce(R.id.drawerRescanButton) { confirmRescan() }
    }

    /**
     * Forces the full scan the ten-minute rest would otherwise skip.
     *
     * Needed when the world changed outside the app — a file manager moved
     * something, Google Photos emptied the bin — which our own records have
     * no way of noticing.
     */
    private fun confirmRescan() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rescan_title)
            .setMessage(R.string.rescan_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                inventory.forgetFullScan(PRIMARY_VOLUME)
                refreshFolders()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun wireActions() {
        wireStageGestures()
        tagButton.setOnClickListener { showTagDialog() }
        dateButton.setOnClickListener { toggleDateVerdict() }
        findViewById<Button>(R.id.fullScreenButton).setOnClickListener {
            setFullScreen(!fullScreen)
        }
        onBackPressedDispatcher.addCallback(this, exitFullScreen)
        printButton.setOnClickListener { togglePrintTag() }
        bindDrawer()

        stagingButton.setOnClickListener { showStagingFolder() }
        openExternalButton.setOnClickListener { openCurrentExternally() }
        undoButton.setOnClickListener { applyDecision { session.undoLastMove() } }
        applyButton.setOnClickListener { startApply() }
        findViewById<Button>(R.id.reloadButton).setOnClickListener { reload() }
        findViewById<Button>(R.id.pickRangeButton).setOnClickListener { pickDateRange() }
    }

    /**
     * Follows the finger, then commits or springs back.
     *
     * The photo moves with the drag instead of jumping when it ends, so the
     * gesture can be seen taking effect and abandoned halfway. Only swipes
     * act: tapping a side was ambiguous, since a strip on the left can mean
     * either where the finger starts or where it travels.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireStageGestures() {
        mediaStage.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginDrag(event)
                MotionEvent.ACTION_MOVE -> continueDrag(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                else -> false
            }
        }
    }

    private fun beginDrag(event: MotionEvent): Boolean {
        if (busy || session.current() == null) return false
        dragBlocked = false
        dragAtEdge = false
        preview.animate().cancel()
        previewAdjacent.animate().cancel()
        dragStartX = event.rawX
        dragStartY = event.rawY
        dragAxis = DragAxis.UNDECIDED
        return true
    }

    /**
     * Moves the photo with the finger. The axis is locked once the drag has
     * clearly committed to one, so a wobble cannot flip between advancing
     * and filing halfway through.
     */
    private fun continueDrag(event: MotionEvent): Boolean {
        if (dragBlocked) return true
        val dx = event.rawX - dragStartX
        val dy = event.rawY - dragStartY

        if (dragAxis == DragAxis.UNDECIDED) {
            if (max(abs(dx), abs(dy)) < AXIS_LOCK_PIXELS) return true
            dragAxis = if (abs(dx) > abs(dy)) DragAxis.HORIZONTAL else DragAxis.VERTICAL

            // Filing and deleting mean nothing in the deletion folder, where
            // the only sensible act is putting a photo back. The drag is
            // abandoned rather than moving the photo to no effect.
            if (dragAxis == DragAxis.HORIZONTAL && !canDecide()) {
                dragBlocked = true
                dragAxis = DragAxis.UNDECIDED
                return true
            }
            showAdjacent(dragAxis, dx, dy)
        }

        if (dragAxis == DragAxis.HORIZONTAL) {
            preview.translationX = dx
            preview.translationY = 0f
        } else {
            // Pulling where there is nothing to reach: the photo gives a
            // little and springs back, instead of sliding away to reveal an
            // empty frame and then jumping home.
            dragAtEdge = !canMoveVertically(dy)
            val travel = if (dragAtEdge) dy * EDGE_RESISTANCE else dy
            preview.translationY = travel
            preview.translationX = 0f
            // Vertical is paging: the neighbour travels with the current one.
            previewAdjacent.translationY = travel + adjacentRestingOffset
        }

        previewHintForDrag(dx, dy)
        return true
    }

    /**
     * Puts the neighbouring photo behind the current one.
     *
     * Vertical drags page, so the neighbour starts one screen away and
     * follows. Horizontal drags file the photo away, so the neighbour stays
     * still and is uncovered as the current one slides off.
     */
    private fun showAdjacent(axis: DragAxis, dx: Float, dy: Float) {
        val neighbour = when {
            axis == DragAxis.HORIZONTAL -> nextBitmap
            dy < 0 -> nextBitmap
            else -> previousBitmap
        } ?: return

        previewAdjacent.setImageBitmap(neighbour)
        previewAdjacent.visibility = View.VISIBLE
        adjacentRestingOffset = when {
            axis == DragAxis.HORIZONTAL -> 0f
            dy < 0 -> mediaStage.height.toFloat()
            else -> -mediaStage.height.toFloat()
        }
        previewAdjacent.translationX = 0f
        previewAdjacent.translationY = if (axis == DragAxis.HORIZONTAL) 0f else adjacentRestingOffset
    }

    /** Names the action the drag would trigger, fading in as it commits. */
    private fun previewHintForDrag(dx: Float, dy: Float) {
        if (dragAxis != DragAxis.HORIZONTAL) {
            actionFlash.alpha = 0f
            return
        }
        val label = if (dx < 0) R.string.flash_keep else R.string.flash_trash
        actionFlash.setText(label)
        actionFlash.alpha = min(1f, abs(dx) / commitThreshold())
    }

    /** True when there is a photo to reach in the direction being dragged. */
    private fun canMoveVertically(dy: Float): Boolean =
        if (dy < 0) session.canGoNext() else session.canGoPrevious()

    /** Distance past which releasing performs the action. */
    private fun commitThreshold(): Float =
        max(mediaStage.width, mediaStage.height) * COMMIT_FRACTION

    /** Decides between performing the action and returning the photo home. */
    private fun endDrag(): Boolean {
        val axis = dragAxis
        dragAxis = DragAxis.UNDECIDED
        if (axis == DragAxis.UNDECIDED) return true

        val travelled = if (axis == DragAxis.HORIZONTAL) preview.translationX else preview.translationY
        if (dragAtEdge || abs(travelled) < commitThreshold()) {
            springBack()
            return true
        }
        commitDrag(axis, travelled)
        return true
    }

    /** Abandoned drag: everything slides home and nothing is recorded. */
    private fun springBack() {
        actionFlash.animate().alpha(0f).setDuration(SETTLE_DURATION_MILLIS).start()
        preview.animate()
            .translationX(0f)
            .translationY(0f)
            .setDuration(SETTLE_DURATION_MILLIS)
            .withEndAction { previewAdjacent.visibility = View.INVISIBLE }
            .start()
        previewAdjacent.animate()
            .translationY(adjacentRestingOffset)
            .setDuration(SETTLE_DURATION_MILLIS)
            .start()
    }

    /** Carries the photo the rest of the way out, then performs the action. */
    private fun commitDrag(axis: DragAxis, travelled: Float) {
        val exitX = if (axis == DragAxis.HORIZONTAL) sign(travelled) * mediaStage.width else 0f
        val exitY = if (axis == DragAxis.VERTICAL) sign(travelled) * mediaStage.height else 0f

        previewAdjacent.animate()
            .translationY(0f)
            .setDuration(SETTLE_DURATION_MILLIS)
            .start()

        preview.animate()
            .translationX(exitX)
            .translationY(exitY)
            .setDuration(SETTLE_DURATION_MILLIS)
            .withEndAction {
                preview.translationX = 0f
                preview.translationY = 0f
                previewAdjacent.visibility = View.INVISIBLE
                performDragAction(axis, travelled)
            }
            .start()
    }

    /** Maps the completed drag to its action. */
    private fun performDragAction(axis: DragAxis, travelled: Float) {
        if (axis == DragAxis.HORIZONTAL) {
            if (travelled < 0) keepCurrent() else trashCurrent()
        } else {
            if (travelled < 0) goNext() else goPrevious()
        }
    }

    private fun goPrevious() {
        if (busy || !session.goPrevious()) return
        render()
    }

    private fun goNext() {
        if (busy || !session.goNext()) return
        render()
    }

    /**
     * Keeping a photo, wherever it is being kept from.
     *
     * In the bin the gesture keeps its meaning and changes its work: what
     * "keep" asks for there is that the photo not be thrown away, which
     * means putting it back where it came from. No second control is needed
     * to say the same thing, and the hand already knows this one.
     */
    private fun keepCurrent() {
        if (!canDecide()) return
        applyDecision {
            if (preferredSubtree == stagingPath()) session.restoreCurrent()
            else session.keepCurrent()
        }
        flashAction(getString(R.string.flash_keep))
    }

    /**
     * Sends the current photo to the bin, unless it is already in it.
     *
     * There, the gesture would queue a move to the folder the photo is
     * standing in: work that changes nothing, counted in the queue and
     * applied for no result. The bin has one act, and this is not it.
     */
    private fun trashCurrent() {
        if (preferredSubtree == stagingPath()) {
            return toast(getString(R.string.trash_already_here))
        }

        if (!canDecide()) return
        applyDecision { session.trashCurrent() }
        flashAction(getString(R.string.flash_trash))
    }

    /**
     * Names the action just performed and fades out.
     *
     * A gesture leaves no trace of itself, so without this the only evidence
     * of having filed or kept a photo would be that a different one is now
     * on screen.
     */
    private fun flashAction(message: String) {
        actionFlash.text = message
        actionFlash.animate().cancel()
        actionFlash.alpha = 1f
        actionFlash.animate()
            .alpha(0f)
            .setStartDelay(FLASH_HOLD_MILLIS)
            .setDuration(FLASH_FADE_MILLIS)
            .start()
    }

    /**
     * Inside the deletion folder the only sensible action is putting a photo
     * back. Keeping one there would mark it reviewed while leaving it queued
     * for deletion, which is a contradiction.
     */
    private fun canDecide(): Boolean = !busy && session.current() != null &&
            preferredSubtree != stagingPath()

    /** Android 13 needs only READ_MEDIA_IMAGES; no legacy storage branch. */
    private fun ensureReadPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) refreshFolders()
        else requestReadPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
    }

    /**
     * Rebuilds the source folder list from what actually holds photos, then
     * loads. Runs off the main thread: it scans one column of the whole
     * collection.
     */
    private fun refreshFolders() {
        setBusy(true)
        statusText.setText(R.string.status_loading)
        thread {
            // Our own inventory answers first, so the screen is usable
            // without waiting for the platform.
            val known = inventory.loadFolders().getOrNull().orEmpty()
            if (known.isNotEmpty()) runOnUiThread { onFoldersLoaded(Result.success(known)) }

            reconcileWithPlatform()
            val folders = inventory.loadFolders()
            runOnUiThread { onFoldersLoaded(folders) }
        }
    }

    /**
     * Brings the inventory in line with what the platform reports.
     *
     * A full pass rather than an incremental one: only comparing everything
     * reveals what has been deleted elsewhere, and having compared
     * everything there is nothing left for an incremental path to add.
     */
    /**
     * Brings the inventory in line with the platform, saying so as it goes.
     *
     * On a full archive this takes long enough that three dots are not an
     * answer: without a count there is no telling a slow pass from a stuck
     * one. The report at the end is the one moment the app can say what it
     * found, and it used to be thrown away.
     */
    private fun reconcileWithPlatform() {
        // Reading every photo on the device is worth doing, and not worth
        // doing again minutes later: coming back from standby should not
        // cost the same as opening the app for the first time.
        val since = System.currentTimeMillis() - inventory.lastFullScanAt(PRIMARY_VOLUME)
        if (since < FULL_SCAN_REST_MILLIS) return

        runOnUiThread { statusText.setText(R.string.status_reading) }
        val records = photoSource.listPhotos(null).getOrElse { error ->
            runOnUiThread { showError(error) }
            return
        }

        runOnUiThread {
            statusText.text = getString(R.string.status_reconciling, records.size)
        }
        inventory.reconcile(records, recordsAreComplete = true).fold(
            onSuccess = { (_, report) ->
                inventory.rememberFullScan(PRIMARY_VOLUME)
                runOnUiThread { showReconcileReport(report) }
            },
            onFailure = { error -> runOnUiThread { showError(error) } }
        )
    }

    /**
     * Fingerprints a few of the decided photos that have none yet.
     *
     * A handful per load rather than all at once: the work is only worth
     * doing for photos a decision has been made about, and even then it is
     * reading files, which has no business holding up a screen. Over a few
     * sessions the whole catalogued set ends up covered.
     *
     * What it buys: a photo renamed and moved outside the app is still
     * recognised, so the decision taken about it is not orphaned. Every
     * other criterion — name, size, date — describes the photo from outside
     * and can change while the photograph does not.
     */
    /** Opens the folders screen straight on one of its functions. */
    private fun openOutput(action: String) {
        startActivity(
            Intent(this, DestinationsActivity::class.java)
                .putExtra(DestinationsActivity.EXTRA_ACTION, action)
        )
    }

    /**
     * Offers to turn the folder this photo lives in into a category.
     *
     * Leafing through an archive, the folder is often the whole answer: a
     * hundred photos from one job, one holiday, one year, all deciding the
     * same way. Naming that folder a category and filing what is in it says
     * in one act what a hundred swipes would say one at a time.
     *
     * Nothing is moved and nothing is renamed: the photos stay exactly where
     * they are, and simply stop being unsorted.
     */
    private fun offerFolderAsCategory() {
        val photo = session.current() ?: return
        val folder = photo.relativePath.trim('/')
        if (folder.isEmpty()) return

        val existing = destinations.firstOrNull {
            it.relativePath.trim('/').equals(folder, ignoreCase = true)
        }
        if (existing != null) {
            return toast(getString(R.string.folder_category_exists, existing.label))
        }

        thread {
            val da = undecidedIn(folder)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (da.isEmpty()) return@runOnUiThread toast(
                    getString(R.string.folder_category_empty)
                )
                askFolderCategory(folder, da)
            }
        }
    }

    /** Photo ids sitting directly in [folder] that nobody has decided about. */
    private fun undecidedIn(folder: String): List<Long> {
        val decided = stateRepository.loadAll().getOrElse { emptyMap() }.keys

        return inventory.loadForAdoption().getOrElse { emptyList() }
            .filter { it.relativePath.trim('/').equals(folder, ignoreCase = true) }
            .map { it.photoId }
            .filterNot { it in decided }
    }

    /**
     * States what will happen and how many photos it will file.
     *
     * A category with this name already existing is not a reason to stop:
     * it is a reason not to make a second one. A split archive — two
     * Famiglia, half the photos under each — is the thing this app exists
     * to undo, and it must not create one itself.
     */
    private fun askFolderCategory(folder: String, photoIds: List<Long>) {
        val label = folder.substringAfterLast('/')
        val sameName = destinations.firstOrNull { it.label.equals(label, ignoreCase = true) }
        val message =
            if (sameName == null) getString(R.string.folder_category_message, label, photoIds.size)
            else getString(R.string.folder_category_join, sameName.label, photoIds.size)

        AlertDialog.Builder(this)
            .setTitle(R.string.folder_category_title)
            .setMessage(message)
            .setPositiveButton(
                if (sameName == null) R.string.folder_category_do
                else R.string.folder_category_join_do
            ) { _, _ ->
                createFolderCategory(label, folder, photoIds, sameName?.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Files every photo of the folder, creating the category unless
     * [existingId] names one that already carries this name.
     */
    private fun createFolderCategory(
        label: String,
        folder: String,
        photoIds: List<Long>,
        existingId: Long?
    ) {
        thread {
            // The folder is already where these photos live, so a category
            // made here must not add a year subfolder: filing must move
            // nothing. Joining an existing one changes nothing about it.
            val destinationId = existingId ?: destinationRepository.insert(
                label = label,
                relativePath = folder,
                yearSubfolder = false
            ).getOrElse { error ->
                return@thread runOnUiThread {
                    toast(getString(R.string.message_error, error.message.orEmpty()))
                }
            }

            val photos = inventory.loadRecords(photoIds).getOrElse { emptyList() }
            val filed = stateRepository
                .recordAll(photos, ReviewStatus.CATEGORIZED, destinationId)
                .getOrElse { 0 }

            runOnUiThread {
                offerUndoFiling(label, photos.map { it.photoId }, filed, existingId == null)
                reload()
            }
        }
    }

    /**
     * Says what was just filed and leaves a way to take it back.
     *
     * Filing a folder writes no files — the photos stay where they are — so
     * it does not go through the queue, which exists to review writes before
     * they happen. But it decides hundreds of photographs in one press, and
     * a decision that large should not be irreversible just because it was
     * cheap. Undoing is offered where the eye already is, and only until the
     * next thing happens.
     */
    private fun offerUndoFiling(
        label: String,
        photoIds: List<Long>,
        filed: Int,
        createdCategory: Boolean
    ) {
        Snackbar
            .make(
                findViewById(R.id.main),
                getString(R.string.folder_category_done, label, filed),
                Snackbar.LENGTH_INDEFINITE
            )
            .setDuration(UNDO_VISIBLE_MILLIS)
            .setAction(R.string.action_undo_filing) { undoFiling(photoIds, createdCategory, label) }
            .show()
    }

    /**
     * Puts the photos back to undecided, and removes a category made for
     * them if it was made only for them.
     *
     * A category created by that one press and then emptied would otherwise
     * stay behind as an empty folder nobody asked for.
     */
    private fun undoFiling(photoIds: List<Long>, createdCategory: Boolean, label: String) {
        thread {
            val forgotten = stateRepository.forgetAll(photoIds).getOrElse { 0 }
            if (createdCategory) {
                destinationRepository.loadAll().getOrElse { emptyList() }
                    .firstOrNull { it.label == label }
                    ?.let { destinationRepository.delete(it.id) }
            }
            runOnUiThread {
                toast(getString(R.string.folder_category_undone, forgotten))
                buildDestinationButtons()
                reload()
            }
        }
    }

    /**
     * Checks the category folders every time the app opens.
     *
     * Not the whole archive — that is the reconciliation, and it is slow
     * enough that it must not run unasked. This asks a narrower question, of
     * the folders that are meant to be in order: does everything in them
     * belong there, and is it where its category says it should be.
     *
     * It only reports. Nothing is moved, filed, or deleted without being
     * asked for, and the answer opens the queue where the work is shown.
     */
    private fun checkFiledFolders(asked: Boolean = false) {
        // Once per opening when it speaks up by itself, because the same
        // warning returning after every filter change would be noise, and
        // noise gets dismissed without being read. Asked for from the menu
        // it always runs, and always answers — including to say all is well,
        // which is the answer a question deserves.
        if (!asked) {
            if (filedFoldersChecked) return
            filedFoldersChecked = true
        }

        thread {
            val estranei = inventory.loadStrangersInDestinations()
                .getOrElse { emptyList() }
            val fuoriPosto = inventory.loadMisplaced().getOrElse { emptyList() }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (estranei.isEmpty() && fuoriPosto.isEmpty()) {
                    if (asked) toast(getString(R.string.filed_check_clean))
                    return@runOnUiThread
                }
                showFiledFolderProblems(estranei.size, fuoriPosto.size)
            }
        }
    }

    /**
     * Points at the check that can answer, rather than answering here.
     *
     * A dialog can say how many; only the check itself can say where it
     * looked, what it looked for, and what applying would do to which
     * photographs. Sending the user there is the whole of this dialog's job.
     */
    private fun showFiledFolderProblems(strangers: Int, misplaced: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.filed_check_title)
            .setMessage(getString(R.string.filed_check_message, strangers, misplaced))
            .apply {
                if (strangers > 0) setPositiveButton(R.string.filed_check_strangers) { _, _ ->
                    openCheck(IgnoredRepository.Check.STRANGERS)
                }
                if (misplaced > 0) setNeutralButton(R.string.filed_check_misplaced) { _, _ ->
                    openCheck(IgnoredRepository.Check.MISPLACED)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Opens the screen for one check, which is the same screen for all. */
    private fun openCheck(check: IgnoredRepository.Check) {
        queueLauncher.launch(
            Intent(this, CheckActivity::class.java)
                .putExtra(CheckActivity.EXTRA_CHECK, check.storedValue)
        )
    }

    /** Re-reads the marked dates, so the badge agrees with the database. */
    private fun refreshSuspectDates() {
        thread {
            val segnate = inventory.loadDateSuspect().getOrElse { return@thread }
            runOnUiThread {
                suspectDates = segnate
                findViewById<Button>(R.id.drawerSuspectButton).text =
                    getString(R.string.action_suspect_dates, segnate.size)
                render()
            }
        }
    }

    private fun takeFingerprints() {
        thread {
            val senza = inventory.loadNeedingHash(FINGERPRINTS_PER_LOAD)
                .getOrElse { return@thread }

            for (photo in senza) {
                val hash = photoSource.contentHash(photo).getOrNull() ?: continue
                inventory.recordHash(photo.photoId, hash)
            }
        }
    }

    /** Keeps the queue entry in the drawer honest about how much is waiting. */
    private fun refreshWaitingCount() {
        thread {
            val cestino = inventory.loadPendingTrash(ReviewSession.DELETION_STAGING_PATH)
                .getOrElse { emptyList() }.size
            val fuoriPosto = inventory.loadMisplaced().getOrElse { emptyList() }.size
            runOnUiThread {
                findViewById<Button>(R.id.drawerQueueButton).text =
                    getString(R.string.action_queue, cestino + fuoriPosto)
            }
        }
    }

    /** Says what the reconciliation actually changed, not just that it ran. */
    private fun showReconcileReport(report: PhotoInventory.Report) {
        val righe = listOfNotNull(
            getString(R.string.report_seen, report.seen),
            report.added.takeIf { it > 0 }?.let { getString(R.string.report_added, it) },
            report.rekeyed.takeIf { it > 0 }?.let { getString(R.string.report_rekeyed, it) },
            // Only what this scan is the first to miss. The rest — rows
            // given up long ago — is found missing again by every scan for
            // ever: not a measurement against any moment, just the size of
            // the graveyard, and reporting it made each reconciliation read
            // like a fresh loss. Measured once on this archive: of 692 such
            // rows, 656 were photos genuinely deleted and the other 36 had
            // already been superseded by a copy that carries their decision.
            // Nothing in that number was ever actionable.
            report.newlyMissing.takeIf { it > 0 }
                ?.let { getString(R.string.report_missing, it) }
        )
        lastReport = righe.joinToString(" · ")
        // The status line is overwritten by the load that follows, so this
        // only shows while nothing is about to open over it.
        if (sourceAsked) toast(lastReport.orEmpty())
    }

    /**
     * Keeps only what sits under a source root.
     *
     * A phone holds photos in dozens of folders and most are nobody's
     * archive: naming the roots is what keeps the work bounded. The staging
     * folder is always in, whatever the roots say, or the photos waiting to
     * be deleted would become unreachable.
     */
    private fun <T> withinSourceRoots(items: List<T>, path: (T) -> String): List<T> {
        val roots = settings.effectiveSourceRoots()
        if (roots.isEmpty()) return items

        return items.filter { item ->
            val relative = path(item).trim('/')
            // The bin is never part of the work: a photo waiting there has
            // been decided, and offering it again as something to sort is
            // asking a question that already has an answer.
            if (relative.startsWith(stagingPath(), ignoreCase = true)) return@filter false

            roots.any { relative.startsWith(it, ignoreCase = true) }
        }
    }

    @JvmName("foldersWithinSourceRoots")
    private fun withinSourceRoots(folders: List<FolderSummary>): List<FolderSummary> =
        withinSourceRoots(folders) { it.relativePath }

    @JvmName("photosWithinSourceRoots")
    private fun withinSourceRoots(photos: List<PhotoRecord>): List<PhotoRecord> =
        withinSourceRoots(photos) { it.relativePath }

    /**
     * Chooses where the archive lives, by ticking folders rather than typing.
     *
     * The branches offered are rebuilt from the folders that hold photos:
     * the platform reports only leaves, and an archive is chosen by its
     * trunk. Ticking one takes it and everything underneath.
     */
    private fun editSourceRoots(atStartup: Boolean = false) {
        val candidates = FolderTree.candidates(allFolders)
        if (candidates.isEmpty()) {
            toast(getString(R.string.roots_none))
            if (atStartup) reload()
            return
        }

        val form = LayoutInflater.from(this).inflate(R.layout.dialog_folder_tree, null)
        lastReport?.let {
            form.findViewById<TextView>(R.id.folderTreeHint).text =
                getString(R.string.roots_hint_with_report, it)
        }
        val wholeDevice = form.findViewById<CheckBox>(R.id.wholeDeviceCheck)
        val list = form.findViewById<ListView>(R.id.folderTree)

        val chosen = settings.sourceRoots.toMutableSet()
        wholeDevice.isChecked = settings.wholeDeviceAsSource
        val adapter = FolderTreeAdapter(this, candidates, chosen)
        list.adapter = adapter
        adapter.revealSelection()

        adapter.setEnabled(!wholeDevice.isChecked)
        wholeDevice.setOnCheckedChangeListener { _, checked -> adapter.setEnabled(!checked) }

        var salvato = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.roots_title)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                salvato = true
                saveSourceRoots(chosen, wholeDevice.isChecked)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // Backing out keeps the scope from last time rather than leaving an
        // empty screen; saving reloads through the folders it refreshes.
        dialog.setOnDismissListener {
            sourcePicker = null
            if (!salvato && atStartup) reload()
        }
        sourcePicker = dialog
        dialog.show()
    }

    /** Stores the chosen folders and reloads what is now in scope. */
    private fun saveSourceRoots(chosen: Set<String>, wholeDevice: Boolean) {
        settings.wholeDeviceAsSource = wholeDevice
        settings.sourceRoots = chosen.sorted()

        preferredSubtree = null
        refreshFolders()
    }

    private fun onFoldersLoaded(result: Result<List<FolderSummary>>) {
        setBusy(false)
        val folders = result.getOrElse {
            showError(it)
            emptyList()
        }

        allFolders = folders
        stagingCount = folders
            .filter { it.relativePath == ReviewSession.DELETION_STAGING_PATH }
            .sumOf { it.photoCount }
        stagingButton.text = getString(R.string.action_staging, stagingCount)

        val offered = withinSourceRoots(folders)
        // Roots that match nothing look exactly like an empty phone, and the
        // difference is the one thing the user can act on.
        if (offered.isEmpty() && folders.isNotEmpty()) {
            toast(getString(R.string.folder_outside_roots))
        }

        showWorkingFolder()

        // First folders of the session: ask what is in scope, and load only
        // once there is an answer.
        if (!sourceAsked) {
            sourceAsked = true
            editSourceRoots(atStartup = true)
            return
        }
        if (sourcePicker != null) return

        reload()
    }

    /** The button says what is being worked on, or that it is everything. */
    private fun showWorkingFolder() {
        val branch = preferredSubtree
        folderButton.text = if (branch == null) getString(R.string.folder_all)
        else getString(R.string.folder_chosen, branch.substringAfterLast('/'))
        showCascadeCaption(null)
    }

    /**
     * Says out loud what the indent already shows: period and state speak
     * only about the folder above them, and about [photoCount] photos.
     */
    private fun showCascadeCaption(photoCount: Int?) {
        val dove = preferredSubtree?.substringAfterLast('/')
            ?: getString(R.string.cascade_everything)
        // Null while the folder has changed and nothing has been counted yet:
        // announcing zero photos would be stating a number nobody measured.
        val quante = photoCount?.toString() ?: getString(R.string.cascade_counting)

        findViewById<TextView>(R.id.cascadeCaption).text =
            getString(R.string.cascade_caption, dove, quante)
    }

    /**
     * Gives the photograph the screen, or gives the selector back.
     *
     * The controls that choose what to look at are read once and are then in
     * the way of the thing they chose. Leaving is the back gesture, because
     * that is what the hand reaches for.
     */
    private fun setFullScreen(on: Boolean) {
        fullScreen = on
        findViewById<View>(R.id.selectorBlock).visibility =
            if (on) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.fullScreenButton).setText(
            if (on) R.string.action_full_screen_exit else R.string.action_full_screen
        )
        exitFullScreen.isEnabled = on

        // The button that got us here goes away with the block it sits in,
        // so the way out has to be said out loud once.
        if (on) toast(getString(R.string.full_screen_hint))
    }

    /**
     * Chooses the branch to work on, from the same tree as the scope.
     *
     * Only what is in scope is offered: the source says where the archive
     * is, and there is no reason to work outside it without saying so first.
     */
    private fun editWorkingFolder() {
        val inScope = withinSourceRoots(allFolders)
        val candidates = FolderTree.candidates(inScope)
        if (candidates.isEmpty()) return toast(getString(R.string.roots_none))

        val form = LayoutInflater.from(this).inflate(R.layout.dialog_folder_tree, null)
        val everything = form.findViewById<CheckBox>(R.id.wholeDeviceCheck)
        everything.setText(R.string.folder_all)
        everything.isChecked = preferredSubtree == null

        val chosen = LinkedHashSet<String>()
        preferredSubtree?.let(chosen::add)

        // The two are alternatives and must behave like it in both
        // directions. Picking a folder while "everything" stayed ticked
        // looked like it had worked and was thrown away on save: the
        // checkbox won, silently, and the whole device was loaded instead.
        val adapter = FolderTreeAdapter(
            this, candidates, chosen, singleChoice = true,
            onPicked = { everything.isChecked = chosen.isEmpty() }
        )
        form.findViewById<ListView>(R.id.folderTree).adapter = adapter
        adapter.revealSelection()

        adapter.setEnabled(!everything.isChecked)
        everything.setOnCheckedChangeListener { _, checked ->
            adapter.setEnabled(!checked)
            if (checked && chosen.isNotEmpty()) {
                chosen.clear()
                adapter.notifyDataSetChanged()
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.folder_title)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                // The folder wins when there is one: a tick left over from
                // before must never discard a choice just made.
                val scelta = chosen.firstOrNull()
                if (scelta != preferredSubtree) changeFilter { preferredSubtree = scelta }
                showWorkingFolder()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Rebuilds the period list from the photos actually present.
     *
     * Offering every recent month regardless of content meant a month could
     * be chosen that holds nothing, which looks exactly like a broken
     * filter. Months are derived from the same resolved capture date used to
     * pick the year folder, so the list also exposes at a glance when those
     * dates are wrong.
     */
    private fun rebuildPeriodSpinner(
        photos: List<PhotoRecord>,
        states: Map<Long, PhotoStateRepository.StoredState>
    ) {
        val counts = LinkedHashMap<PhotoFilter.Period.Month, Int>()
        val reviewed = LinkedHashMap<PhotoFilter.Period.Month, Int>()
        val calendar = Calendar.getInstance()
        for (photo in photos) {
            calendar.timeInMillis = photo.dateTakenMillis
            val month = PhotoFilter.Period.Month(
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.YEAR)
            )
            counts[month] = (counts[month] ?: 0) + 1
            // Keyed by our own id, not the platform's: they stopped being
            // the same number in v5, and looking one up by the other found
            // nothing, so a month stayed red however much had been done.
            if (states.containsKey(photo.photoId)) {
                reviewed[month] = (reviewed[month] ?: 0) + 1
            }
        }

        val ordered = counts.keys.sortedWith(
            compareByDescending<PhotoFilter.Period.Month> { it.year }.thenByDescending { it.month }
        )

        val rebuilt = ArrayList<PhotoFilter.Period>()
        val items = ArrayList<TintedSpinnerAdapter.Item>()
        rebuilt.add(PhotoFilter.Period.Any)
        items.add(TintedSpinnerAdapter.Item(getString(R.string.period_any, photos.size), null))

        for (month in ordered) {
            rebuilt.add(month)
            val total = counts[month] ?: 0
            val seen = reviewed[month] ?: 0
            items.add(monthItem(month, seen, total))
        }
        items.add(TintedSpinnerAdapter.Item(getString(R.string.period_custom_range), null))

        // Rebuilding the adapter emits a selection event, which would load
        // again and rebuild again. Touch it only when the months changed.
        if (rebuilt == offeredPeriods && periodSpinner.adapter != null) return

        offeredPeriods.clear()
        offeredPeriods.addAll(rebuilt)
        applyPeriodSpinner(items)
    }

    /**
     * Builds one month row: green when every photo has been reviewed, red
     * when none has, amber in between. The symbol carries the same meaning,
     * so the row still reads without relying on colour.
     */
    private fun monthItem(
        month: PhotoFilter.Period.Month,
        seen: Int,
        total: Int
    ): TintedSpinnerAdapter.Item {
        val done = total > 0 && seen == total
        val markRes = when {
            done -> R.string.month_mark_done
            seen == 0 -> R.string.month_mark_todo
            else -> R.string.month_mark_partial
        }
        val colorRes = when {
            done -> R.color.month_done
            seen == 0 -> R.color.month_todo
            else -> R.color.month_partial
        }
        val label = getString(
            R.string.period_month,
            "%02d-%d".format(month.month, month.year),
            getString(markRes),
            seen,
            total
        )
        return TintedSpinnerAdapter.Item(label, colorRes)
    }

    /**
     * Swaps the adapter. Safe to call because the listener reloads only when
     * the chosen period actually differs, so the selection event that a
     * rebuild produces cannot start a loop.
     */
    private fun applyPeriodSpinner(items: List<TintedSpinnerAdapter.Item>) {
        periodSpinner.adapter = TintedSpinnerAdapter(this, items)

        val index = offeredPeriods.indexOf(preferredPeriod)
        when {
            index >= 0 -> periodSpinner.setSelection(index)
            preferredPeriod is PhotoFilter.Period.Range -> periodSpinner.setSelection(items.size - 1)
            else -> {
                // The chosen month no longer exists in this folder.
                preferredPeriod = PhotoFilter.Period.Any
                periodSpinner.setSelection(0)
            }
        }

        periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val chosen = offeredPeriods.getOrNull(pos) ?: customRange ?: PhotoFilter.Period.Any
                if (chosen == preferredPeriod) return
                changeFilter { preferredPeriod = chosen }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun buildScopeSpinner() {
        scopeSpinner.adapter = simpleAdapter(
            listOf(
                getString(R.string.scope_to_sort),
                getString(R.string.scope_sorted),
                getString(R.string.scope_every)
            )
        )
        scopeSpinner.onItemSelectedListener = reloadOnSelection()
    }

    private fun simpleAdapter(items: List<String>) = ArrayAdapter(
        this, android.R.layout.simple_spinner_item, items
    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

    private fun reloadOnSelection() = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = reload()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    /** One button per destination, rebuilt whenever the list may have changed. */
    private fun buildDestinationButtons() {
        destinations = destinationRepository.loadAll().getOrElse {
            showError(it)
            emptyList()
        }
        destinationActions.removeAllViews()

        // Working in the bin, filing into a category is not what is wanted,
        // and nothing else is either: the one act there is the keep gesture,
        // which puts the photo back where it came from. Twelve category
        // buttons beside it would only invite a mis-tap.
        if (preferredSubtree == stagingPath()) {
            updateButtonState()
            return
        }

        for (destination in destinations) {
            val button = Button(this)
            button.text = destination.label
            button.setOnClickListener { applyDecision { session.fileCurrent(destination) } }
            destinationActions.addView(button)
        }
        updateButtonState()
    }

    /** The deletion folder, without its trailing separator. */
    private fun stagingPath(): String = ReviewSession.DELETION_STAGING_PATH.trim('/')

    /** Builds the filter currently selected in the two spinners. */
    private fun currentFilter(): PhotoFilter {
        val scope = PhotoFilter.ReviewScope.entries
            .getOrElse(scopeSpinner.selectedItemPosition) { PhotoFilter.ReviewScope.ALL }
        return PhotoFilter(preferredPeriod, scope)
    }

    /** Asks for the two ends of a custom range, then reloads. */
    private fun pickDateRange() {
        val today = Calendar.getInstance()
        DatePickerDialog(this, { _, fromYear, fromMonth, fromDay ->
            val from = Calendar.getInstance().apply { clear(); set(fromYear, fromMonth, fromDay) }
            DatePickerDialog(this, { _, toYear, toMonth, toDay ->
                val to = Calendar.getInstance().apply { clear(); set(toYear, toMonth, toDay) }
                val range = PhotoFilter.Period.Range(from.timeInMillis, to.timeInMillis)
                customRange = range
                preferredPeriod = range
                reload()
            }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
                .show()
        }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
            .show()
    }

    /**
     * Applies a change of filter, protecting work not yet applied.
     *
     * Changing folder or period reloads the working set and empties the
     * queue, so without asking, decisions would vanish unannounced.
     *
     * Only queued moves raise the question. Marking a photo as kept writes
     * a record and nothing else: it is already saved, cannot fail, and has
     * no counterpart to apply, so asking about it would be asking about
     * work already done.
     */
    private fun changeFilter(change: () -> Unit) {
        if (session.pendingCount == 0) {
            change()
            buildDestinationButtons()
            reload()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pending_title)
            .setMessage(getString(R.string.pending_message, session.pendingCount))
            .setCancelable(false)
            .setPositiveButton(R.string.pending_apply) { _, _ ->
                change()
                buildDestinationButtons()
                startApply()
            }
            .setNegativeButton(R.string.pending_drop_all) { _, _ ->
                session.discardQueue().onFailure { showError(it) }
                change()
                buildDestinationButtons()
                reload()
            }
            .show()
    }

    /** Loads photos matching the filter, off the main thread. */
    private fun reload() {
        setBusy(true)
        statusText.setText(R.string.status_loading)
        val filter = currentFilter()
        val generation = ++loadGeneration

        thread {
            // Everything, then narrowed here: a branch is not something the
            // platform can be asked for, since it only knows leaves.
            val photos = photoSource.listPhotos(null)
                .mapCatching { inventory.reconcile(it).getOrThrow().first }
            val states = stateRepository.loadAll()
            val tags = tagRepository.loadAssignments()
            val origins = stateRepository.loadOriginalPaths()
            runOnUiThread {
                if (generation != loadGeneration) return@runOnUiThread
                onLoaded(filter, photos, states, tags, origins)
            }
        }
    }

    /** Applies the review-state axis of the filter and shows the first photo. */
    private fun onLoaded(
        filter: PhotoFilter,
        photos: Result<List<PhotoRecord>>,
        states: Result<Map<Long, PhotoStateRepository.StoredState>>,
        tags: Result<Map<Long, List<String>>>,
        origins: Result<Map<Long, PhotoStateRepository.Location>>
    ) {
        setBusy(false)
        val loaded = photos.getOrElse { return showError(it) }
        val branch = preferredSubtree
        val loadedPhotos = if (branch == stagingPath()) {
            // Chosen on purpose: then it is exactly what should be shown.
            loaded.filter { FolderTree.isWithin(it.relativePath, branch) }
        } else if (branch != null) {
            loaded.filter { FolderTree.isWithin(it.relativePath, branch) }
        } else {
            withinSourceRoots(loaded)
        }
        val loadedStates = states.getOrElse { return showError(it) }
        val loadedTags = tags.getOrElse { return showError(it) }
        val loadedOrigins = origins.getOrElse { return showError(it) }

        showCascadeCaption(loadedPhotos.size)
        rebuildPeriodSpinner(loadedPhotos, loadedStates)
        val period = currentFilter().resolvePeriodMillis()
        val inPeriod = if (period == null) loadedPhotos
        else loadedPhotos.filter { it.dateTakenMillis in period }
        // The bin is exempt from the state filter. Everything in it was
        // decided against by definition, so filtering by "not yet decided"
        // empties the screen and leaves no way to take anything back out.
        val visible =
            if (branch == stagingPath()) inPeriod
            else inPeriod.filter { filter.accepts(loadedStates[it.photoId]?.status) }

        ratingByPhoto = ratings.loadAll().getOrElse { emptyMap() }
        takeFingerprints()
        refreshSuspectDates()
        checkFiledFolders()
        session.load(visible, loadedStates, loadedTags, loadedOrigins)
        render()
        if (visible.isEmpty()) explainEmptyResult(loadedPhotos, inPeriod.size, period)
    }

    /**
     * Says why nothing came back. Which of the three filters emptied the set
     * is impossible to guess from an empty screen, and the folder, the
     * period and the review state each hide photos for different reasons.
     */
    private fun explainEmptyResult(
        inFolder: List<PhotoRecord>,
        inPeriod: Int,
        period: LongRange?
    ) {
        val dateFormat = SimpleDateFormat(DAY_PATTERN, Locale.ITALY)
        val periodText = if (period == null) {
            getString(R.string.period_any_plain)
        } else {
            "${dateFormat.format(Date(period.first))} - ${dateFormat.format(Date(period.last))}"
        }
        val dates = inFolder.map { it.dateTakenMillis }
        val presentText = if (dates.isEmpty()) "-" else
            "${dateFormat.format(Date(dates.min()))} - ${dateFormat.format(Date(dates.max()))}"

        statusText.text = getString(
            R.string.status_empty_detail,
            inFolder.size, periodText, inPeriod, presentText, describeSources(inFolder)
        )
    }

    /**
     * Counts which source supplied each date.
     *
     * Names the branch responsible when dates look wrong: EXIF, the file
     * name, or the file timestamp are three different failure modes and an
     * aggregate date range alone cannot tell them apart.
     */
    private fun describeSources(photos: List<PhotoRecord>): String {
        val counts = photos.groupingBy { it.dateSource }.eachCount()
        return CaptureDateResolver.Source.entries.joinToString(" ") { source ->
            "${describeDateSource(source)}=${counts[source] ?: 0}"
        }
    }

    /** Runs a decision, reports failure, and refreshes the screen. */
    private fun applyDecision(decision: () -> Result<Unit>) {
        if (busy) return
        decision().onFailure { showError(it) }
        render()
    }

    /** Adds a tag to the current photo, or removes one already present. */
    /**
     * Draws the five stars once, and wires each to the rating it stands for.
     *
     * Tapping a star that is already lit clears the rating: without that,
     * one star would be a trap, since there would be no way back to none.
     */
    private fun buildStarBar() {
        for (stars in 1..RatingRepository.MAX_STARS) {
            val star = TextView(this).apply {
                textSize = STAR_TEXT_SIZE
                setPadding(STAR_PADDING, STAR_PADDING, STAR_PADDING, STAR_PADDING)
                contentDescription = getString(R.string.star_hint, stars)
                setOnClickListener { rateCurrent(stars) }
            }
            starBar.addView(star)
        }
    }

    /** Records the rating and stays on the photo. */
    private fun rateCurrent(stars: Int) {
        val photo = session.current() ?: return
        val current = ratingByPhoto[photo.photoId] ?: RatingRepository.UNRATED
        val wanted = if (current == stars) RatingRepository.UNRATED else stars

        ratings.rate(photo.photoId, wanted).fold(
            onSuccess = {
                ratingByPhoto = ratingByPhoto.toMutableMap().apply {
                    if (wanted == RatingRepository.UNRATED) remove(photo.photoId)
                    else put(photo.photoId, wanted)
                }
                if (wanted == RatingRepository.UNRATED) toast(getString(R.string.rating_cleared))
                showStars()
            },
            onFailure = { showError(it) }
        )
    }

    /** Lights the stars the photo in view has earned. */
    private fun showStars() {
        val photo = session.current()
        val stars = photo?.let { ratingByPhoto[it.photoId] } ?: RatingRepository.UNRATED

        for (index in 0 until starBar.childCount) {
            val star = starBar.getChildAt(index) as TextView
            star.setText(if (index < stars) R.string.star_filled else R.string.star_empty)
            star.isEnabled = photo != null
        }
    }

    /**
     * Marks the photo for printing, and does not move on.
     *
     * Deciding a photo is worth printing is not deciding where it goes: the
     * two happen at different moments, and one advancing would take the
     * photo away before the other could be made.
     */
    private fun togglePrintTag() {
        val name = getString(R.string.print_tag_name)
        val outcome = if (session.currentTags().any { it.equals(name, ignoreCase = true) }) {
            session.untagCurrent(name)
        } else {
            session.tagCurrent(name).map { }
        }
        outcome.fold(onSuccess = { render() }, onFailure = { showError(it) })
    }

    private fun showTagDialog() {
        if (session.current() == null) return
        val form = LayoutInflater.from(this).inflate(R.layout.dialog_tag, null)
        val field = form.findViewById<EditText>(R.id.tagName)

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.tag_add_title)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                session.tagCurrent(field.text.toString()).onFailure { showError(it) }
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)

        val existing = session.currentTags()
        if (existing.isNotEmpty()) {
            builder.setNeutralButton(R.string.tag_remove_title) { _, _ -> showRemoveTagDialog(existing) }
        }
        builder.show()
    }

    private fun showRemoveTagDialog(existing: List<String>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.tag_remove_title)
            .setItems(existing.toTypedArray()) { _, which ->
                session.untagCurrent(existing[which]).onFailure { showError(it) }
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Offers the two shapes of restore, then asks to confirm.
     *
     * Restores are applied straight away rather than queued: the queue is
     * there to review filing and deletion before they happen, while a
     * restore is already the correction of a decision. Queueing a correction
     * would turn one intention into two steps.
     */
    private fun showRestoreDialog() {
        val restorable = session.restorableCount()
        if (restorable == 0) return toast(getString(R.string.restore_none))

        // Only the photo in view. Undoing the archiving of everything the
        // filters happen to be showing is too much to hang on one button:
        // the history in photo_paths keeps every original location, so
        // anything older can still be reconstructed deliberately.
        if (session.currentRestorePath() == null) return toast(getString(R.string.restore_none))

        confirmRestore(session.buildRestorePlan(onlyCurrent = true))
    }

    /** Final confirmation, naming exactly how many photos will move. */
    /**
     * Restoring undoes the most and used to say the least.
     *
     * It moves photos back to where the app first saw them, gives back their
     * original names — undoing the stamp — and marks them kept. "All" means
     * every photo the current filter has loaded, which with no filter is the
     * archive. A count alone was not an answer.
     */
    private fun confirmRestore(plan: List<ReviewSession.PendingMove>) {
        if (plan.isEmpty()) return toast(getString(R.string.restore_none))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.restore_confirm_title, plan.size))
            .setAdapter(MovePreviewAdapter.forMoves(this, plan, photoSource), null)
            .setPositiveButton(R.string.restore_do) { _, _ -> startRestore(plan) }
            .setNeutralButton(R.string.restore_explain) { _, _ -> explainRestore(plan.size) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Spells out what restoring gives back, and what it takes away. */
    private fun explainRestore(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setMessage(getString(R.string.restore_explained, count))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Moving files still needs the system consent, restore included. */
    private fun startRestore(plan: List<ReviewSession.PendingMove>) {
        pendingRestore = plan
        try {
            requestRestoreConsent.launch(
                IntentSenderRequest.Builder(mover.buildConsent(plan)).build()
            )
        } catch (error: Exception) {
            pendingRestore = emptyList()
            showError(error)
        }
    }

    private fun applyRestore() {
        val plan = pendingRestore
        pendingRestore = emptyList()
        if (plan.isEmpty()) return

        setBusy(true)
        statusText.setText(R.string.status_applying)
        thread {
            val result = mover.applyAll(plan)
            val applied = plan.filterNot { it in result.failed }
            session.commitRestores(applied)
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.restore_done, result.succeeded))
                refreshFolders()
            }
        }
    }

    /**
     * Jumps straight to the folder holding photos waiting to be deleted.
     *
     * The reminder matters: the app only gathers them there. Emptying the
     * folder from Google Photos is what removes the backed-up copy too, and
     * deleting it anywhere else would leave the cloud copy behind.
     */
    private fun showStagingFolder() {
        // Only what the bin holds. Photos decided against whose file has not
        // moved yet are work still to do, not bin contents, and work still
        // to do belongs to the queue: two screens answering the same
        // question differently is how a reader stops trusting either.
        markAlreadyHandedOver()

        setBusy(true)
        thread {
            val contenuto = photoSource.binContents(stagingPath() + "/").getOrNull()
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                showBinContents(contenuto)
            }
        }
    }

    /**
     * The photos decided against that really are still waiting.
     *
     * One that Android is already holding in its own bin has been dealt
     * with, however it got there — by this app before it learned to write
     * the handover down, or by the user from the gallery. Those are marked
     * as done rather than offered again, which is the difference between a
     * check that can be trusted and one that repeats itself.
     */
    private fun markAlreadyHandedOver() {
        thread {
            val waiting = inventory.loadPendingTrash(ReviewSession.DELETION_STAGING_PATH)
                .getOrElse { emptyList() }
            if (waiting.isEmpty()) return@thread

            val inAndroidBin = photoSource.systemBinIds().getOrElse { emptySet() }
            for (photo in waiting.filter { it.platformId in inAndroidBin }) {
                stateRepository.recordSystemBin(
                    photo.photoId, photo.relativePath, photo.displayName
                )
            }
        }
    }

    /**
     * Tells the truth about the bin, including what Android has hidden.
     *
     * A deleted photo on this phone is not gone: it is renamed, kept in
     * place for thirty days, and hidden from every ordinary query. The app
     * was therefore reporting an empty bin over a bin that was full, and the
     * photos in it were recoverable the whole time — right up until the day
     * they would not be.
     */
    private fun showBinContents(contents: MediaStorePhotoSource.BinContents?) {
        if (contents == null || contents.total == 0) {
            return toast(getString(R.string.staging_empty))
        }
        if (contents.trashed == 0) {
            changeFilter { preferredSubtree = stagingPath() }
            return toast(getString(R.string.staging_hint))
        }

        val quando = contents.earliestExpiryMillis
            ?.let { SimpleDateFormat(DATE_PATTERN, Locale.ITALY).format(Date(it)) }
            ?: getString(R.string.bin_expiry_unknown)

        // Told as two separate facts, because they are: what the app's bin
        // holds, and what has since been handed to Android and is counting
        // down. Leading with the total made the app's own bin appear to be
        // talking about somebody else's — the file is still in our folder,
        // but the decision about it is no longer ours.
        val message =
            if (contents.visible == 0) getString(R.string.bin_message_all_trashed, contents.trashed, quando)
            else getString(
                R.string.bin_message_mixed, contents.visible, contents.trashed, quando
            )

        AlertDialog.Builder(this)
            .setTitle(R.string.bin_title)
            .setMessage(message)
            .setPositiveButton(R.string.bin_open) { _, _ ->
                changeFilter { preferredSubtree = stagingPath() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Offers to gather the photos marked for deletion that never moved.
     *
     * They are the drift between a decision and its consequence: the queue
     * dies with the session while the decision is written at once, so a
     * refused or interrupted batch leaves photos the app believes are in the
     * bin and are not. Gathering them is what makes the bin mean something —
     * and what lets a change of mind still find them.
     */

    /**
     * Hands the current photo to whichever gallery the user prefers, which
     * is where a photo can actually be deleted from the cloud as well.
     */
    private fun openCurrentExternally() {
        val photo = session.current() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photoSource.uriFor(photo), IMAGE_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast(getString(R.string.open_external_failed))
        }
    }

    /** Redraws everything that depends on the current position. */
    private fun render() {
        val photo = session.current()
        statusText.text = getString(
            R.string.status_counter,
            if (session.size == 0) 0 else session.currentIndex + 1,
            session.size,
            session.pendingCount
        )
        applyButton.text = getString(R.string.action_apply, session.pendingCount)
        updateButtonState()

        if (photo == null) {
            preview.setImageDrawable(null)
            applyStateFrame(null)
            stateBadge.visibility = View.GONE
            photoFolder.text = ""
            photoInfo.setText(R.string.status_empty)
            photoTags.text = ""
            showStars()
            return
        }
        swipeLegend.setText(
            if (preferredSubtree == stagingPath()) R.string.swipe_legend_bin
            else R.string.swipe_legend
        )
        applyStateFrame(session.currentStatus())
        showStateBadge(session.currentStatus(), photo)
        photoFolder.text = getString(R.string.photo_folder, photo.relativePath.trim('/'))
        photoInfo.text = describe(photo)
        showStars()
        printButton.isEnabled = !busy && session.current() != null
        photoTags.text = getString(
            R.string.photo_tags,
            session.currentTags().joinToString(", ").ifEmpty { getString(R.string.tag_none) }
        )
        loadPreview(photo)
    }

    private fun updateButtonState() {
        val hasPhoto = session.current() != null

        val canDecide = canDecide()
        tagButton.isEnabled = !busy && hasPhoto
        dateButton.isEnabled = !busy && hasPhoto
        dateButton.setText(
            if (session.current()?.photoId in suspectDates) R.string.action_date_right
            else R.string.action_date_wrong
        )
        openExternalButton.isEnabled = !busy && hasPhoto
        stagingButton.isEnabled = !busy && stagingCount > 0
        undoButton.isEnabled = !busy && session.pendingCount > 0
        applyButton.isEnabled = !busy && session.pendingCount > 0
        for (index in 0 until destinationActions.childCount) {
            destinationActions.getChildAt(index).isEnabled = canDecide
        }
    }

    /**
     * Records, or withdraws, that this photograph's date is wrong.
     *
     * Nothing is renamed and nothing is moved. A date can be well formed and
     * still untrue — a photo received through WhatsApp is stamped with the
     * day it was sent — and no amount of reading the file will show it. Only
     * the person looking at the picture knows, so the verdict is stored and
     * the photo can be found again when there is a way to put it right.
     */
    private fun toggleDateVerdict() {
        val photo = session.current() ?: return
        val wrong = photo.photoId !in suspectDates

        inventory.markDateSuspect(photo.photoId, wrong).fold(
            onSuccess = {
                suspectDates =
                    if (wrong) suspectDates + photo.photoId else suspectDates - photo.photoId
                toast(
                    getString(
                        if (wrong) R.string.date_marked_wrong else R.string.date_marked_right
                    )
                )
                render()
            },
            onFailure = { toast(getString(R.string.message_error, it.message.orEmpty())) }
        )
    }

    /**
     * Writes the decision onto the photograph itself.
     *
     * The coloured frame says a decision exists; it cannot say which one,
     * and the category is exactly what the user needs to read while leafing
     * through. Colour is never left to carry the meaning by itself.
     */
    private fun showStateBadge(status: ReviewStatus?, photo: PhotoRecord) {
        val stato = when (status) {
            ReviewStatus.CATEGORIZED -> currentDestinationLabel()
            ReviewStatus.KEPT -> getString(R.string.photo_state_kept)
            ReviewStatus.TRASHED -> getString(R.string.photo_state_trashed)
            null -> null
        }
        val dubbia = photo.photoId in suspectDates
        val testo = when {
            stato != null && dubbia -> getString(R.string.badge_with_date, stato)
            stato != null -> stato
            dubbia -> getString(R.string.badge_date_suspect)
            else -> null
        }
        stateBadge.text = testo.orEmpty()
        stateBadge.visibility = if (testo == null) View.GONE else View.VISIBLE
    }

    /** Name, size, capture date and recorded decision for one photo. */
    private fun describe(photo: PhotoRecord): String {
        val taken = SimpleDateFormat(DATE_PATTERN, Locale.ITALY).format(Date(photo.dateTakenMillis))
        val megabytes = photo.sizeBytes / BYTES_PER_MEGABYTE
        val detail = "%.1f MB · %s (%s) · %s".format(
            megabytes, taken, describeDateSource(photo.dateSource), describeStatus()
        )
        return getString(R.string.photo_info, photo.displayName, detail)
    }

    /**
     * Shows where the date came from. A photo filed by file timestamp is
     * likely to land in the wrong year, so the user must be able to see it.
     */
    private fun describeDateSource(source: CaptureDateResolver.Source): String = when (source) {
        CaptureDateResolver.Source.EXIF -> getString(R.string.date_source_exif)
        CaptureDateResolver.Source.FILENAME -> getString(R.string.date_source_filename)
        CaptureDateResolver.Source.ESTIMATED -> getString(R.string.date_source_estimated)
        CaptureDateResolver.Source.FILE_TIMESTAMP -> getString(R.string.date_source_file)
    }

    private fun describeStatus(): String = when (session.currentStatus()) {
        null -> getString(R.string.photo_state_unseen)
        ReviewStatus.KEPT -> getString(R.string.photo_state_kept)
        ReviewStatus.TRASHED -> getString(R.string.photo_state_trashed)
        ReviewStatus.CATEGORIZED ->
            getString(R.string.photo_state_categorized, currentDestinationLabel())
    }

    /**
     * Name of the folder a photo was filed into.
     *
     * Falls back to a placeholder rather than showing nothing when the
     * destination has since been deleted: the photo is still filed
     * somewhere, and the path history records where.
     */
    private fun currentDestinationLabel(): String {
        val id = session.currentDestinationId() ?: return getString(R.string.destination_unknown)
        return destinations.firstOrNull { it.id == id }?.label
            ?: getString(R.string.destination_unknown)
    }

    /**
     * Frames the photo in the colour of its recorded state, so the decision
     * already taken is visible while leafing through rather than only in the
     * line of text above.
     */
    private fun applyStateFrame(status: ReviewStatus?) {
        val colorRes = when (status) {
            ReviewStatus.KEPT -> R.color.state_kept
            ReviewStatus.CATEGORIZED -> R.color.state_categorized
            ReviewStatus.TRASHED -> R.color.state_trashed
            null -> null
        }
        if (colorRes == null) {
            mediaStage.foreground = null
            return
        }
        val widthPx = (FRAME_WIDTH_DP * resources.displayMetrics.density).toInt()
        mediaStage.foreground = GradientDrawable().apply {
            setStroke(widthPx, ContextCompat.getColor(this@MainActivity, colorRes))
        }
    }

    /** Decodes the thumbnail off the main thread, ignoring stale results. */
    private fun loadPreview(photo: PhotoRecord) {
        // Already showing this photo: reloading it would only make it blink.
        if (preview.tag == photo.platformId && preview.drawable != null) {
            preloadNeighbours()
            return
        }
        preview.tag = photo.platformId

        val cached = thumbnails[photo.platformId]
        if (cached != null) {
            // Decoded already, most likely as the neighbour of the photo just
            // left behind: show it at once so the gesture has no aftermath.
            preview.setImageBitmap(cached)
            preloadNeighbours()
            return
        }

        preview.setImageDrawable(null)
        thread {
            val bitmap = photoSource.loadThumbnail(photo, THUMBNAIL_EDGE_PIXELS).getOrNull()
            runOnUiThread {
                if (bitmap != null) thumbnails[photo.platformId] = bitmap
                showPreviewIfCurrent(photo.platformId, bitmap)
                preloadNeighbours()
            }
        }
    }

    /**
     * Decodes the photos either side in the background.
     *
     * A drag has to reveal the neighbour the instant it starts; decoding it
     * on touch would show an empty frame for the first moments of every
     * gesture.
     */
    private fun preloadNeighbours() {
        val wanted = listOfNotNull(session.peek(1), session.peek(-1))
            .filterNot { thumbnails.containsKey(it.platformId) }
        if (wanted.isEmpty()) return

        thread {
            val decoded = wanted.mapNotNull { photo ->
                photoSource.loadThumbnail(photo, THUMBNAIL_EDGE_PIXELS).getOrNull()?.let { photo.platformId to it }
            }
            runOnUiThread { decoded.forEach { thumbnails[it.first] = it.second } }
        }
    }

    /** Drops a bitmap that finished decoding after the user moved on. */
    private fun showPreviewIfCurrent(mediaId: Long, bitmap: Bitmap?) {
        if (preview.tag != mediaId) return
        if (bitmap != null) preview.setImageBitmap(bitmap)
    }

    /**
     * States what the queue would write, then asks for consent.
     *
     * Counts say how much; only the list says what. Filing a hundred photos
     * by swiping is exactly the situation where one of them went to the
     * wrong category without being noticed, and this is the last moment that
     * can still be seen.
     */
    private fun startApply() {
        val moves = session.queuedMoves
        if (moves.isEmpty()) return toast(getString(R.string.message_queue_empty))

        // Some of them the system will refuse, and saying so afterwards
        // reads as a fault of the app rather than a rule of the platform.
        val bloccate = moves.count { PhotoSourcePort.isImmovable(it.photo.relativePath) }
        val titolo = if (bloccate == 0) getString(R.string.reorganize_list_title, moves.size)
        else getString(R.string.apply_title_blocked, moves.size, bloccate)

        AlertDialog.Builder(this)
            .setTitle(titolo)
            .setAdapter(MovePreviewAdapter.forMoves(this, moves, photoSource), null)
            .setPositiveButton(R.string.reorganize_apply) { _, _ -> requestMoveConsent() }
            // Discarding belonged only to the dialog that interrupts a
            // filter change, which meant the queue could be emptied by
            // accident but never on purpose.
            .setNeutralButton(R.string.pending_drop_all) { _, _ -> confirmDiscardQueue() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        if (bloccate > 0) explainImmovable(bloccate)
    }

    /** Says why some photos cannot move, before the attempt rather than after. */
    private fun explainImmovable(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.immovable_title)
            .setMessage(getString(R.string.immovable_explained, count))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Empties the queue after saying what that costs.
     *
     * Photos queued for a category or for deletion go back to unseen; those
     * merely kept stay kept, because keeping is a decision already made and
     * nothing was going to be written for it anyway.
     */
    private fun confirmDiscardQueue() {
        val queued = session.pendingCount
        AlertDialog.Builder(this)
            .setTitle(R.string.discard_title)
            .setMessage(getString(R.string.discard_message, queued))
            .setPositiveButton(R.string.pending_drop_all) { _, _ ->
                session.discardQueue().fold(
                    onSuccess = {
                        toast(getString(R.string.discard_done, queued))
                        reload()
                    },
                    onFailure = { showError(it) }
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Asks for one consent covering the whole queue. */
    private fun requestMoveConsent() {
        val moves = session.queuedMoves
        if (moves.isEmpty()) return toast(getString(R.string.message_queue_empty))
        try {
            requestMoveConsent.launch(
                IntentSenderRequest.Builder(mover.buildConsent(moves)).build()
            )
        } catch (error: Exception) {
            showError(error)
        }
    }

    /** Performs the queued moves after consent was granted. */
    private fun applyMoves() {
        setBusy(true)
        statusText.setText(R.string.status_applying)
        val moves = session.queuedMoves

        thread {
            val result = mover.applyAll(moves)
            runOnUiThread { onMovesApplied(result) }
        }
    }

    /**
     * Reports the batch and keeps what failed for another attempt.
     *
     * Everything that fails now is worth retrying: the one failure that was
     * permanent — a photo in another app's folder — is copied instead of
     * moved, so it no longer fails at all.
     */
    private fun onMovesApplied(result: BatchMover.BatchResult) {
        setBusy(false)
        if (result.failed.isEmpty()) {
            toast(getString(R.string.message_applied, result.succeeded, result.totalMillis))
        } else {
            toast(
                getString(
                    R.string.message_partial,
                    result.succeeded,
                    result.failed.size,
                    result.firstError ?: ""
                )
            )
        }

        session.retainFailedMoves(result.failed)
        // Both kinds end in the same place, for the same reason: the app
        // cannot move these files, and leaving the original beside its copy
        // would double the archive instead of ordering it.
        val toHandOver = result.forSystemBin + result.copiedOriginals
        if (toHandOver.isNotEmpty()) {
            systemBin.offer(toHandOver, copied = result.copiedOriginals.size)
        }
        refreshFolders()
    }

    /**
     * Shows what is in Android's bin, and says whose bin it is.
     *
     * Read-only on purpose: the app puts photos in there when it has no
     * other way, and can count what is inside, but emptying or restoring
     * belongs to the gallery. Saying that plainly is the point of the
     * screen — two bins that behaved differently and looked the same would
     * be worse than one.
     */
    private fun showSystemBin() {
        setBusy(true)
        thread {
            val contenuto = photoSource.systemBinContents().getOrNull()
            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (contenuto == null || contenuto.trashed == 0) {
                    return@runOnUiThread toast(getString(R.string.system_bin_empty))
                }

                val quando = contenuto.earliestExpiryMillis
                    ?.let { SimpleDateFormat(DATE_PATTERN, Locale.ITALY).format(Date(it)) }
                    ?: getString(R.string.bin_expiry_unknown)
                AlertDialog.Builder(this)
                    .setTitle(R.string.action_system_bin_plain)
                    .setMessage(
                        getString(R.string.system_bin_report, contenuto.trashed, quando)
                    )
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }
        }
    }

    private fun refuseConsent() {
        toast(getString(R.string.message_consent_refused))
        updateButtonState()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        // Spinning rather than counting: reading the archive is one query to
        // the platform, and there is no honest number to put on it.
        if (value) progress.startSpinning() else progress.stop()
        updateButtonState()
    }

    private fun showError(error: Throwable) {
        toast(getString(R.string.message_error, error.message ?: error::class.java.simpleName))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
