package it.threarth.fotosistemis

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.ProposalRepository
import it.threarth.fotosistemis.core.dedup.DuplicateFinder
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.PhotoSource
import it.threarth.fotosistemis.core.review.FolderTree
import it.threarth.fotosistemis.core.review.MovePlanner
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * The photographs the archive holds more than once.
 *
 * Two files of the same length are not the same picture, so the bytes decide
 * — but only for files whose length is shared with another, because hashing
 * an archive to find a handful of duplicates costs more than the answer.
 *
 * Which copy to keep is never decided here. The app can say that two files
 * hold the same photograph; it cannot know that the one in Famiglia matters
 * more than the one in a folder from an old phone. So every group asks, and
 * nothing happens to the copies that are not chosen until the user says so —
 * and then they go to the app's own bin, from which they come back.
 */
class DuplicatesActivity : AppCompatActivity() {

    private companion object {

        /** Smallest useful thumbnail; the slider adds to it. */
        const val MIN_THUMB_DP = 48

        /** Decoded once at a size that survives the slider being dragged. */
        const val THUMBNAIL_EDGE_PIXELS = 512

        /** How often to say how far the hashing has got. */
        const val PROGRESS_EVERY = 20
    }

    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var proposals: ProposalRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var listView: ListView
    private lateinit var outcome: TextView
    private lateinit var progress: ScanProgress

    private var groups: List<DuplicateFinder.Group> = emptyList()
    private var byId: Map<Long, PhotoRecord> = emptyMap()

    /** Which copy of each group the user wants to keep, by group index. */
    private val keeping = HashMap<Int, Long>()

    private var thumbSizeDp = MIN_THUMB_DP + 60
    private var pending: List<ReviewSession.PendingMove> = emptyList()

