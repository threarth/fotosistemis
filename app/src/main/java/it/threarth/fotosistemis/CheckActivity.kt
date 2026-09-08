package it.threarth.fotosistemis

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.data.DestinationRepository
import it.threarth.fotosistemis.core.data.IgnoredRepository
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.ProposalRepository
import it.threarth.fotosistemis.core.date.CaptureDateCheck
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.FolderPath
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.review.FolderTree
import it.threarth.fotosistemis.core.review.MovePlanner
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * One shape for every check the app performs.
 *
 * A check that only reports a number cannot be judged: the reader has no way
 * to know what was searched, what counted as wrong, or what pressing the
 * button would do to their photographs. So every check says the same three
 * things before anything else — where it looked, what it looked for, what
 * applying would do — and then shows the photographs themselves, as cards,
 * because a list of paths is checkable only by someone who already knows
 * what each file looks like.
 *
 * And every check can be told to leave something alone. That answer is
 * remembered: a check that forgets it brings the same photos back at every
 * scan until the whole list stops being read, and then the one new problem
 * in it goes unseen.
 */
class CheckActivity : AppCompatActivity() {

    companion object {

        /** Which check to run, by [IgnoredRepository.Check] name. */
        const val EXTRA_CHECK = "it.threarth.fotosistemis.CHECK"

        /** How often to say how far the slow check has got. */
        private const val PROGRESS_EVERY = 25
    }

    /**
     * One finding: the photograph, and what would be done about it.
     *
     * [move] is set when the remedy moves the file; [destinationId] when it
     * only records which category the photo already sits in.
     */
    private data class Finding(
        val photo: PhotoRecord,
        val move: ReviewSession.PendingMove?,
        val destinationId: Long?,
        val caption: String
    )

    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var destinations: DestinationRepository
    private lateinit var ignored: IgnoredRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var settings: AppSettings

    private lateinit var check: IgnoredRepository.Check
    private lateinit var listView: ListView
    private lateinit var progress: ScanProgress

