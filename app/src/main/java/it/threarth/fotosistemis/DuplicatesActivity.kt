package it.threarth.fotosistemis

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import it.threarth.fotosistemis.core.data.DestinationRepository
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

        /**
         * Photos asked for at a time while reading pictures.
         *
         * Asked for in batches rather than all at once: the list is drawn
         * from what still has no fingerprint, so each batch is fresh and
         * the work resumes by itself after an interruption.
         */
        const val READ_BATCH = 200
    }

    private lateinit var inventory: PhotoInventory
    private lateinit var stateRepository: PhotoStateRepository
    private lateinit var proposals: ProposalRepository
    private lateinit var destinations: DestinationRepository
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var listView: ListView
    private lateinit var outcome: TextView
    private lateinit var progress: ScanProgress

    private var groups: List<DuplicateFinder.Group> = emptyList()
    private var byId: Map<Long, PhotoRecord> = emptyMap()

    /** What is true of each photo, and what is still asked of it. */
    private var stateById: Map<Long, PhotoStateRepository.StoredState> = emptyMap()
    private var requestById: Map<Long, Proposal> = emptyMap()
    private var categoryById: Map<Long, String> = emptyMap()

    /** Which copy of each group the user wants to keep, by group index. */
    private val keeping = HashMap<Int, Long>()

    private var thumbSizeDp = MIN_THUMB_DP + 60
    private var pending: List<ReviewSession.PendingMove> = emptyList()

    /** True while the pictures are being read; cleared to stop the pass. */
    @Volatile
    private var reading = false

    /**
     * Requests standing on copies about to go, and what to do with them.
     *
     * Held until consent is granted, so cancelling the system dialog leaves
     * every decision exactly as the user left it.
     */
    private var affected: List<Discarded> = emptyList()
    private var moveRequests = false

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
        destinations = DestinationRepository(database)
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) { search() }

        listView = findViewById(R.id.duplicatesList)
        outcome = findViewById(R.id.duplicatesOutcome)
        progress = ScanProgress(findViewById(R.id.duplicatesProgress))
        findViewById<Button>(R.id.duplicatesApplyButton).setOnClickListener { askConsent() }
        findViewById<Button>(R.id.duplicatesReadButton).setOnClickListener { readPictures() }
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

    /**
     * Two questions asked of every file, and the stronger one first.
     *
     * The picture's fingerprint is read once and kept, so it is already
     * here; the file's is taken now, and only for copies that have no
     * picture fingerprint and share a length with another. What the reading
     * has not reached yet is declared rather than passed over in silence: a
     * search that has read half the archive and says "no duplicates" is
     * telling the user something it does not know.
     */
    private fun search() {
        outcome.setText(R.string.duplicates_running)
        progress.startSpinning()
        listView.adapter = null
        keeping.clear()

        thread {
            stateById = stateRepository.loadAll().getOrElse { emptyMap() }
            categoryById = destinations.loadAll().getOrElse { emptyList() }
                .associate { it.id to it.label }
            requestById = proposals.loadAll().getOrElse { emptyMap() }

            val photos = searchable()
            val candidates = photos.map { describe(it) }
            // Only the copies with no picture fingerprint fall back to the
            // file's, and only those sharing a length are worth reading:
            // a length nobody else has cannot be a duplicate of anything.
            val toRead = DuplicateFinder.needingHash(candidates.filter { it.imageHash == null })
            runOnUiThread { progress.start(toRead.size) }

            val perId = photos.associateBy { it.photoId }
            val read = toRead.mapIndexed { index, candidate ->
                if (index % PROGRESS_EVERY == 0) showProgress(index, toRead.size)
                val photo = perId[candidate.photoId]
                candidate.copy(contentHash = photo?.let { photoSource.contentHash(it).getOrNull() })
            }
            val found = DuplicateFinder.groups(
                candidates.filter { it.imageHash != null } + read
            )
            val unread = inventory.countNeedingImageHash().getOrElse { 0 }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                byId = perId
                groups = found
                // The finder's proposal, not the first in the list: the
                // alphabet put the WhatsApp original first, which is the
                // one copy that can never be organised again.
                found.indices.forEach { keeping[it] = found[it].suggested.photoId }
                redraw(photos.size, toRead.size, unread)
            }
        }
    }

    /**
     * The photos this search may weigh against each other.
     *
     * Excluded by **where the file is**, never by what has been decided
     * about it. Two places are out: the app's own bin, since being there is
     * the whole point of it, and Android's bin, which is asked about now
     * rather than remembered — a photo handed over months ago may have been
     * put back from the gallery since, and a record of the handover would go
     * on hiding it for ever.
     *
     * Bugfix: this used to exclude by decision as well — anything recorded
     * as thrown away, anything with a discard waiting, and everything ever
     * handed to Android's bin. That buried the very copies the search
     * exists to find: a group needs two copies, so hiding one does not hide
     * a row, it makes the whole finding disappear. Fifty-five photographs
     * were on the phone and out of this search. What was decided about a
     * copy is worth knowing and is now written on its row — where it helps
     * the user choose, instead of choosing for them.
     */
    private fun searchable(): List<PhotoRecord> {
        val inSystemBin = photoSource.systemBinIds().getOrElse { emptySet() }

        return inventory.loadAllPresent().getOrElse { emptyList() }
            .filterNot {
                FolderTree.isWithin(it.relativePath, ReviewSession.DELETION_STAGING_PATH) ||
                        it.platformId in inSystemBin
            }
    }

    /** One photo as the search needs to see it. */
    private fun describe(photo: PhotoRecord) = DuplicateFinder.Candidate(
        photoId = photo.photoId,
        relativePath = photo.relativePath,
        displayName = photo.displayName,
        sizeBytes = photo.sizeBytes,
        imageHash = photo.imageHash,
        catalogued = photo.photoId in stateById,
        discardRequested = requestById[photo.photoId]?.action == Proposal.Action.TRASH ||
                stateById[photo.photoId]?.status == ReviewStatus.TRASHED,
        immovable = PhotoSource.isImmovable(photo.relativePath),
        stamped = CaptureDateResolver.readStamp(photo.displayName) != null
    )

    /**
     * Reads the picture of every photo that has none recorded, once.
     *
     * Started by hand and never on its own: it opens every file on the
     * phone. Interruptible, and resumable for nothing — it asks each time
     * for what still has no fingerprint, so stopping halfway costs only the
     * photos not yet reached.
     *
     * A file whose picture cannot be read is written down as read all the
     * same, or the pass would offer it again for ever and never end. How
     * many there were is reported: a probe that fails has to say so.
     */
    private fun readPictures() {
        if (reading) {
            reading = false
            return
        }
        val left = inventory.countNeedingImageHash().getOrElse { 0 }
        if (left == 0) return

        AlertDialog.Builder(this)
            .setTitle(R.string.duplicates_read_title)
            .setMessage(getString(R.string.duplicates_read_message, left))
            .setPositiveButton(R.string.duplicates_read_do) { _, _ -> runReading() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * The reading itself, off the main thread.
     *
     * Bugfix: how far it has got is counted over the whole archive, not
     * over what was left when this run began. Counted the other way, a pass
     * resumed after six thousand photos said "0" and appeared to be
     * starting again — the work was not being redone, but nothing on screen
     * said so, and a bar that denies the work already done is worse than no
     * bar.
     */
    private fun runReading() {
        val everything = inventory.countPresent().getOrElse { 0 }
        var done = everything - inventory.countNeedingImageHash().getOrElse { 0 }

        reading = true
        progress.start(everything)
        showProgress(done, everything, R.string.duplicates_read_progress)
        findViewById<Button>(R.id.duplicatesReadButton).setText(R.string.duplicates_read_stop)

        thread {
            var read = 0
            var unreadable = 0
            while (reading) {
                val batch = inventory.loadNeedingImageHash(READ_BATCH).getOrElse { emptyList() }
                if (batch.isEmpty()) break

                val before = done
                for (photo in batch) {
                    if (!reading) break
                    val picture = photoSource.imageHash(photo).getOrNull()
                    if (picture == null) unreadable++
                    if (inventory.recordImageHash(photo.photoId, picture).isFailure) continue

                    read++
                    done++
                    if (read % PROGRESS_EVERY == 0) {
                        showProgress(done, everything, R.string.duplicates_read_progress)
                    }
                }
                // A batch that wrote nothing would be handed back unchanged
                // for ever: the loop would spin on the same photos and the
                // pass would never end.
                if (done == before) break
            }
            reading = false
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(getString(R.string.duplicates_read_report, read, unreadable))
                search()
            }
        }
    }

    /**
     * How far a pass has got, in its own words.
     *
     * The two passes read different things — one the head of a file, the
     * other the picture inside it — and a single wording for both would
     * leave the reader unable to tell which is running.
     */
    private fun showProgress(done: Int, total: Int, said: Int = R.string.duplicates_progress) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            outcome.text = getString(said, done, total)
            progress.advance(done)
        }
    }

    private fun redraw(examined: Int, hashed: Int, unread: Int) {
        progress.stop()
        val extra = groups.sumOf { it.extra }
        val said =
            if (groups.isEmpty()) getString(R.string.duplicates_none, examined)
            else getString(R.string.duplicates_found, groups.size, extra, hashed)

        // Said every time there is anything left to read, whether or not
        // duplicates were found: it is on an empty result that a partial
        // search misleads most.
        outcome.text =
            if (unread == 0) said else said + getString(R.string.duplicates_unread, unread)

        listView.adapter = GroupAdapter()
        findViewById<Button>(R.id.duplicatesApplyButton).isEnabled = groups.isNotEmpty()
        findViewById<Button>(R.id.duplicatesReadButton).apply {
            isEnabled = unread > 0
            text =
                if (unread > 0) getString(R.string.duplicates_read_some, unread)
                else getString(R.string.duplicates_read_done_all)
        }
    }

    /**
     * What is true of one copy, and what is still asked of it.
     *
     * Which copy to keep is a choice between histories as much as between
     * folders: one may be filed in a category, another may carry a request
     * nobody has applied yet — and discarding that one throws the request
     * away with it. Saying so on the row is what lets the user see that
     * before choosing rather than after.
     */
    private fun stateOf(photoId: Long): String {
        val stored = stateById[photoId]
        val truth = when (stored?.status) {
            ReviewStatus.CATEGORIZED -> getString(
                R.string.duplicates_state_categorized, categoryNamed(stored.destinationId)
            )

            ReviewStatus.KEPT -> getString(R.string.duplicates_state_kept)
            ReviewStatus.TRASHED -> getString(R.string.duplicates_state_trashed)
            else -> getString(R.string.duplicates_state_none)
        }
        val request = requestById[photoId] ?: return truth

        return truth + getString(R.string.duplicates_state_request, describeRequest(request))
    }

    /** A pending request in words, with the category it names. */
    private fun describeRequest(request: Proposal): String = when (request.action) {
        Proposal.Action.FILE -> getString(
            R.string.duplicates_request_file,
            categoryNamed(request.destinationId)
        )

        Proposal.Action.TRASH -> getString(R.string.duplicates_request_trash)
        Proposal.Action.RESTORE -> getString(R.string.duplicates_request_restore)
    }

    /** A category by its number, or a word saying it is not known. */
    private fun categoryNamed(destinationId: Long?): String =
        categoryById[destinationId] ?: getString(R.string.duplicates_category_unknown)

    /**
     * What the name says about where this file came from, when it says
     * anything.
     *
     * Two files a hundred bytes apart out of two and a half megabytes are
     * the same photograph twice, and nothing on the screen used to say
     * which of them a gallery app had written.
     *
     * On 8 September 2026 the search proposed rightly on twenty-one pairs
     * of them and was overruled, by hand, on all twenty-one: without a word
     * about `_saved`, `foto.jpg` beside `foto_saved.jpg` reads as an
     * original beside a copy from somewhere else — WhatsApp, this archive
     * being what it is — and saving the second from the bin looks like the
     * careful thing to do. So the rewrites went into a category and the
     * camera's own files stayed unreviewed.
     *
     * A proposal nobody can check is not a proposal. The screen was right
     * and said nothing, which is the same to the reader as being wrong.
     */
    private fun originOf(copy: DuplicateFinder.Candidate): String =
        if (DuplicateFinder.isDerived(copy.displayName)) {
            getString(R.string.duplicates_copy_derived)
        } else {
            ""
        }

    /**
     * Opens the copies at the size of the screen, starting at this one.
     *
     * Every group is handed over, not only the one touched: comparing two
     * copies means going back and forth between them, and a viewer holding
     * a single pair would have to be closed and reopened to do it. The line
     * at the foot says which group and which copy, because a running count
     * across all of them would say nothing about what belongs with what.
     */
    private fun openViewer(groupIndex: Int, photoId: Long) {
        val rows = ArrayList<MovePreviewAdapter.Row>()
        var opening = 0

        groups.forEachIndexed { index, group ->
            // Read once per group: asking for it sorts the copies afresh
            // every time, and here that would happen once per copy.
            val suggested = group.suggested.photoId

            group.copies.forEachIndexed { position, copy ->
                val photo = byId[copy.photoId] ?: return@forEachIndexed
                if (index == groupIndex && copy.photoId == photoId) opening = rows.size

                rows.add(
                    MovePreviewAdapter.Row(
                        photo = photo,
                        title = copy.displayName,
                        first = getString(R.string.move_from, copy.relativePath),
                        second = getString(
                            R.string.duplicates_viewer_line,
                            copy.sizeBytes / 1024,
                            stateOf(copy.photoId)
                        ) + originOf(copy) +
                                if (suggested == copy.photoId) {
                                    getString(R.string.duplicates_viewer_suggested)
                                } else {
                                    ""
                                },
                        position = getString(
                            R.string.duplicates_viewer_position,
                            index + 1, groups.size, position + 1, group.copies.size
                        )
                    )
                )
            }
        }
        if (rows.isEmpty()) return

        CardViewerActivity.pendingRows = rows
        CardViewerActivity.pendingIndex = opening
        startActivity(Intent(this, CardViewerActivity::class.java))
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
            ) + originOf(copy) +
                    if (suggested) getString(R.string.duplicates_copy_suggested) else ""

            row.findViewById<TextView>(R.id.copyState).text = stateOf(copy.photoId)

            val image = row.findViewById<ImageView>(R.id.copyThumb)
            val side = (thumbSizeDp * resources.displayMetrics.density).toInt()
            image.layoutParams = image.layoutParams.apply {
                width = side
                height = side
            }
            // The picture opens; the row chooses. Two copies that differ by
            // a hundred bytes of metadata look identical at sixty-four
            // pixels, so the choice was being made on the paths alone —
            // and on 8 September 2026 twenty-one of them were chosen the
            // wrong way round on that basis.
            image.setOnClickListener { openViewer(groupIndex, copy.photoId) }
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

    /** A copy about to go, and the copy of its group that survives. */
    private data class Discarded(
        val photo: PhotoRecord,
        val keptPhotoId: Long,
        val request: Proposal
    )

    /**
     * Everything not chosen goes to the app's bin, and can come back.
     *
     * A copy may carry a request nobody has applied yet, and binning it
     * would take that request down with it. Those are named before anything
     * happens, with what the surviving copy is, so the choice is made
     * knowing both.
     */
    private fun askConsent() {
        val discarding = groups.flatMapIndexed { index, group ->
            val kept = keeping[index] ?: return@flatMapIndexed emptyList()
            group.copies.filter { it.photoId != kept }.mapNotNull { copy ->
                byId[copy.photoId]?.let { photo ->
                    requestById[photo.photoId]
                        ?.takeIf { it.action != Proposal.Action.TRASH }
                        ?.let { Discarded(photo, kept, it) }
                }
            }
        }
        affected = discarding
        moveRequests = false

        if (discarding.isEmpty()) launchConsent() else confirmRequests(discarding)
    }

    /**
     * Names the requests standing on the copies about to go, and offers the
     * two honest answers.
     *
     * A request is about the photograph, not about the file holding it, so
     * moving it onto the copy that survives loses nothing — except where
     * that copy carries a request of its own, which the app must not
     * silently overwrite. Those are said apart, and for them the request
     * lapses whichever button is pressed.
     */
    private fun confirmRequests(discarding: List<Discarded>) {
        val movable = discarding.count { requestById[it.keptPhotoId] == null }
        val lines = discarding.joinToString("\n") { discarded ->
            getString(
                R.string.duplicates_request_line,
                discarded.photo.displayName,
                describeRequest(discarded.request),
                stateOf(discarded.keptPhotoId)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.duplicates_requests_title)
            .setMessage(
                getString(R.string.duplicates_requests_message, discarding.size, lines) +
                        getString(R.string.duplicates_requests_movable, movable, discarding.size)
            )
            .apply {
                if (movable > 0) setPositiveButton(R.string.duplicates_requests_move) { _, _ ->
                    moveRequests = true
                    launchConsent()
                }
            }
            .setNeutralButton(R.string.duplicates_requests_drop) { _, _ -> launchConsent() }
            .setNegativeButton(R.string.action_cancel) { _, _ -> affected = emptyList() }
            .show()
    }

    /** Asks Android for the one permission the whole batch needs. */
    private fun launchConsent() {
        pending = groups.flatMapIndexed { index, group ->
            group.copies
                .filter { it.photoId != keeping[index] }
                .mapNotNull { byId[it.photoId] }
                .map { MovePlanner.toBin(it) }
        }
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

    /**
     * Writes the decisions, then carries them out.
     *
     * Bugfix: the requests used to be written before the consent dialog, on
     * the reasoning that a decision is the user's and holds even if the
     * platform refuses. But refusing that dialog is not the platform
     * declining — it is the user changing their mind — and writing first
     * meant a cancelled dialog left a photo's own request destroyed and one
     * it never asked for in its place. Written here, a cancellation costs
     * nothing; a move the platform then refuses still keeps its request,
     * which is what that reasoning wanted.
     */
    private fun recordDecisions(moves: List<ReviewSession.PendingMove>) {
        if (moveRequests) {
            for (discarded in affected) {
                if (requestById[discarded.keptPhotoId] != null) continue
                val kept = byId[discarded.keptPhotoId] ?: continue
                proposals.propose(kept, discarded.request.action, discarded.request.destinationId)
            }
        }
        affected = emptyList()
        moveRequests = false

        // One request per photo: this replaces whatever stood on the copies
        // that are going, which is the point — they are going.
        for (move in moves) {
            proposals.propose(move.photo, Proposal.Action.TRASH, null)
        }
    }

    private fun binThem() {
        val moves = pending
        pending = emptyList()
        outcome.setText(R.string.duplicates_running)
        progress.startSpinning()

        thread {
            recordDecisions(moves)
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
