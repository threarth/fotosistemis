package it.threarth.fotosistemis

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.provider.MediaStore
import android.widget.Button
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
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * Everything decided and not yet carried out, photo by photo.
 *
 * A decision is written the instant it is taken; carrying it out can fail, be
 * refused, or be lost when a session ends. The two drift apart silently, and
 * a count alone cannot be checked: seeing the pictures is what tells a right
 * decision from a wrong one before either is made permanent.
 */
class QueueActivity : AppCompatActivity() {

    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var destinations: DestinationRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var settings: AppSettings

    private lateinit var listView: ListView
    private lateinit var progress: ScanProgress
    private lateinit var summary: TextView

    private var trash: List<ReviewSession.PendingMove> = emptyList()
    private var misplaced: List<ReviewSession.PendingMove> = emptyList()
    private var showingTrash = true

    /** What the buttons act on, and the consent covers. */
    private var pending: List<ReviewSession.PendingMove> = emptyList()

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) carryOut()
            else pending = emptyList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_queue)
        findViewById<View>(R.id.queueRoot).padForSystemBars()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        destinations = DestinationRepository(database)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) {
            load()
        }
        settings = AppSettings(this)

        listView = findViewById(R.id.queueList)
        progress = ScanProgress(findViewById(R.id.queueProgress))
        summary = findViewById(R.id.queueSummary)
        findViewById<Button>(R.id.queueKindButton).setOnClickListener {
            showingTrash = !showingTrash
            redraw()
        }
        findViewById<Button>(R.id.queueCarryOutButton).setOnClickListener { askConsent() }
        findViewById<Button>(R.id.queueCallOffButton).setOnClickListener { confirmCallOff() }

        load()
    }

    /** Reads both halves of the waiting work and turns them into moves. */
    private fun load() {
        progress.startSpinning()
        thread {
            val cestino = readTrash()
            val fuoriPosto = readMisplaced()
            runOnUiThread {
                progress.stop()
                trash = cestino
                misplaced = fuoriPosto
                if (trash.isEmpty() && misplaced.isNotEmpty()) showingTrash = false
                redraw()
            }
        }
    }

    /** Photos decided for deletion that are not yet in the bin. */
    private fun readTrash(): List<ReviewSession.PendingMove> =
        inventory.loadPendingTrash(ReviewSession.DELETION_STAGING_PATH)
            .getOrElse { emptyList() }
            .map { photo ->
                ReviewSession.PendingMove(
                    photo = photo,
                    destinationRelativePath = ReviewSession.DELETION_STAGING_PATH,
                    status = ReviewStatus.TRASHED,
                    destinationId = null
                )
            }

    /**
     * Filed photos sitting somewhere other than where their category says.
     *
     * The record of the folder and the folder itself can disagree — a move
     * refused, an app closed mid-batch — and the photo is then filed in name
     * only. These are the moves that would make the two agree again.
     */
    private fun readMisplaced(): List<ReviewSession.PendingMove> {
        val byId: Map<Long, Destination> = destinations.loadAll().getOrElse { emptyList() }
            .associateBy { it.id }
        val entries = inventory.loadForReorganization().getOrElse { emptyList() }
        // A photo the platform will not let us move was copied instead, and
        // the original stayed exactly where it was. Its own path therefore
        // still says "wrong folder" for ever, and applying again just makes
        // another copy — which is what produced IMG-…-WA0000 (1) and (2).
        val reached = stateRepository.destinationsReached().getOrElse { emptyMap() }
        val records = inventory.loadRecords(entries.map { it.photoId })
            .getOrElse { emptyList() }
            .associateBy { it.photoId }

        return entries
            .mapNotNull { entry ->
                val destination = byId[entry.destinationId] ?: return@mapNotNull null
                val target = destination.pathFor(entry.captureMillis, settings.yearFolderPattern)
                if (entry.relativePath == target) return@mapNotNull null
                if (target.trim('/') in reached[entry.photoId].orEmpty()) return@mapNotNull null

                records[entry.photoId]?.let {
                    ReviewSession.PendingMove(it, target, ReviewStatus.CATEGORIZED, destination.id)
                }
            }
    }

    private fun redraw() {
        val shown = if (showingTrash) trash else misplaced
        summary.text = getString(R.string.queue_summary, trash.size, misplaced.size)
        findViewById<Button>(R.id.queueKindButton).text = getString(
            if (showingTrash) R.string.queue_showing_trash else R.string.queue_showing_sorted,
            shown.size
        )
        listView.adapter = MovePreviewAdapter.forMoves(this, shown, photoSource)

        val any = shown.isNotEmpty()
        findViewById<Button>(R.id.queueCarryOutButton).isEnabled = any
        findViewById<Button>(R.id.queueCallOffButton).isEnabled = any
    }

    /** Moving files needs the system's permission, whoever asked for it. */
    private fun askConsent() {
        pending = if (showingTrash) trash else misplaced
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

    private fun carryOut() {
        val moves = pending
        pending = emptyList()
        thread {
            val result = mover.applyAll(moves)
            runOnUiThread {
                toast(getString(R.string.queue_carried_out, result.succeeded, result.failed.size))
                // The original of a copy cannot be moved and would leave
                // the picture on the phone twice: Android's bin is the only
                // place it can go, and only if the user agrees.
                if (result.copiedOriginals.isNotEmpty()) {
                    systemBin.offer(result.copiedOriginals, result.copiedOriginals.size)
                }
                load()
            }
        }
    }

    /** Undoing decisions is itself one, and is stated before it happens. */
    private fun confirmCallOff() {
        val shown = if (showingTrash) trash else misplaced
        if (shown.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.queue_call_off)
            .setMessage(getString(R.string.queue_call_off_message, shown.size))
            .setPositiveButton(R.string.queue_call_off) { _, _ ->
                stateRepository.forgetAll(shown.map { it.photo.photoId }).fold(
                    onSuccess = {
                        toast(getString(R.string.queue_called_off, it))
                        load()
                    },
                    onFailure = { toast(getString(R.string.message_error, it.message.orEmpty())) }
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
