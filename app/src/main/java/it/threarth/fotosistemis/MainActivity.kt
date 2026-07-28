package it.threarth.fotosistemis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
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

        const val DATE_PATTERN = "dd/MM/yyyy HH:mm"
        const val MONTH_PATTERN = "MM-yyyy"
        const val BYTES_PER_MEGABYTE = 1024.0 * 1024.0
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
    private lateinit var periodSpinner: Spinner
    private lateinit var scopeSpinner: Spinner
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var keepButton: Button
    private lateinit var trashButton: Button
    private lateinit var tagButton: Button
    private lateinit var undoButton: Button
    private lateinit var applyButton: Button

    private val offeredPeriods = ArrayList<PhotoFilter.Period>()
    private var destinations: List<DestinationRepository.Destination> = emptyList()
    private var customRange: PhotoFilter.Period.Range? = null
    private var busy = false

    private val requestReadPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) reload() else statusText.setText(R.string.status_permission_needed)
        }

    /** Consent for changing files; only after this can a move succeed. */
    private val requestMoveConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) applyMoves()
            else refuseConsent()
        }

    /** The trash request performs the deletion itself once granted. */
    private val requestTrashConsent =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) onTrashGranted() else refuseConsent()
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
        session = ReviewSession(stateRepository, tagRepository)

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
        periodSpinner = findViewById(R.id.periodSpinner)
        scopeSpinner = findViewById(R.id.scopeSpinner)
        previousButton = findViewById(R.id.previousButton)
        nextButton = findViewById(R.id.nextButton)
        keepButton = findViewById(R.id.keepButton)
        trashButton = findViewById(R.id.trashButton)
        tagButton = findViewById(R.id.tagButton)
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
        undoButton.setOnClickListener { applyDecision { session.undoLastAction() } }
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

        if (granted) reload() else requestReadPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
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
        if (busy) return
        setBusy(true)
        statusText.setText(R.string.status_loading)
        val filter = currentFilter()

        thread {
            val photos = mediaRepository.queryPhotos(filter.resolvePeriodMillis())
            val states = stateRepository.loadAll()
            val tags = tagRepository.loadAssignments()
            runOnUiThread { onLoaded(filter, photos, states, tags) }
        }
    }

    /** Applies the review-state axis of the filter and shows the first photo. */
    private fun onLoaded(
        filter: PhotoFilter,
        photos: Result<List<MediaStoreRepository.Photo>>,
        states: Result<Map<Long, PhotoStateRepository.StoredState>>,
        tags: Result<Map<Long, List<String>>>
    ) {
        setBusy(false)
        val loadedPhotos = photos.getOrElse { return showError(it) }
        val loadedStates = states.getOrElse { return showError(it) }
        val loadedTags = tags.getOrElse { return showError(it) }

        val visible = loadedPhotos.filter { filter.accepts(loadedStates[it.mediaId]?.status) }
        session.load(visible, loadedStates, loadedTags)
        render()
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
        previousButton.isEnabled = !busy && session.canGoPrevious()
        nextButton.isEnabled = !busy && session.canGoNext()
        keepButton.isEnabled = !busy && hasPhoto
        trashButton.isEnabled = !busy && hasPhoto
        tagButton.isEnabled = !busy && hasPhoto
        undoButton.isEnabled = !busy && session.pendingCount > 0
        applyButton.isEnabled = !busy && session.pendingCount > 0
        for (index in 0 until destinationActions.childCount) {
            destinationActions.getChildAt(index).isEnabled = !busy && hasPhoto
        }
    }

    /** Name, size, capture date and recorded decision for one photo. */
    private fun describe(photo: MediaStoreRepository.Photo): String {
        val taken = SimpleDateFormat(DATE_PATTERN, Locale.ITALY).format(Date(photo.dateTakenMillis))
        val megabytes = photo.sizeBytes / BYTES_PER_MEGABYTE
        val detail = "%.1f MB · %s · %s".format(megabytes, taken, describeStatus())
        return getString(R.string.photo_info, photo.displayName, detail)
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

    /**
     * Starts applying the queue. Moves come first; trashing needs its own
     * consent and is asked for afterwards.
     */
    private fun startApply() {
        val moves = session.queuedMoves
        val trashed = session.queuedTrash
        if (moves.isEmpty() && trashed.isEmpty()) return toast(getString(R.string.message_queue_empty))

        try {
            if (moves.isNotEmpty()) {
                requestMoveConsent.launch(
                    IntentSenderRequest.Builder(mover.buildMoveConsent(moves)).build()
                )
            } else {
                requestTrashConsent.launch(
                    IntentSenderRequest.Builder(mover.buildTrashConsent(trashed)).build()
                )
            }
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
            val result = mover.applyMoves(moves)
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

        val trashed = session.queuedTrash
        if (trashed.isEmpty()) {
            session.retainFailedActions(result.failed)
            reload()
            return
        }
        session.retainFailedActions(result.failed + trashed)
        requestTrashConsent.launch(
            IntentSenderRequest.Builder(mover.buildTrashConsent(trashed)).build()
        )
    }

    /** The system already moved the files; only history remains to record. */
    private fun onTrashGranted() {
        val trashed = session.queuedTrash
        mover.recordTrashed(trashed)
        toast(getString(R.string.message_trashed, trashed.size))
        session.retainFailedActions(session.queuedActions.filterNot { it in trashed })
        reload()
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
