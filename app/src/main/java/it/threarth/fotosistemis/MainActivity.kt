package it.threarth.fotosistemis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
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

/**
 * The review screen: one photo at a time, with navigation that decides
 * nothing and buttons that decide something.
 *
 * Only wires views to ReviewSession. Ordering, decisions and the queue live
 * there; MediaStore and SQLite access live in their repositories.
 */
class MainActivity : AppCompatActivity() {

    private companion object {

        /** How many recent months the period spinner offers. */
        const val MONTHS_OFFERED = 12

        /** Preselected source folder when it exists: the camera roll. */
        const val DEFAULT_SOURCE_FOLDER = "DCIM/Camera/"

        const val DATE_PATTERN = "dd/MM/yyyy HH:mm"
        const val MONTH_PATTERN = "MM-yyyy"
        const val DAY_PATTERN = "dd/MM/yyyy"
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
        const val IMAGE_MIME_TYPE = "image/*"
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
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var keepButton: Button
    private lateinit var trashButton: Button
    private lateinit var tagButton: Button
    private lateinit var restoreButton: Button
    private lateinit var stagingButton: Button
    private lateinit var openExternalButton: Button
    private lateinit var undoButton: Button
    private lateinit var applyButton: Button

    private val offeredPeriods = ArrayList<PhotoFilter.Period>()

    /** Folders offered by the spinner; null at index 0 means "every folder". */
    private val offeredFolders = ArrayList<String?>()

    /**
     * Folder the user last chose. Kept separately from the spinner so that
     * rebuilding the list after a batch does not silently change what is
     * being reviewed.
     */
    private var preferredFolder: String? = DEFAULT_SOURCE_FOLDER
    private var destinations: List<DestinationRepository.Destination> = emptyList()
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

        buildPeriodSpinner()
        buildScopeSpinner()
        wireActions()
        ensureReadPermission()
    }

    /** Destinations may have changed in the other screen. */
    override fun onResume() {
        super.onResume()
        buildDestinationButtons()
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
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        keepButton = findViewById(R.id.keepButton)
        trashButton = findViewById(R.id.trashButton)
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
        previousButton.setOnClickListener { if (session.goPrevious()) render() }
        nextButton.setOnClickListener { if (session.goNext()) render() }
        keepButton.setOnClickListener { applyDecision { session.keepCurrent() } }
        trashButton.setOnClickListener { applyDecision { session.trashCurrent() } }
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
            .firstOrNull { it.relativePath == ReviewSession.DELETION_STAGING_PATH }
            ?.photoCount ?: 0
        stagingButton.text = getString(R.string.action_staging, stagingCount)

        offeredFolders.clear()
        val labels = ArrayList<String>()
        offeredFolders.add(null)
        labels.add(getString(R.string.folder_all))
        for (folder in folders) {
            offeredFolders.add(folder.relativePath)
            labels.add(getString(R.string.folder_entry, folder.relativePath, folder.photoCount))
        }

        folderSpinner.adapter = simpleAdapter(labels)
        val restoredIndex = offeredFolders.indexOf(preferredFolder)
        if (restoredIndex >= 0) folderSpinner.setSelection(restoredIndex)
        folderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                preferredFolder = offeredFolders.getOrNull(pos)
                reload()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        reload()
    }

