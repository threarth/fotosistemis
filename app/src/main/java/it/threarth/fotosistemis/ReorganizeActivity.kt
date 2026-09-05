package it.threarth.fotosistemis

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.reorg.Reorganizer
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * Gives each category the shape the user wants on disk.
 *
 * The gallery makes one album per folder, so year subfolders multiply albums
 * until an archive becomes unmanageable. Flattening them loses the order,
 * because the names cameras produce sort by device rather than by time, which
 * is what the date at the front of the name puts back.
 *
 * Nothing is written until the summary has been shown and accepted.
 */
class ReorganizeActivity : AppCompatActivity() {

    private lateinit var repository: DestinationRepository
    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var settings: AppSettings

    private lateinit var listView: ListView
    private lateinit var statusText: TextView

    private var entries: List<Reorganizer.Entry> = emptyList()
    private var choices: List<Reorganizer.Choice> = emptyList()

    /** Show the categories that are already tidy alongside the rest. */
    private var showSettled = false

    /** Held while the user looks at the photos it would affect. */
    private var pendingPlan: Reorganizer.Plan? = null

    /** The rows currently listed, which is not always every choice. */
    private var visible: List<Reorganizer.Choice> = emptyList()

    /** Consent is asked one batch at a time; these track how far it got. */
    private var batches: List<List<ReviewSession.PendingMove>> = emptyList()
    private var batchIndex = 0
    private var succeeded = 0
    private var failed = 0
    private var firstError: String? = null