    private val consentLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) binThem() else pending = emptyList()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_duplicates)
        findViewById<View>(R.id.duplicatesRoot).padForSystemBars()

        val database = AndroidDatabase(this)
        inventory = PhotoInventory(database)
        stateRepository = PhotoStateRepository(database)
        proposals = ProposalRepository(database, stateRepository)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) { search() }

        listView = findViewById(R.id.duplicatesList)
        outcome = findViewById(R.id.duplicatesOutcome)
        progress = ScanProgress(findViewById(R.id.duplicatesProgress))
        findViewById<Button>(R.id.duplicatesApplyButton).setOnClickListener { askConsent() }
        listenToSizeSlider()

        search()
    }

    /** The reader decides how big a picture has to be to be recognised. */
    private fun listenToSizeSlider() {
        findViewById<SeekBar>(R.id.duplicatesSize).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    thumbSizeDp = MIN_THUMB_DP + value
                    (listView.adapter as? GroupAdapter)?.notifyDataSetChanged()
                }

                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            }
        )
    }

    /** Two passes: lengths narrow the field, the bytes decide. */
    private fun search() {
        outcome.setText(R.string.duplicates_running)
        progress.startSpinning()
        listView.adapter = null
        keeping.clear()

        thread {
            val decisions = stateRepository.loadAll().getOrElse { emptyMap() }
            val proposed = proposals.loadAll().getOrElse { emptyMap() }
            val handedOver = stateRepository.handedToSystemBin().getOrElse { emptySet() }

            // A photo already thrown away is not a duplicate to weigh
            // against the copy that was kept: offering it could propose
            // keeping the discarded one and binning the survivor.
            //
            // Being asked for the bin and being in it are not two cases
            // but one, in two moments: the proposal is written at once, the
            // file moves when Android grants it and the proposal becomes
            // the truth. Both moments are excluded.
            //
            // Apart from those, one case that really is different: an
            // immovable original handed to Android's bin after its copy was
            // filed. That photo is CATEGORISED, not thrown away — the
            // picture was kept, in another file — so no decision marks it,
            // and only the record of the handover does. It is hidden rather
            // than gone, and without this the scan found every WhatsApp
            // original it had just disposed of.
            //
            // The path is checked too, for anything dropped into the bin
            // from outside the app, which no decision of ours would know.
            val photos = inventory.loadAllPresent().getOrElse { emptyList() }
                .filterNot {
                    FolderTree.isWithin(it.relativePath, ReviewSession.DELETION_STAGING_PATH) ||
                            it.photoId in handedOver ||
                            decisions[it.photoId]?.status == ReviewStatus.TRASHED ||
                            proposed[it.photoId]?.action == Proposal.Action.TRASH
                }

            // What makes one copy worth more than another: whether work has
            // been done on it, whether it can still be organised at all, and
            // whether it carries the date stamp.
            val filed = decisions.keys
            val candidates = photos.map {
                DuplicateFinder.Candidate(
                    photoId = it.photoId,
                    relativePath = it.relativePath,
                    displayName = it.displayName,
                    sizeBytes = it.sizeBytes,
                    catalogued = it.photoId in filed,
                    immovable = PhotoSource.isImmovable(it.relativePath),
                    stamped = CaptureDateResolver.readStamp(it.displayName) != null
                )
            }
            val daLeggere = DuplicateFinder.needingHash(candidates)
            // From here the length of the work is known, so the bar can
            // stop spinning and start meaning something.
            runOnUiThread { progress.start(daLeggere.size) }
            val perId = photos.associateBy { it.photoId }

            val hashed = daLeggere.mapIndexed { index, candidate ->
                if (index % PROGRESS_EVERY == 0) showProgress(index, daLeggere.size)
                val photo = perId[candidate.photoId]
                candidate.copy(contentHash = photo?.let { photoSource.contentHash(it).getOrNull() })
            }
            val found = DuplicateFinder.groups(hashed)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                byId = perId
                groups = found
                // The finder's proposal, not the first in the list: the
                // alphabet put the WhatsApp original first, which is the
                // one copy that can never be organised again.
                found.indices.forEach { keeping[it] = found[it].suggested.photoId }
                redraw(photos.size, daLeggere.size)
            }
        }
    }

    private fun showProgress(done: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            outcome.text = getString(R.string.duplicates_progress, done, total)
            progress.advance(done)
        }
    }

    private fun redraw(examined: Int, hashed: Int) {
        progress.stop()
        val extra = groups.sumOf { it.extra }
        outcome.text =
            if (groups.isEmpty()) getString(R.string.duplicates_none, examined)
            else getString(R.string.duplicates_found, groups.size, extra, hashed)

        listView.adapter = GroupAdapter()
        findViewById<Button>(R.id.duplicatesApplyButton).isEnabled = groups.isNotEmpty()
    }

    /** One card per photograph, one radio per copy of it. */
    private inner class GroupAdapter : BaseAdapter() {

        private val inflater = LayoutInflater.from(this@DuplicatesActivity)
        private val cache = HashMap<Long, Bitmap>()

        override fun getCount(): Int = groups.size

        override fun getItem(position: Int): DuplicateFinder.Group = groups[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: inflater.inflate(R.layout.item_duplicate_group, parent, false)
            val group = groups[position]

            view.findViewById<TextView>(R.id.groupHeading).text =
                getString(R.string.duplicates_group_heading, group.copies.size, group.extra)

            val rows = view.findViewById<LinearLayout>(R.id.groupCopies)
            rows.removeAllViews()

            // Built together so each button can turn the others off: one
            // copy is kept, and the rule has to be visible on screen, not
            // merely honoured when the button is finally read.
            val radios = ArrayList<RadioButton>(group.copies.size)
            group.copies.forEach { copy -> radios.add(addCopyRow(rows, position, copy, radios)) }
            return view
        }

        /** One copy: the radio that keeps it, its picture, and its whole path. */
        private fun addCopyRow(
            rows: LinearLayout,
            groupIndex: Int,
            copy: DuplicateFinder.Candidate,
            siblings: MutableList<RadioButton>
        ): RadioButton {
            val row = inflater.inflate(R.layout.item_duplicate_copy, rows, false)
            val radio = row.findViewById<RadioButton>(R.id.copyKeep)
            radio.isChecked = keeping[groupIndex] == copy.photoId
            radio.setOnClickListener {
                keeping[groupIndex] = copy.photoId
                siblings.forEach { it.isChecked = it === radio }
            }
            // The whole row chooses, not the small circle alone.
            row.setOnClickListener { radio.performClick() }

            // Not every discarded copy goes to the same bin, and the
            // difference matters: one comes back whenever you like, the
            // other empties itself after thirty days. A photo inside
            // WhatsApp's folder cannot be moved into the app's own bin, so
            // discarding it means handing it to Android's — which is said
            // here, on the row, before anything is chosen.
            val toSystemBin = copy.immovable
            val suggested = groups[groupIndex].suggested.photoId == copy.photoId
            row.findViewById<TextView>(R.id.copyPath).text = getString(
                R.string.duplicates_copy_line,
                copy.relativePath + copy.displayName,
                copy.sizeBytes / 1024,
                getString(
                    if (toSystemBin) R.string.duplicates_copy_system_bin
                    else R.string.duplicates_copy_own_bin
                )
            ) + if (suggested) getString(R.string.duplicates_copy_suggested) else ""

            val image = row.findViewById<ImageView>(R.id.copyThumb)
            val side = (thumbSizeDp * resources.displayMetrics.density).toInt()
            image.layoutParams = image.layoutParams.apply {
                width = side
                height = side
            }
            bindThumbnail(image, copy.photoId)
            rows.addView(row)
            return radio
        }

        private fun bindThumbnail(image: ImageView, photoId: Long) {
            image.tag = photoId
            image.setImageBitmap(cache[photoId])
            if (cache.containsKey(photoId)) return

            val photo = byId[photoId] ?: return
            thread {
                val bitmap = photoSource.loadThumbnail(photo, THUMBNAIL_EDGE_PIXELS).getOrNull()
                image.post {
                    if (bitmap != null) cache[photoId] = bitmap
                    if (image.tag == photoId) image.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** Everything not chosen goes to the app's bin, and can come back. */
    private fun askConsent() {
        pending = groups.flatMapIndexed { index, group ->
            group.copies
                .filter { it.photoId != keeping[index] }
                .mapNotNull { byId[it.photoId] }
                .map { MovePlanner.toBin(it) }
        }
        if (pending.isEmpty()) return

        // Proposed before the files move, as everywhere else in this app:
        // the decision is the user's and holds even if Android refuses the
        // move. It becomes the truth when the copy actually reaches the bin;
        // until then it waits in the queue like any other request.
        for (move in pending) {
            proposals.propose(move.photo, Proposal.Action.TRASH, null)
        }

        try {
            consentLauncher.launch(
                IntentSenderRequest.Builder(mover.buildConsent(pending)).build()
            )
        } catch (error: Exception) {
            pending = emptyList()
            toast(getString(R.string.message_error, error.message.orEmpty()))
        }
    }

    private fun binThem() {
        val moves = pending
        pending = emptyList()
        outcome.setText(R.string.duplicates_running)
        progress.startSpinning()

        thread {
            val result = mover.applyAll(moves)
            runOnUiThread {
                progress.stop()
                toast(getString(R.string.duplicates_binned, result.succeeded, result.failed.size))

                // A refused move keeps its decision — the user decided, the
                // platform declined — so the photo is excluded from the next
                // scan of this screen and would vanish without explanation.
                // It is not lost: it joins the ones waiting to be moved, and
                // the reader is told where that is instead of being left to
                // wonder where the copy went.
                if (result.failed.isNotEmpty()) explainFailures(result.failed.size)
                val handover = result.forSystemBin + result.copiedOriginals
                if (handover.isNotEmpty()) {
                    systemBin.offer(handover, result.copiedOriginals.size)
                } else {
                    search()
                }
            }
        }
    }

    /** Says what became of the copies Android would not move. */
    private fun explainFailures(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.duplicates_failed_title)
            .setMessage(getString(R.string.duplicates_failed_message, count))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