    private var findings: List<Finding> = emptyList()
    private var examined = 0
    private var pending: List<ReviewSession.PendingMove> = emptyList()

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) applyMoves() else pending = emptyList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_check)
        findViewById<View>(R.id.checkRoot).padForSystemBars()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        destinations = DestinationRepository(database)
        ignored = IgnoredRepository(database)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) { run() }
        settings = AppSettings(this)

        check = IgnoredRepository.Check.entries
            .firstOrNull { it.storedValue == intent.getStringExtra(EXTRA_CHECK) }
            ?: IgnoredRepository.Check.STRANGERS

        listView = findViewById(R.id.checkList)
        progress = ScanProgress(findViewById(R.id.checkProgress))
        title = getString(titleOf(check))
        findViewById<TextView>(R.id.checkTitle).setText(titleOf(check))
        findViewById<Button>(R.id.checkUnignoreButton).text =
            getString(R.string.check_unignore, 0)
        findViewById<TextView>(R.id.checkWhere).setText(whereOf(check))
        findViewById<TextView>(R.id.checkWhat).text =
            getString(whatOf(check)) + "\n\n" + getString(actionOf(check))

        findViewById<Button>(R.id.checkApplyButton).setOnClickListener { apply() }
        showAlternative()
        findViewById<Button>(R.id.checkIgnoreButton).setOnClickListener { ignoreAll() }
        findViewById<Button>(R.id.checkUnignoreButton).setOnClickListener { stopIgnoring() }

        run()
    }

    /**
     * Offers the second answer, where the check has one.
     *
     * A photo back from Android's bin admits two readings and the app cannot
     * pick between them: the discard did not happen, or the discard still
     * stands and has to be asked again. Both are offered, plainly named, and
     * the user decides — which is the same rule every other decision in this
     * app follows.
     */
    private fun showAlternative() {
        val button = findViewById<Button>(R.id.checkAlternativeButton)
        if (check != IgnoredRepository.Check.RETURNED) return

        button.visibility = View.VISIBLE
        button.setText(R.string.check_returned_again)
        button.setOnClickListener { proposeDiscardAgain() }
        // With two answers side by side, "Applica" names neither of them.
        findViewById<Button>(R.id.checkApplyButton).setText(R.string.check_returned_review)
    }

    private fun titleOf(check: IgnoredRepository.Check): Int = when (check) {
        IgnoredRepository.Check.STRANGERS -> R.string.check_strangers_title
        IgnoredRepository.Check.MISPLACED -> R.string.check_misplaced_title
        IgnoredRepository.Check.DATES -> R.string.check_dates_title
        IgnoredRepository.Check.RETURNED -> R.string.check_returned_title
    }

    private fun whereOf(check: IgnoredRepository.Check): Int = when (check) {
        IgnoredRepository.Check.STRANGERS -> R.string.check_strangers_where
        IgnoredRepository.Check.MISPLACED -> R.string.check_misplaced_where
        IgnoredRepository.Check.DATES -> R.string.check_dates_where
        IgnoredRepository.Check.RETURNED -> R.string.check_returned_where
    }

    private fun whatOf(check: IgnoredRepository.Check): Int = when (check) {
        IgnoredRepository.Check.STRANGERS -> R.string.check_strangers_what
        IgnoredRepository.Check.MISPLACED -> R.string.check_misplaced_what
        IgnoredRepository.Check.DATES -> R.string.check_dates_what
        IgnoredRepository.Check.RETURNED -> R.string.check_returned_what
    }

    private fun actionOf(check: IgnoredRepository.Check): Int = when (check) {
        IgnoredRepository.Check.STRANGERS -> R.string.check_strangers_action
        IgnoredRepository.Check.MISPLACED -> R.string.check_misplaced_action
        IgnoredRepository.Check.DATES -> R.string.check_dates_action
        IgnoredRepository.Check.RETURNED -> R.string.check_returned_action
    }

    /** Runs the check off the main thread and draws what it found. */
    private fun run() {
        setBusy(true)
        // Reading a photograph's EXIF means opening the file, and a filed
        // archive is hundreds of them. Saying so beats an empty screen that
        // looks broken while it is in fact working.
        findViewById<TextView>(R.id.checkOutcome).setText(R.string.check_running)
        progress.startSpinning()
        listView.adapter = null

        thread {
            val skip = ignored.idsFor(check).getOrElse { emptySet() }
            val found = when (check) {
                IgnoredRepository.Check.STRANGERS -> findStrangers()
                IgnoredRepository.Check.MISPLACED -> findMisplaced()
                IgnoredRepository.Check.DATES -> findWrongDates()
                IgnoredRepository.Check.RETURNED -> findReturned()
            }.filterNot { it.photo.photoId in skip }

            runOnUiThread {
                setBusy(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                findings = found
                redraw()
            }
        }
    }

    /** Photos inside a category folder that nobody ever filed there. */
    private fun findStrangers(): List<Finding> {
        val ids = inventory.loadStrangersInDestinations().getOrElse { emptyList() }
        examined = ids.size
        val folders = destinations.loadAll().getOrElse { emptyList() }

        return inventory.loadRecords(ids).getOrElse { emptyList() }.mapNotNull { photo ->
            val folder = holderOf(photo.relativePath, folders) ?: return@mapNotNull null
            Finding(
                photo = photo,
                move = null,
                destinationId = folder.id,
                caption = getString(R.string.check_row_file_under, folder.label)
            )
        }
    }

    /** Filed photos sitting somewhere other than where their category says. */
    private fun findMisplaced(): List<Finding> {
        val byId = destinations.loadAll().getOrElse { emptyList() }.associateBy { it.id }
        val entries = inventory.loadForReorganization().getOrElse { emptyList() }
        // A photo the platform will not let us move was copied instead, and
        // the original stayed exactly where it was. Its own path therefore
        // still says "wrong folder" for ever, and applying again just makes
        // another copy — which is what produced IMG-…-WA0000 (1) and (2).
        val reached = stateRepository.destinationsReached().getOrElse { emptyMap() }
        examined = entries.size
        val records = inventory.loadRecords(entries.map { it.photoId })
            .getOrElse { emptyList() }
            .associateBy { it.photoId }

        return entries.mapNotNull { entry ->
            val destination = byId[entry.destinationId] ?: return@mapNotNull null
            val photo = records[entry.photoId] ?: return@mapNotNull null
            // The same plan the review screen would make, stamped name
            // included: a photo put right by a check must end up exactly
            // as it would have had it been filed from the start.
            val move = MovePlanner.toCategory(photo, destination, settings.yearFolderPattern)
            val target = move.destinationRelativePath
            if (FolderPath.sameFolder(entry.relativePath, target)) return@mapNotNull null
            if (reached[entry.photoId].orEmpty().any { FolderPath.sameFolder(it, target) }) {
                return@mapNotNull null
            }

            Finding(photo, move, destination.id, getString(R.string.move_to, target))
        }
    }

    /** Filed photos whose three carriers do not agree about the date. */
    private fun findWrongDates(): List<Finding> {
        val filed = stateRepository.loadAll().getOrElse { emptyMap() }
            .filterValues { it.status == ReviewStatus.CATEGORIZED }
            .keys
        val ours = photoSource.ownPhotos().getOrElse { emptyList() }
            .map { it.platformId }
            .toHashSet()
        val photos = inventory.loadRecords(filed.toList()).getOrElse { emptyList() }
        examined = photos.size

        return photos.mapIndexedNotNull { index, photo ->
            if (index == 0) runOnUiThread { progress.start(photos.size) }
            if (index % PROGRESS_EVERY == 0) showProgress(index, photos.size)
            val carriers = photoSource.readCarriers(photo).getOrNull()
                ?: return@mapIndexedNotNull null
            val verdict = CaptureDateCheck.check(photo.dateTakenMillis, carriers)
            if (verdict.settled) return@mapIndexedNotNull null

            val caption =
                if (photo.platformId in ours) {
                    getString(R.string.check_row_date, verdict.wrong.joinToString(", "))
                } else {
                    getString(R.string.check_row_date_theirs, verdict.wrong.joinToString(", "))
                }
            Finding(photo, null, null, caption)
        }
    }

    /**
     * Photos handed to Android's bin that are on the phone again.
     *
     * The record of a handover is a memory, and memories go stale: the file
     * may have been put back from the gallery since, or Android's bin
     * emptied and the photo saved from it. Only the platform knows, so it is
     * asked — what is in its bin now — and whatever is not there any more
     * and is still in the inventory has come back.
     *
     * A photo the app copied into a category and whose leftover original
     * went to the bin is not one of these: that record says categorised, and
     * says the truth. What is looked for is a photo the app calls thrown
     * away while it sits in the archive.
     */
    private fun findReturned(): List<Finding> {
        val handedOver = stateRepository.handedToSystemBin().getOrElse { emptySet() }
        val stillInBin = photoSource.systemBinIds().getOrElse { emptySet() }
        val truths = stateRepository.loadAll().getOrElse { emptyMap() }
        val photos = inventory.loadRecords(handedOver.toList()).getOrElse { emptyList() }
        examined = handedOver.size

        return photos.mapNotNull { photo ->
            if (photo.platformId in stillInBin) return@mapNotNull null
            if (truths[photo.photoId]?.status != ReviewStatus.TRASHED) return@mapNotNull null
            if (FolderTree.isWithin(photo.relativePath, ReviewSession.DELETION_STAGING_PATH)) {
                return@mapNotNull null
            }

            Finding(photo, null, null, getString(R.string.check_row_returned))
        }
    }

    /** Keeps the reader company through a check that opens every file. */
    private fun showProgress(done: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            findViewById<TextView>(R.id.checkOutcome).text =
                getString(R.string.check_progress, done, total)
            progress.advance(done)
        }
    }

    /** The declared category folder holding [path], longest match winning. */
    private fun holderOf(path: String, folders: List<Destination>): Destination? {
        return folders
            .filter { destination ->
                val folder = destination.relativePath.trim('/')
                folder.isNotEmpty() && FolderTree.isWithin(path, folder)
            }
            .maxByOrNull { it.relativePath.trim('/').length }
    }

    private fun redraw() {
        progress.stop()
        val outcome = findViewById<TextView>(R.id.checkOutcome)
        outcome.text =
            if (findings.isEmpty()) getString(R.string.check_nothing, examined)
            else getString(R.string.check_found, findings.size, examined)

        listView.adapter = MovePreviewAdapter(
            this,
            findings.map { finding ->
                MovePreviewAdapter.Row(
                    photo = finding.photo,
                    title = finding.photo.displayName,
                    first = getString(R.string.move_from, finding.photo.relativePath),
                    second = finding.caption
                )
            },
            photoSource
        )

        val any = findings.isNotEmpty()
        findViewById<Button>(R.id.checkApplyButton).isEnabled = any
        findViewById<Button>(R.id.checkIgnoreButton).isEnabled = any
        findViewById<Button>(R.id.checkAlternativeButton).isEnabled = any
        showIgnoredCount()
    }

    /** Says how many this check has been told to skip, and offers them back. */
    private fun showIgnoredCount() {
        val button = findViewById<Button>(R.id.checkUnignoreButton)
        thread {
            val quante = ignored.idsFor(check).getOrElse { emptySet() }.size
            runOnUiThread {
                button.text = getString(R.string.check_unignore, quante)
                button.isEnabled = quante > 0
            }
        }
    }

    private fun apply() {
        when (check) {
            IgnoredRepository.Check.STRANGERS -> fileStrangers()
            IgnoredRepository.Check.MISPLACED -> askConsent()
            IgnoredRepository.Check.DATES -> healDates()
            IgnoredRepository.Check.RETURNED -> forgetTheDiscard()
        }
    }

    /**
     * Puts a returned photo back among the undecided.
     *
     * The truth is removed rather than replaced. An absent state is what
     * this app means by "never reviewed" — it says so where the states are
     * declared — so taking it away is exactly right here: the discard was
     * not carried out, and nothing else is known. Everything follows on its
     * own from that. The photo comes back into the deck, and the month it
     * belongs to counts it again as work to do, without a single screen
     * being taught a new word.
     */
    private fun forgetTheDiscard() {
        setBusy(true)
        thread {
            var forgotten = 0
            for (finding in findings) {
                if (stateRepository.forget(finding.photo.photoId).isSuccess) forgotten++
            }
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.check_applied, forgotten, findings.size - forgotten))
                run()
            }
        }
    }

    /**
     * Asks again for what was already decided: the photo is back, and the
     * decision about it still stands.
     *
     * Written as a request, not as a truth. The file has to move, or be
     * handed over a second time, before anything about it is true again —
     * and that is the queue's business, where the user watches it happen.
     */
    private fun proposeDiscardAgain() {
        setBusy(true)
        thread {
            val proposals = ProposalRepository(AndroidDatabase(this), stateRepository)
            val asked = proposals
                .proposeAll(findings.map { it.photo }, Proposal.Action.TRASH, null)
                .getOrElse { 0 }
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.check_applied, asked, findings.size - asked))
                run()
            }
        }
    }

    /** Files each stranger under the category whose folder already holds it. */
    private fun fileStrangers() {
        setBusy(true)
        thread {
            var filed = 0
            for (finding in findings) {
                val destinationId = finding.destinationId ?: continue
                // It is already in that folder: nothing to move, so this is
                // a truth to record, not a proposal to carry out.
                if (stateRepository
                        .record(finding.photo, ReviewStatus.CATEGORIZED, destinationId)
                        .isSuccess
                ) filed++
            }
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.check_applied, filed, findings.size - filed))
                run()
            }
        }
    }

    /** Moving files needs the system's permission, whoever asked for it. */
    private fun askConsent() {
        pending = findings.mapNotNull { it.move }
        if (pending.isEmpty()) return

        try {
            consentLauncher.launch(
                IntentSenderRequest.Builder(mover.buildConsent(pending)).build()
            )
        } catch (error: Exception) {
            pending = emptyList()
            toast(getString(R.string.message_error, error.message.orEmpty()))
        }
    }

    private fun applyMoves() {
        val moves = pending
        pending = emptyList()
        setBusy(true)
        thread {
            val result = mover.applyAll(moves)
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.check_applied, result.succeeded, result.failed.size))
                // A photo the app could only copy leaves its original
                // behind, and that has nowhere to go but Android's bin.
                val handover = result.forSystemBin + result.copiedOriginals
                if (handover.isNotEmpty()) {
                    systemBin.offer(handover, result.copiedOriginals.size)
                } else {
                    run()
                }
            }
        }
    }

    /** Writes the date where it is missing, then reads back to check. */
    private fun healDates() {
        setBusy(true)
        thread {
            val ours = photoSource.ownPhotos().getOrElse { emptyList() }
                .map { it.platformId }
                .toHashSet()
            var healed = 0
            var left = 0

            for (finding in findings) {
                if (finding.photo.platformId !in ours) {
                    left++
                    continue
                }
                val verdict = photoSource
                    .healCaptureDate(finding.photo, finding.photo.dateTakenMillis)
                    .getOrNull()
                if (verdict != null && verdict.settled) healed++ else left++
            }
            runOnUiThread {
                setBusy(false)
                toast(getString(R.string.check_applied, healed, left))
                run()
            }
        }
    }

    /** Remembers that these are not to be reported again. */
    private fun ignoreAll() {
        val ids = findings.map { it.photo.photoId }
        ignored.ignore(check, ids).fold(
            onSuccess = {
                toast(getString(R.string.check_ignored, it))
                run()
            },
            onFailure = { toast(getString(R.string.message_error, it.message.orEmpty())) }
        )
    }

    /** Takes back every answer given to this check, so it may ask again. */
    private fun stopIgnoring() {
        ignored.clear(check).fold(
            onSuccess = {
                toast(getString(R.string.check_unignored, it))
                run()
            },
            onFailure = { toast(getString(R.string.message_error, it.message.orEmpty())) }
        )
    }

    private fun setBusy(busy: Boolean) {
        val usable = !busy && findings.isNotEmpty()
        findViewById<Button>(R.id.checkApplyButton).isEnabled = usable
        findViewById<Button>(R.id.checkIgnoreButton).isEnabled = usable
        findViewById<Button>(R.id.checkAlternativeButton).isEnabled = usable
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
