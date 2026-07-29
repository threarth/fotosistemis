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
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
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

        /** Preselected source folder when it exists: the camera roll. */
        const val DEFAULT_SOURCE_FOLDER = "DCIM/Camera/"

        const val DATE_PATTERN = "dd/MM/yyyy HH:mm"
        const val DAY_PATTERN = "dd/MM/yyyy"
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val IMAGE_MIME_TYPE = "image/*"

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

    private lateinit var mediaRepository: MediaStoreRepository
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var destinationRepository: DestinationRepository
    private lateinit var mover: BatchMover
    private lateinit var session: ReviewSession

    private lateinit var preview: ImageView
    private lateinit var statusText: TextView
    private lateinit var photoInfo: TextView
    private lateinit var photoTags: TextView
    private lateinit var destinationActions: LinearLayout
    private lateinit var folderSpinner: Spinner
    private lateinit var periodSpinner: Spinner
    private lateinit var scopeSpinner: Spinner
    private lateinit var mediaStage: View
    private lateinit var previewAdjacent: ImageView
    private lateinit var actionFlash: TextView
    private lateinit var tagButton: Button
    private lateinit var restoreButton: Button
    private lateinit var stagingButton: Button
    private lateinit var openExternalButton: Button
    private lateinit var undoButton: Button
    private lateinit var applyButton: Button

    private val offeredPeriods = ArrayList<PhotoFilter.Period>()

    /** Folders offered by the spinner; null at index 0 means "every folder". */
    private val offeredFolders = ArrayList<MediaStoreRepository.FolderSummary?>()

    /**
     * Folder the user last chose. Kept separately from the spinner so that
     * rebuilding the list after a batch does not silently change what is
     * being reviewed.
     */
    private var preferredFolder: MediaStoreRepository.FolderSummary? = null
    private var destinations: List<DestinationRepository.Destination> = emptyList()
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

    private val previousBitmap: Bitmap? get() = session.peek(-1)?.let { thumbnails[it.mediaId] }
    private val nextBitmap: Bitmap? get() = session.peek(1)?.let { thumbnails[it.mediaId] }

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) reload() else statusText.setText(R.string.status_permission_needed)
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

    /** Restores bypass the queue, so they carry their own consent. */
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
        applySystemBarInsets()

        mediaRepository = MediaStoreRepository(this)
        stateRepository = PhotoStateRepository(this)
        tagRepository = TagRepository(this)
        destinationRepository = DestinationRepository(this)
        mover = BatchMover(this, mediaRepository, stateRepository)
        session = ReviewSession(stateRepository, tagRepository, AppSettings(this))

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
        if (::session.isInitialized) refreshStagingCount()
    }

    /**
     * Re-counts the photos awaiting deletion without disturbing the review.
     *
     * A full reload would lose the current position every time the app is
     * reopened, which is too high a price for a number in a button.
     */
    private fun refreshStagingCount() {
        thread {
            val folders = mediaRepository.queryFolders().getOrNull() ?: return@thread
            val counted = folders
                .filter { it.relativePath == ReviewSession.DELETION_STAGING_PATH }
                .sumOf { it.photoCount }
            runOnUiThread { onStagingCountRefreshed(counted) }
        }
    }

    private fun onStagingCountRefreshed(counted: Int) {
        if (counted == stagingCount) return
        stagingCount = counted
        stagingButton.text = getString(R.string.action_staging, stagingCount)
        updateButtonState()

        // Emptied while we were looking at it: what is on screen no longer
        // exists, so the list has to be rebuilt.
        if (preferredFolder?.relativePath == ReviewSession.DELETION_STAGING_PATH) refreshFolders()
    }

    private fun bindViews() {
        preview = findViewById(R.id.preview)
        statusText = findViewById(R.id.statusText)
        photoInfo = findViewById(R.id.photoInfo)
        photoTags = findViewById(R.id.photoTags)
        destinationActions = findViewById(R.id.destinationActions)
        folderSpinner = findViewById(R.id.folderSpinner)
        periodSpinner = findViewById(R.id.periodSpinner)
        scopeSpinner = findViewById(R.id.scopeSpinner)
        mediaStage = findViewById(R.id.mediaStage)
        previewAdjacent = findViewById(R.id.previewAdjacent)
        actionFlash = findViewById(R.id.actionFlash)
        tagButton = findViewById(R.id.tagButton)
        restoreButton = findViewById(R.id.restoreButton)
        stagingButton = findViewById(R.id.stagingButton)
        openExternalButton = findViewById(R.id.openExternalButton)
        undoButton = findViewById(R.id.undoButton)
        applyButton = findViewById(R.id.applyButton)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun wireActions() {
        wireStageGestures()
        tagButton.setOnClickListener { showTagDialog() }
        restoreButton.setOnClickListener { showRestoreDialog() }
        stagingButton.setOnClickListener { showStagingFolder() }
        openExternalButton.setOnClickListener { openCurrentExternally() }
        undoButton.setOnClickListener { applyDecision { session.undoLastMove() } }
        applyButton.setOnClickListener { startApply() }
        findViewById<Button>(R.id.reloadButton).setOnClickListener { reload() }
        findViewById<Button>(R.id.pickRangeButton).setOnClickListener { pickDateRange() }
        findViewById<Button>(R.id.destinationsButton).setOnClickListener {
            startActivity(Intent(this, DestinationsActivity::class.java))
        }
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

    private fun keepCurrent() {
        if (!canDecide()) return
        applyDecision { session.keepCurrent() }
        flashAction(getString(R.string.flash_keep))
    }

    private fun trashCurrent() {
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
            preferredFolder?.relativePath != ReviewSession.DELETION_STAGING_PATH

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
            val folders = mediaRepository.queryFolders()
            runOnUiThread { onFoldersLoaded(folders) }
        }
    }

    private fun onFoldersLoaded(result: Result<List<MediaStoreRepository.FolderSummary>>) {
        setBusy(false)
        val folders = result.getOrElse {
            showError(it)
            emptyList()
        }

        stagingCount = folders
            .filter { it.relativePath == ReviewSession.DELETION_STAGING_PATH }
            .sumOf { it.photoCount }
        stagingButton.text = getString(R.string.action_staging, stagingCount)

        offeredFolders.clear()
        val labels = ArrayList<String>()
        offeredFolders.add(null)
        labels.add(getString(R.string.folder_all))
        for (folder in folders) {
            offeredFolders.add(folder)
            // Photos on a memory card are marked: they stay on their own
            // volume when filed, because MediaStore cannot move a file
            // across volumes without copying every byte.
            val name = if (folder.isRemovable) {
                getString(R.string.folder_removable, folder.relativePath)
            } else {
                folder.relativePath
            }
            labels.add(getString(R.string.folder_entry, name, folder.photoCount))
        }

        if (preferredFolder == null) {
            preferredFolder = folders.firstOrNull {
                !it.isRemovable && it.relativePath == DEFAULT_SOURCE_FOLDER
            }
        }

        folderSpinner.adapter = simpleAdapter(labels)
        val restoredIndex = offeredFolders.indexOfFirst { it?.sameAs(preferredFolder) == true }
        if (restoredIndex >= 0) folderSpinner.setSelection(restoredIndex)
        folderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val chosen = offeredFolders.getOrNull(pos)
                if (chosen?.sameAs(preferredFolder) == true) return
                if (chosen == null && preferredFolder == null) return
                changeFilter { preferredFolder = chosen }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        reload()
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
        photos: List<MediaStoreRepository.Photo>,
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
            if (states.containsKey(photo.mediaId)) {
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
                getString(R.string.scope_all),
                getString(R.string.scope_unseen),
                getString(R.string.scope_uncategorized)
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
        for (destination in destinations) {
            val button = Button(this)
            button.text = destination.label
            button.setOnClickListener { applyDecision { session.fileCurrent(destination) } }
            destinationActions.addView(button)
        }
        updateButtonState()
    }

    /**
     * Folder currently selected, or null for every folder. Matching is exact,
     * so a folder never includes its own subfolders.
     */
    private fun currentFolder(): MediaStoreRepository.FolderSummary? =
        offeredFolders.getOrNull(folderSpinner.selectedItemPosition)

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
            reload()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pending_title)
            .setMessage(getString(R.string.pending_message, session.pendingCount))
            .setCancelable(false)
            .setPositiveButton(R.string.pending_apply) { _, _ ->
                change()
                startApply()
            }
            .setNegativeButton(R.string.pending_drop_all) { _, _ ->
                session.discardQueue().onFailure { showError(it) }
                change()
                reload()
            }
            .show()
    }

    /** Loads photos matching the filter, off the main thread. */
    private fun reload() {
        setBusy(true)
        statusText.setText(R.string.status_loading)
        val filter = currentFilter()
        val folder = currentFolder()
        val generation = ++loadGeneration

        thread {
            val photos = mediaRepository.queryPhotos(folder)
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
        photos: Result<List<MediaStoreRepository.Photo>>,
        states: Result<Map<Long, PhotoStateRepository.StoredState>>,
        tags: Result<Map<Long, List<String>>>,
        origins: Result<Map<Long, String>>
    ) {
        setBusy(false)
        val loadedPhotos = photos.getOrElse { return showError(it) }
        val loadedStates = states.getOrElse { return showError(it) }
        val loadedTags = tags.getOrElse { return showError(it) }
        val loadedOrigins = origins.getOrElse { return showError(it) }

        rebuildPeriodSpinner(loadedPhotos, loadedStates)
        val period = currentFilter().resolvePeriodMillis()
        val inPeriod = if (period == null) loadedPhotos
        else loadedPhotos.filter { it.dateTakenMillis in period }
        val visible = inPeriod.filter { filter.accepts(loadedStates[it.mediaId]?.status) }

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
        inFolder: List<MediaStoreRepository.Photo>,
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
    private fun describeSources(photos: List<MediaStoreRepository.Photo>): String {
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

        val options = ArrayList<String>()
        val plans = ArrayList<List<ReviewSession.PendingMove>>()

        if (session.currentRestorePath() != null) {
            options.add(getString(R.string.restore_current))
            plans.add(session.buildRestorePlan(onlyCurrent = true))
        }
        options.add(getString(R.string.restore_all, restorable))
        plans.add(session.buildRestorePlan(onlyCurrent = false))

        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setItems(options.toTypedArray()) { _, which -> confirmRestore(plans[which]) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Final confirmation, naming exactly how many photos will move. */
    private fun confirmRestore(plan: List<ReviewSession.PendingMove>) {
        if (plan.isEmpty()) return toast(getString(R.string.restore_none))
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_title)
            .setMessage(getString(R.string.restore_confirm, plan.size))
            .setPositiveButton(android.R.string.ok) { _, _ -> startRestore(plan) }
            .setNegativeButton(R.string.action_cancel, null)
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
        if (stagingCount == 0) return toast(getString(R.string.staging_empty))
        val index = offeredFolders.indexOfFirst {
            it?.relativePath == ReviewSession.DELETION_STAGING_PATH
        }
        if (index < 0) return toast(getString(R.string.staging_empty))

        // Selecting the folder is enough: the spinner listener reloads and
        // asks about unapplied work. Setting preferredFolder here first
        // would make that listener see no change and skip the reload.
        folderSpinner.setSelection(index)
        toast(getString(R.string.staging_hint))
    }

    /**
     * Hands the current photo to whichever gallery the user prefers, which
     * is where a photo can actually be deleted from the cloud as well.
     */
    private fun openCurrentExternally() {
        val photo = session.current() ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(photo.uri, IMAGE_MIME_TYPE)
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
            photoInfo.setText(R.string.status_empty)
            photoTags.text = ""
            return
        }
        applyStateFrame(session.currentStatus())
        photoInfo.text = describe(photo)
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
        openExternalButton.isEnabled = !busy && hasPhoto
        stagingButton.isEnabled = !busy && stagingCount > 0
        restoreButton.isEnabled = !busy && (session.currentRestorePath() != null ||
                session.restorableCount() > 0)
        undoButton.isEnabled = !busy && session.pendingCount > 0
        applyButton.isEnabled = !busy && session.pendingCount > 0
        for (index in 0 until destinationActions.childCount) {
            destinationActions.getChildAt(index).isEnabled = canDecide
        }
    }

    /** Name, size, capture date and recorded decision for one photo. */
    private fun describe(photo: MediaStoreRepository.Photo): String {
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
        CaptureDateResolver.Source.FILE_TIMESTAMP -> getString(R.string.date_source_file)
    }

    private fun describeStatus(): String = when (session.currentStatus()) {
        null -> getString(R.string.photo_state_unseen)
        ReviewStatus.KEPT -> getString(R.string.photo_state_kept)
        ReviewStatus.TRASHED -> getString(R.string.photo_state_trashed)
        ReviewStatus.CATEGORIZED -> getString(R.string.photo_state_categorized)
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
    private fun loadPreview(photo: MediaStoreRepository.Photo) {
        // Already showing this photo: reloading it would only make it blink.
        if (preview.tag == photo.mediaId && preview.drawable != null) {
            preloadNeighbours()
            return
        }
        preview.tag = photo.mediaId

        val cached = thumbnails[photo.mediaId]
        if (cached != null) {
            // Decoded already, most likely as the neighbour of the photo just
            // left behind: show it at once so the gesture has no aftermath.
            preview.setImageBitmap(cached)
            preloadNeighbours()
            return
        }

        preview.setImageDrawable(null)
        thread {
            val bitmap = mediaRepository.loadThumbnail(photo).getOrNull()
            runOnUiThread {
                if (bitmap != null) thumbnails[photo.mediaId] = bitmap
                showPreviewIfCurrent(photo.mediaId, bitmap)
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
            .filterNot { thumbnails.containsKey(it.mediaId) }
        if (wanted.isEmpty()) return

        thread {
            val decoded = wanted.mapNotNull { photo ->
                mediaRepository.loadThumbnail(photo).getOrNull()?.let { photo.mediaId to it }
            }
            runOnUiThread { decoded.forEach { thumbnails[it.first] = it.second } }
        }
    }

    /** Drops a bitmap that finished decoding after the user moved on. */
    private fun showPreviewIfCurrent(mediaId: Long, bitmap: Bitmap?) {
        if (preview.tag != mediaId) return
        if (bitmap != null) preview.setImageBitmap(bitmap)
    }

    /** Asks for one consent covering the whole queue. */
    private fun startApply() {
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
        refreshFolders()
    }

    private fun refuseConsent() {
        toast(getString(R.string.message_consent_refused))
        updateButtonState()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateButtonState()
    }

    private fun showError(error: Throwable) {
        toast(getString(R.string.message_error, error.message ?: error::class.java.simpleName))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