    private val reviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startApply() else pendingPlan = null
        }

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) applyCurrentBatch()
            else finishApply(getString(R.string.message_consent_refused))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_reorganize)
        applySystemBarInsets()

        val database = AndroidDatabase(this)
        repository = DestinationRepository(database)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository)
        settings = AppSettings(this)

        listView = findViewById(R.id.reorganizeList)
        statusText = findViewById(R.id.reorganizeStatus)
        listView.setOnItemClickListener { _, _, position, _ ->
            editChoice(choices.indexOf(visible[position]))
        }
        findViewById<Button>(R.id.reorganizeApplyButton).setOnClickListener { preview(choices) }
        findViewById<Button>(R.id.reorganizeRebaseButton).setOnClickListener { rebaseAll() }
        findViewById<Button>(R.id.reorganizeShowAllButton).setOnClickListener {
            showSettled = !showSettled
            redrawList()
        }

        refresh()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.reorganizeRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /**
     * Reloads the filed photos and the categories holding them.
     *
     * Choices start as the shape the archive already has, so opening the
     * screen and applying without touching anything changes nothing.
     */
    private fun refresh() {
        entries = inventory.loadForReorganization().getOrElse {
            showError(it)
            emptyList()
        }
        val destinations = repository.loadAll().getOrElse {
            showError(it)
            emptyList()
        }
        val used = entries.map { it.destinationId }.toSet()
        choices = destinations.filter { it.id in used }.map { destination ->
            Reorganizer.Choice(destination, stampNames = alreadyStamped(destination.id))
        }

        if (choices.isEmpty()) statusText.setText(R.string.reorganize_empty)
        findViewById<TextView>(R.id.reorganizeRootLine).text =
            getString(R.string.reorganize_root_line, settings.destinationRoot)
        redrawList()
    }

    /**
     * Lists the categories, hiding the ones with nothing left to move.
     *
     * A category whose photos are all where it says they are has no work in
     * it, and leaving it on a screen called "reorganise" makes the list
     * grow instead of shrink as the job gets done.
     */
    /**
     * True when this category's photos already carry the date stamp.
     *
     * Opening with the switch off would read as "take the stamps away", and
     * the screen would propose renaming every photo back on a category that
     * is in fact finished. What the archive already is, is the only honest
     * starting point.
     */
    private fun alreadyStamped(destinationId: Long): Boolean {
        val suoi = entries.filter { it.destinationId == destinationId }
        if (suoi.isEmpty()) return false

        val timbrate = suoi.count { CaptureDateResolver.readStamp(it.displayName) != null }
        return timbrate * 2 >= suoi.size
    }

    private fun redrawList() {
        val counts = entries.groupingBy { it.destinationId }.eachCount()
        val conLavoro = Reorganizer.plan(entries, choices, settings.yearFolderPattern)
            .categoriesWithWork

        visible = choices.filter { showSettled || it.destination.label in conLavoro }
        val sistemate = choices.size - choices.count { it.destination.label in conLavoro }

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            visible.map { describe(it, counts[it.destination.id] ?: 0) }
        )
        findViewById<Button>(R.id.reorganizeShowAllButton).text = getString(
            if (showSettled) R.string.reorganize_hide_settled
            else R.string.reorganize_show_settled,
            sistemate
        )
        if (visible.isEmpty() && !showSettled) statusText.setText(R.string.reorganize_all_settled)
    }

    /** One line per category: name, photos, folder shape, and naming. */
    private fun describe(choice: Reorganizer.Choice, photoCount: Int): String {
        val layout = getString(
            if (choice.destination.yearSubfolder) R.string.reorganize_layout_year
            else R.string.reorganize_layout_flat
        )
        val stamped = if (choice.stampNames) getString(R.string.reorganize_row_stamped) else ""

        return getString(
            R.string.reorganize_row,
            choice.destination.label,
            photoCount,
            choice.destination.relativePath,
            layout,
            stamped
        )
    }

    /** Opens the form for one category and keeps what it returns. */
    private fun editChoice(position: Int) {
        val choice = choices[position]
        val form = LayoutInflater.from(this).inflate(R.layout.dialog_reorganize_category, null)
        val labelField = form.findViewById<EditText>(R.id.reorganizeLabel)
        val pathField = form.findViewById<EditText>(R.id.reorganizePath)
        val yearCheck = form.findViewById<CheckBox>(R.id.reorganizeYearSubfolder)
        val stampCheck = form.findViewById<CheckBox>(R.id.reorganizeStampNames)

        labelField.setText(choice.destination.label)
        pathField.setText(choice.destination.relativePath)
        yearCheck.isChecked = choice.destination.yearSubfolder
        stampCheck.isChecked = choice.stampNames

        AlertDialog.Builder(this)
            .setTitle(choice.destination.label)
            .setView(form)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val label = labelField.text.toString().trim()
                val path = pathField.text.toString().trim().trim('/')
                if (label.isEmpty() || path.isEmpty()) {
                    toast(getString(R.string.destination_invalid))
                } else {
                    replaceChoice(position, choice, label, path, yearCheck, stampCheck)
                }
            }
            // One category at a time: a whole archive in a single batch is
            // hard to check, and easier to postpone than to verify.
            .setNeutralButton(R.string.reorganize_preview_one) { _, _ ->
                val label = labelField.text.toString().trim()
                val path = pathField.text.toString().trim().trim('/')
                if (label.isEmpty() || path.isEmpty()) {
                    return@setNeutralButton toast(getString(R.string.destination_invalid))
                }
                replaceChoice(position, choice, label, path, yearCheck, stampCheck)
                preview(listOf(choices[position]))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun replaceChoice(
        position: Int,
        choice: Reorganizer.Choice,
        label: String,
        path: String,
        yearCheck: CheckBox,
        stampCheck: CheckBox
    ) {
        val edited = choice.destination.copy(
            label = label,
            relativePath = path,
            yearSubfolder = yearCheck.isChecked
        )
        choices = choices.toMutableList().also {
            it[position] = Reorganizer.Choice(edited, stampCheck.isChecked)
        }
        redrawList()
    }

    /**
     * Puts every category under the parent folder, in one gesture.
     *
     * Only the paths in this screen: nothing is written until the preview is
     * accepted, so this is a proposal like any other choice made here.
     */
    private fun rebaseAll() {
        val root = settings.destinationRoot
        choices = choices.map { choice ->
            choice.copy(
                destination = choice.destination.copy(
                    relativePath = "$root/${choice.destination.label}"
                )
            )
        }
        redrawList()
        toast(getString(R.string.reorganize_rebased, root))
    }

    /** Builds the plan for [wanted] and states it before anything is written. */
    private fun preview(wanted: List<Reorganizer.Choice>) {
        if (wanted.isEmpty()) return toast(getString(R.string.reorganize_empty))
        val plan = Reorganizer.plan(entries, wanted, settings.yearFolderPattern)

        if (plan.isBlocked) return showConflicts(plan)
        if (plan.total == 0) return toast(getString(R.string.reorganize_nothing))

        pendingPlan = plan
        AlertDialog.Builder(this)
            .setTitle(R.string.reorganize_summary_title)
            .setMessage(summaryOf(plan))
            .setPositiveButton(R.string.reorganize_apply) { _, _ -> startApply() }
            .setNeutralButton(R.string.reorganize_list) { _, _ -> showMoveList(plan) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> pendingPlan = null }
            .show()
    }

    /** Counts per category, plus what the user should look at twice. */
    private fun summaryOf(plan: Reorganizer.Plan): String {
        val breakdown = plan.byCategory.entries
            .sortedByDescending { it.value }
            .joinToString("\n") { "  ${it.key}: ${it.value}" }
        val uncertain = if (plan.uncertainCount == 0) "" else getString(
            R.string.reorganize_summary_uncertain, plan.uncertainCount
        )
        val clusters = Reorganizer.clustersOf(entries)
        val imports = if (clusters.isEmpty()) "" else getString(
            R.string.reorganize_summary_clusters,
            clusters.joinToString("\n") { "  ${it.dayLabel}: ${it.photoCount}" }
        )

        return getString(R.string.reorganize_summary, plan.total, breakdown, uncertain, imports)
    }

    /**
     * Every file the plan would touch, from where to where.
     *
     * Counts say how much; only the list says what. A move that looks right
     * in aggregate can still be wrong for a particular photo, and this is
     * the only place that difference shows before it is written.
     */
    private fun showMoveList(plan: Reorganizer.Plan) {
        val righe = plan.moves.map { move ->
            getString(
                R.string.reorganize_move_line,
                move.fromRelativePath + move.fromDisplayName,
                move.toRelativePath + move.toDisplayName
            )
        }.toTypedArray<CharSequence>()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.reorganize_list_title, plan.total))
            .setItems(righe, null)
            .setPositiveButton(R.string.reorganize_apply) { _, _ -> startApply() }
            .setNeutralButton(R.string.reorganize_review) { _, _ -> reviewPlan(plan) }
            .setNegativeButton(R.string.action_cancel) { _, _ -> pendingPlan = null }
            .show()
    }

    /**
     * Two photos cannot share a name, and the app will not pick a winner.
     * Turning the stamp on is the fix, and it is the user's to make.
     */
    private fun showConflicts(plan: Reorganizer.Plan) {
        val shown = plan.conflicts.joinToString("\n") { "  ${it.relativePath}${it.displayName}" }
        AlertDialog.Builder(this)
            .setTitle(R.string.reorganize_conflicts_title)
            .setMessage(getString(R.string.reorganize_conflicts, plan.conflicts.size, shown))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Opens the photos themselves: a count of three thousand cannot be checked. */
    private fun reviewPlan(plan: Reorganizer.Plan) {
        val records = inventory.loadRecords(plan.moves.map { it.photoId })
            .getOrElse { return showError(it) }
        if (records.isEmpty()) return toast(getString(R.string.reorganize_nothing))

        PhotoPreviewActivity.pendingPhotos = records
        PhotoPreviewActivity.pendingSummary =
            getString(R.string.reorganize_review_summary, records.size)
        reviewLauncher.launch(Intent(this, PhotoPreviewActivity::class.java))
    }

    /**
     * Saves the wanted shape, then starts moving files towards it.
     *
     * The destinations are written first on purpose: they say where photos
     * belong, and the moves make the disk agree. A run that fails halfway can
     * then be finished by running it again, which would be impossible if the
     * folders still described the old shape.
     */
    private fun startApply() {
        val plan = pendingPlan ?: return
        pendingPlan = null

        for (choice in choices) {
            val outcome = repository.update(
                choice.destination.id,
                choice.destination.label,
                choice.destination.relativePath,
                choice.destination.yearSubfolder
            )
            if (outcome.isFailure) return showError(outcome.exceptionOrNull()!!)
        }
        val moves = buildMoves(plan) ?: return

        batches = mover.consentBatches(moves)
        batchIndex = 0
        succeeded = 0
        failed = 0
        firstError = null
        requestNextConsent()
    }

    /** Turns the plan into queued writes, or null when the photos are gone. */
    private fun buildMoves(plan: Reorganizer.Plan): List<ReviewSession.PendingMove>? {
        val records = inventory.loadRecords(plan.moves.map { it.photoId })
            .getOrElse {
                showError(it)
                return null
            }
        val byId: Map<Long, PhotoRecord> = records.associateBy { it.photoId }

        return plan.moves.mapNotNull { move ->
            val photo = byId[move.photoId] ?: return@mapNotNull null
            ReviewSession.PendingMove(
                photo = photo,
                destinationRelativePath = move.toRelativePath,
                status = ReviewStatus.CATEGORIZED,
                destinationId = move.destinationId,
                newDisplayName = move.toDisplayName
            )
        }
    }

    /** Asks for the consent covering the next batch, or reports the end. */
    private fun requestNextConsent() {
        if (batchIndex >= batches.size) return finishApply(null)
        statusText.text = getString(
            R.string.reorganize_consent_batch, batchIndex + 1, batches.size
        )
        try {
            consentLauncher.launch(
                IntentSenderRequest.Builder(mover.buildConsent(batches[batchIndex])).build()
            )
        } catch (error: Exception) {
            finishApply(error.message ?: error::class.java.simpleName)
        }
    }

    /** Moves one batch off the main thread, then moves on to the next. */
    private fun applyCurrentBatch() {
        val batch = batches[batchIndex]
        statusText.setText(R.string.status_applying)

        thread {
            val result = mover.applyAll(batch)
            for (move in batch) {
                if (move in result.failed) continue
                inventory.recordRelocation(
                    move.photo.photoId,
                    move.destinationRelativePath,
                    move.newDisplayName ?: move.photo.displayName
                )
            }
            runOnUiThread {
                succeeded += result.succeeded
                failed += result.failed.size
                if (firstError == null) firstError = result.firstError
                batchIndex++
                requestNextConsent()
            }
        }
    }

    /** Reports what happened and puts the screen back in a readable state. */
    private fun finishApply(error: String?) {
        batches = emptyList()
        val failure = error ?: firstError

        if (failure == null) {
            toast(getString(R.string.reorganize_done, succeeded))
        } else {
            toast(getString(R.string.reorganize_partial, succeeded, failed, failure))
        }
        statusText.text = ""
        refresh()
    }

    private fun showError(error: Throwable) {
        toast(getString(R.string.message_error, error.message ?: error::class.java.simpleName))
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
