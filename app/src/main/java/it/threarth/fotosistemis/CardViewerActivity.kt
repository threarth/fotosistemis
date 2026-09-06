package it.threarth.fotosistemis

import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import it.threarth.fotosistemis.core.model.PhotoRecord
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A card preview opened to the size of the screen, and leafed through.
 *
 * The cards say what will happen to each photograph, but a thumbnail the
 * size of a fingernail is not enough to recognise a picture by, and
 * recognising it is the whole reason the preview exists. Any list of cards
 * hands its rows here: the same rows, the same order, the same words, only
 * large — and the finger moves through them left and right, which is how the
 * archive is read everywhere else in this app.
 *
 * Nothing can be decided here. It shows; the list decides.
 */
class CardViewerActivity : AppCompatActivity() {

    companion object {

        /**
         * The rows to show, handed over out of band.
         *
         * An intent carries about a megabyte and a list of photographs would
         * exceed it. The caller sets this immediately before starting the
         * screen, and only within the same process.
         */
        var pendingRows: List<MovePreviewAdapter.Row> = emptyList()

        /** Which of them to open on. */
        var pendingIndex: Int = 0

        /** Long enough that a scroll is not read as a swipe. */
        private const val SWIPE_FRACTION = 0.18f

        /** Big enough to fill a screen without decoding the whole file. */
        private const val VIEWER_EDGE_PIXELS = 1400
    }

    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var image: ImageView

    private var rows: List<MovePreviewAdapter.Row> = emptyList()
    private var index = 0
    private val cache = HashMap<Long, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_card_viewer)
        findViewById<View>(R.id.viewerRoot).padForSystemBars()

        photoSource = MediaStorePhotoSource(this)
        image = findViewById(R.id.viewerImage)
        rows = pendingRows
        index = pendingIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))

        if (rows.isEmpty()) return finish()

        listenForSwipes()
        show()
    }

    /**
     * Left and right step through the list.
     *
     * The gesture has to travel a real distance before it counts, or every
     * tap on the picture would jump to another photograph.
     */
    private fun listenForSwipes() {
        var startX = 0f

        image.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val travelled = event.x - startX
                    if (abs(travelled) > view.width * SWIPE_FRACTION) {
                        step(if (travelled < 0) 1 else -1)
                    }
                    view.performClick()
                    true
                }

                else -> true
            }
        }
    }

    private fun step(by: Int) {
        val wanted = index + by
        if (wanted !in rows.indices) return

        index = wanted
        show()
    }

    private fun show() {
        val row = rows[index]
        findViewById<TextView>(R.id.viewerTitle).text = row.title
        findViewById<TextView>(R.id.viewerFirst).text = row.first
        findViewById<TextView>(R.id.viewerSecond).apply {
            text = row.second.orEmpty()
            visibility = if (row.second == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.viewerPosition).text =
            getString(R.string.viewer_position, index + 1, rows.size)

        loadPicture(row.photo)
    }

    /** Decodes off the main thread, and ignores what arrives too late. */
    private fun loadPicture(photo: PhotoRecord) {
        image.setImageBitmap(cache[photo.photoId])
        image.tag = photo.photoId
        if (cache.containsKey(photo.photoId)) return

        thread {
            val bitmap = photoSource.loadThumbnail(photo, VIEWER_EDGE_PIXELS).getOrNull()
            image.post {
                if (bitmap != null) cache[photo.photoId] = bitmap
                if (image.tag == photo.photoId) image.setImageBitmap(bitmap)
            }
        }
    }
}
