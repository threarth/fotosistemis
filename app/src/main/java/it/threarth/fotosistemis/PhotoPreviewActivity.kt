package it.threarth.fotosistemis

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import it.threarth.fotosistemis.core.model.PhotoRecord
import kotlin.concurrent.thread
import kotlin.math.roundToLong

/**
 * Shows the photos an operation would affect, before it happens.
 *
 * A count alone is not something anyone can check: three thousand is either
 * right or catastrophic, and the difference is only visible by looking. This
 * screen turns a number into something a person can actually verify, and
 * running through it at an adjustable pace is what makes a batch that size
 * reviewable at all.
 */
class PhotoPreviewActivity : AppCompatActivity() {

    companion object {

        /**
         * The photos to show, handed over out of band.
         *
         * An intent carries about a megabyte, which thousands of records
         * would exceed. The caller sets this immediately before starting the
         * screen, and only within the same process.
         */
        var pendingPhotos: List<PhotoRecord> = emptyList()

        /** Line describing what is being previewed. */
        var pendingSummary: String = ""

        /** Set to true when the user confirms rather than backs out. */
        var confirmed: Boolean = false

        private const val SLOWEST_MILLIS = 2000L
        private const val FASTEST_MILLIS = 120L
        private const val THUMBNAIL_EDGE_PIXELS = 1024
        private const val SEEK_BAR_RANGE = 100f
    }

    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var image: ImageView
    private lateinit var counter: TextView
    private lateinit var speedLabel: TextView
    private lateinit var playButton: Button
    private lateinit var speedBar: SeekBar

    private val handler = Handler(Looper.getMainLooper())
    private var photos: List<PhotoRecord> = emptyList()
    private var index = 0
    private var playing = false

    /** Advances while playing, rescheduling itself at the current pace. */
    private val advance = object : Runnable {
        override fun run() {
            if (!playing) return
            if (index >= photos.size - 1) {
                stopPlaying()
                return
            }
            index++
            render()
            handler.postDelayed(this, intervalMillis())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_photo_preview)
        applySystemBarInsets()

        photoSource = MediaStorePhotoSource(this)
        photos = pendingPhotos
        confirmed = false

        image = findViewById(R.id.previewImage)
        counter = findViewById(R.id.previewCounter)
        speedLabel = findViewById(R.id.previewSpeedLabel)
        playButton = findViewById(R.id.previewPlayButton)
        speedBar = findViewById(R.id.previewSpeed)
        findViewById<TextView>(R.id.previewSummary).text = pendingSummary

        wireControls()
        render()
        updateSpeedLabel()
    }

    /** Playing while the screen is not in front would waste work silently. */
    override fun onPause() {
        super.onPause()
        stopPlaying()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(advance)
        pendingPhotos = emptyList()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.previewRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireControls() {
        playButton.setOnClickListener { if (playing) stopPlaying() else startPlaying() }

        findViewById<Button>(R.id.previewConfirmButton).setOnClickListener {
            confirmed = true
            setResult(Activity.RESULT_OK)
            finish()
        }
        findViewById<Button>(R.id.previewCancelButton).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        speedBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
                updateSpeedLabel()

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // Tapping the photo steps forward, for going through it by hand.
        image.setOnClickListener {
            stopPlaying()
            if (index < photos.size - 1) {
                index++
                render()
            }
        }
    }

    private fun startPlaying() {
        if (photos.size < 2) return
        // Reaching the end and pressing play again starts over, rather than
        // appearing to do nothing.
        if (index >= photos.size - 1) index = 0
        playing = true
        playButton.setText(R.string.preview_pause)
        handler.postDelayed(advance, intervalMillis())
    }

    private fun stopPlaying() {
        playing = false
        playButton.setText(R.string.preview_play)
        handler.removeCallbacks(advance)
    }

    /** Slider to delay: right is faster, which is how people read a slider. */
    private fun intervalMillis(): Long {
        val fraction = speedBar.progress / SEEK_BAR_RANGE
        return (SLOWEST_MILLIS - fraction * (SLOWEST_MILLIS - FASTEST_MILLIS)).roundToLong()
    }

    private fun updateSpeedLabel() {
        speedLabel.text = getString(R.string.preview_speed, intervalMillis())
    }

    private fun render() {
        val photo = photos.getOrNull(index) ?: return
        counter.text = getString(
            R.string.preview_counter, index + 1, photos.size, photo.relativePath
        )
        image.tag = photo.photoId
        thread {
            val bitmap = photoSource.loadThumbnail(photo, THUMBNAIL_EDGE_PIXELS).getOrNull()
            runOnUiThread { showIfCurrent(photo.photoId, bitmap) }
        }
    }

    /** Drops a bitmap that finished decoding after the run moved on. */
    private fun showIfCurrent(photoId: Long, bitmap: Bitmap?) {
        if (image.tag != photoId) return
        if (bitmap != null) image.setImageBitmap(bitmap)
    }
}
