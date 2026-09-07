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
import it.threarth.fotosistemis.core.review.MovePlanner
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * Everything decided and not yet carried out, photo by photo.
 *
 * A decision is written the instant it is taken; carrying it out waits for
 * apply, and can fail or be refused when it comes. Nothing is lost between
 * the two — the decision is in the database until it is carried out — but
 * a count alone cannot be checked: seeing the pictures is what tells a right
 * decision from a wrong one before either is made permanent.
 *
 * Two halves. Deletions on one side; on the other every move into a
 * folder, which is the filings and also the restores still owed to the
 * bin's photos, because a restore moves a file just as a filing does.
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

    /** Reads the waiting work and turns it into moves, split in two. */
    private fun load() {
        progress.startSpinning()
        thread {
            val moves = readOwed()
            runOnUiThread {
                progress.stop()
                trash = moves.filter { it.status == ReviewStatus.TRASHED }
                misplaced = moves.filter { it.status != ReviewStatus.TRASHED }
                if (trash.isEmpty() && misplaced.isNotEmpty()) showingTrash = false
                redraw()
            }
        }
    }

    /**
     * Everything owed, planned the same way the review screen plans it.
     *
     * Read from what the archive says is owed, not deduced from where each
     * photo happens to be. Deducing it showed only half the queue — the
     * deletions — and left every filing waiting invisibly. And planned by
     * the one planner, so a filing carried out from here gets the same
     * stamped name it would have got from the review screen.
     */
    private fun readOwed(): List<ReviewSession.PendingMove> {
        val byId: Map<Long, Destination> = destinations.loadAll().getOrElse { emptyList() }
            .associateBy { it.id }
        val origins = stateRepository.loadOriginalPaths().getOrElse { emptyMap() }

        return inventory.loadOwedWork().getOrElse { emptyList() }.mapNotNull { work ->
            MovePlanner.forOwed(
                work.photo,
                work.status,
                byId[work.destinationId],
                settings.yearFolderPattern,
                origins[work.photo.photoId]
            )
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
                // Photos the app cannot move: the original of a copy, which
                // would leave the picture on the phone twice, and a photo
                // decided against inside another app's folder. Android's
                // bin is the only place either can go, and only if the user
                // agrees. Refusing used to leave them owed for ever.
                val handover = result.forSystemBin + result.copiedOriginals
                if (handover.isNotEmpty()) {
                    systemBin.offer(handover, result.copiedOriginals.size)
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
            .setNeutralButton(R.string.queue_call_off_all) { _, _ -> confirmCallOffAll() }
            .setPositiveButton(R.string.queue_call_off) { _, _ ->
                stateRepository.forgetAll(shown.map { it.photo.photoId }).fold(
                    onSuccess = {
                        toast(getString(R.string.queue_called_off, it.count))
                        load()
                    },
                    onFailure = { toast(getString(R.string.message_error, it.message.orEmpty())) }
                )
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Takes back every decision still owed, of every kind and every folder.
     *
     * Offered only here, because only here is all of it shown: a screen
     * that displays twelve must not be able to discard four hundred.
     */
    private fun confirmCallOffAll() {
        val everything = trash.size + misplaced.size

        AlertDialog.Builder(this)
            .setTitle(R.string.queue_call_off_all)
            .setMessage(getString(R.string.queue_call_off_all_message, everything))
            .setPositiveButton(R.string.queue_call_off_all) { _, _ ->
                stateRepository.discardPending().fold(
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
