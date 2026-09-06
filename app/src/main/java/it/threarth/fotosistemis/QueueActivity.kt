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
    private lateinit var settings: AppSettings

    private lateinit var listView: ListView
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
        settings = AppSettings(this)

        listView = findViewById(R.id.queueList)
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
        thread {
            val cestino = readTrash()
            val fuoriPosto = readMisplaced()
            runOnUiThread {
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
        val records = inventory.loadRecords(entries.map { it.photoId })
            .getOrElse { emptyList() }
            .associateBy { it.photoId }

        return entries
            .mapNotNull { entry ->
                val destination = byId[entry.destinationId] ?: return@mapNotNull null
                val target = destination.pathFor(entry.captureMillis, settings.yearFolderPattern)
                if (entry.relativePath == target) return@mapNotNull null

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
                // Left where they are on purpose: deleting here means
                // moving into the app's own bin, never asking the system to
                // destroy a file.
                if (result.copiedOriginals.isNotEmpty()) {
                    toast(getString(R.string.originals_left, result.copiedOriginals.size))
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
