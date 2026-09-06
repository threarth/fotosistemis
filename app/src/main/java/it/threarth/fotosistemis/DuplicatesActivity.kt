package it.threarth.fotosistemis

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.dedup.DuplicateFinder
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import it.threarth.fotosistemis.core.port.PhotoSource
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
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var mover: BatchMover
    private lateinit var systemBin: SystemBinHandover
    private lateinit var listView: ListView
    private lateinit var outcome: TextView

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
        photoSource = MediaStorePhotoSource(this)
        mover = BatchMover(this, photoSource, stateRepository, inventory)
        systemBin = SystemBinHandover(this, photoSource, stateRepository) { search() }

        listView = findViewById(R.id.duplicatesList)
        outcome = findViewById(R.id.duplicatesOutcome)
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
        listView.adapter = null
        keeping.clear()

        thread {
            val photos = inventory.loadAllPresent().getOrElse { emptyList() }
            val candidates = photos.map {
                DuplicateFinder.Candidate(it.photoId, it.relativePath, it.displayName, it.sizeBytes)
            }
            val daLeggere = DuplicateFinder.needingHash(candidates)
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
                found.indices.forEach { keeping[it] = found[it].copies.first().photoId }
                redraw(photos.size, daLeggere.size)
            }
        }
    }

    private fun showProgress(done: Int, total: Int) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            outcome.text = getString(R.string.duplicates_progress, done, total)
        }
    }

    private fun redraw(examined: Int, hashed: Int) {
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

            val rows = view.findViewById<RadioGroup>(R.id.groupCopies)
            rows.removeAllViews()
            rows.setOnCheckedChangeListener(null)
            group.copies.forEachIndexed { index, copy -> addCopyRow(rows, position, index, copy) }
            return view
        }

        /** One copy: the radio that keeps it, its picture, and its whole path. */
        private fun addCopyRow(
            rows: RadioGroup,
            groupIndex: Int,
            copyIndex: Int,
            copy: DuplicateFinder.Candidate
        ) {
            val row = inflater.inflate(R.layout.item_duplicate_copy, rows, false)
            val radio = row.findViewById<RadioButton>(R.id.copyKeep)
            radio.id = View.generateViewId()
            radio.isChecked = keeping[groupIndex] == copy.photoId
            radio.setOnClickListener { keeping[groupIndex] = copy.photoId }

            // Not every discarded copy goes to the same bin, and the
            // difference matters: one comes back whenever you like, the
            // other empties itself after thirty days. A photo inside
            // WhatsApp's folder cannot be moved into the app's own bin, so
            // discarding it means handing it to Android's — which is said
            // here, on the row, before anything is chosen.
            val toSystemBin = PhotoSource.isImmovable(copy.relativePath)
            row.findViewById<TextView>(R.id.copyPath).text = getString(
                R.string.duplicates_copy_line,
                copy.relativePath + copy.displayName,
                copy.sizeBytes / 1024,
                getString(
                    if (toSystemBin) R.string.duplicates_copy_system_bin
                    else R.string.duplicates_copy_own_bin
                )
            )

            val image = row.findViewById<ImageView>(R.id.copyThumb)
            val side = (thumbSizeDp * resources.displayMetrics.density).toInt()
            image.layoutParams = image.layoutParams.apply {
                width = side
                height = side
            }
            bindThumbnail(image, copy.photoId)
            rows.addView(row)
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
                .map { photo ->
                    ReviewSession.PendingMove(
                        photo, ReviewSession.DELETION_STAGING_PATH, ReviewStatus.TRASHED, null
                    )
                }
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

    private fun binThem() {
        val moves = pending
        pending = emptyList()
        outcome.setText(R.string.duplicates_running)

        thread {
            val result = mover.applyAll(moves)
            runOnUiThread {
                toast(getString(R.string.duplicates_binned, result.succeeded, result.failed.size))
                val handover = result.forSystemBin + result.copiedOriginals
                if (handover.isNotEmpty()) {
                    systemBin.offer(handover, result.copiedOriginals.size)
                } else {
                    search()
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