    /** Offers "any period", the last months as mm-yyyy, then the custom range. */
    private fun buildPeriodSpinner() {
        val labels = ArrayList<String>()
        offeredPeriods.clear()

        offeredPeriods.add(PhotoFilter.Period.Any)
        labels.add(getString(R.string.period_any))

        val monthFormat = SimpleDateFormat(MONTH_PATTERN, Locale.ITALY)
        val cursor = Calendar.getInstance()
        repeat(MONTHS_OFFERED) {
            offeredPeriods.add(
                PhotoFilter.Period.Month(cursor.get(Calendar.MONTH) + 1, cursor.get(Calendar.YEAR))
            )
            labels.add(monthFormat.format(cursor.time))
            cursor.add(Calendar.MONTH, -1)
        }

        labels.add(getString(R.string.period_custom_range))
        periodSpinner.adapter = simpleAdapter(labels)
        periodSpinner.onItemSelectedListener = reloadOnSelection()
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
    private fun currentFolder(): String? =
        offeredFolders.getOrNull(folderSpinner.selectedItemPosition)

    /** Builds the filter currently selected in the two spinners. */
    private fun currentFilter(): PhotoFilter {
        val periodIndex = periodSpinner.selectedItemPosition
        val period = when {
            periodIndex in offeredPeriods.indices -> offeredPeriods[periodIndex]
            else -> customRange ?: PhotoFilter.Period.Any
        }
        val scope = PhotoFilter.ReviewScope.entries
            .getOrElse(scopeSpinner.selectedItemPosition) { PhotoFilter.ReviewScope.ALL }
        return PhotoFilter(period, scope)
    }

    /** Asks for the two ends of a custom range, then reloads. */
    private fun pickDateRange() {
        val today = Calendar.getInstance()
        DatePickerDialog(this, { _, fromYear, fromMonth, fromDay ->
            val from = Calendar.getInstance().apply { clear(); set(fromYear, fromMonth, fromDay) }
            DatePickerDialog(this, { _, toYear, toMonth, toDay ->
                val to = Calendar.getInstance().apply { clear(); set(toYear, toMonth, toDay) }
                customRange = PhotoFilter.Period.Range(from.timeInMillis, to.timeInMillis)
                periodSpinner.setSelection(offeredPeriods.size)
                reload()
            }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
                .show()
        }, today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
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

        val period = filter.resolvePeriodMillis()
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
            getString(R.string.period_any)
        } else {
            "${dateFormat.format(Date(period.first))} - ${dateFormat.format(Date(period.last))}"
        }
        val dates = inFolder.map { it.dateTakenMillis }
        val presentText = if (dates.isEmpty()) "-" else
            "${dateFormat.format(Date(dates.min()))} - ${dateFormat.format(Date(dates.max()))}"

        statusText.text = getString(
            R.string.status_empty_detail, inFolder.size, periodText, inPeriod, presentText
        )
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
        val index = offeredFolders.indexOf(ReviewSession.DELETION_STAGING_PATH)
        if (index < 0) return toast(getString(R.string.staging_empty))

        preferredFolder = ReviewSession.DELETION_STAGING_PATH
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
            photoInfo.setText(R.string.status_empty)
            photoTags.text = ""
            return
        }
        photoInfo.text = describe(photo)
        photoTags.text = getString(
            R.string.photo_tags,
            session.currentTags().joinToString(", ").ifEmpty { getString(R.string.tag_none) }
        )
        loadPreview(photo)
    }

    private fun updateButtonState() {
        val hasPhoto = session.current() != null

        /*
         * Inside the deletion folder the only sensible action is putting a
         * photo back. Keeping one there would mark it reviewed while leaving
         * it queued for deletion, which is a contradiction, and filing it
         * elsewhere is what restore already does.
         */
        val inStaging = preferredFolder == ReviewSession.DELETION_STAGING_PATH
        val canDecide = hasPhoto && !inStaging
        previousButton.isEnabled = !busy && session.canGoPrevious()
        nextButton.isEnabled = !busy && session.canGoNext()
        keepButton.isEnabled = !busy && canDecide
        trashButton.isEnabled = !busy && canDecide
        tagButton.isEnabled = !busy && hasPhoto
        openExternalButton.isEnabled = !busy && hasPhoto
        stagingButton.isEnabled = !busy && stagingCount > 0
        restoreButton.isEnabled = !busy && (session.currentRestorePath() != null ||
                session.restorableCount() > 0)
        undoButton.isEnabled = !busy && session.pendingCount > 0
        applyButton.isEnabled = !busy && session.pendingCount > 0
        for (index in 0 until destinationActions.childCount) {
            destinationActions.getChildAt(index).isEnabled = !busy && canDecide
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

    /** Decodes the thumbnail off the main thread, ignoring stale results. */
    private fun loadPreview(photo: MediaStoreRepository.Photo) {
        preview.setImageDrawable(null)
        preview.tag = photo.mediaId
        thread {
            val bitmap = mediaRepository.loadThumbnail(photo).getOrNull()
            runOnUiThread { showPreviewIfCurrent(photo.mediaId, bitmap) }
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
