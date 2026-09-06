package it.threarth.fotosistemis

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.model.PhotoRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The photos whose date the user has contradicted.
 *
 * A wrong date cannot be detected by reading the file: a photograph that
 * came through WhatsApp carries the day it was sent, and that date is
 * perfectly well formed. Only someone who recognises the picture knows
 * otherwise, so the judgement is kept here until there is a way to recover
 * the true time. Nothing on disk is changed by being listed.
 */
class SuspectDatesActivity : AppCompatActivity() {

    private companion object {

        /** Day precision: what is being doubted is the day, not the second. */
        const val DAY_FORMAT = "d MMM yyyy"
    }

    private lateinit var inventory: PhotoInventory
    private lateinit var photoSource: MediaStorePhotoSource
    private lateinit var listView: ListView
    private lateinit var summary: TextView

    private var photos: List<PhotoRecord> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_suspect_dates)
        applySystemBarInsets()

        inventory = PhotoInventory(AndroidDatabase(this))
        photoSource = MediaStorePhotoSource(this)
        listView = findViewById(R.id.suspectList)
        summary = findViewById(R.id.suspectSummary)
        listView.setOnItemClickListener { _, _, position, _ ->
            confirmClearing(photos[position])
        }

        load()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.suspectRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** Reads off the main thread: this is a database query over the archive. */
    private fun load() {
        thread {
            val found = inventory.loadDateSuspectRecords().getOrElse { emptyList() }
            runOnUiThread {
                photos = found
                redraw()
            }
        }
    }

    private fun redraw() {
        summary.text =
            if (photos.isEmpty()) getString(R.string.suspect_empty)
            else getString(R.string.suspect_summary, photos.size)

        val giorno = SimpleDateFormat(DAY_FORMAT, Locale.getDefault())
        listView.adapter = MovePreviewAdapter(
            this,
            photos.map { photo ->
                MovePreviewAdapter.Row(
                    photo = photo,
                    title = photo.displayName,
                    first = photo.relativePath,
                    second = getString(
                        R.string.suspect_row_taken,
                        giorno.format(Date(photo.dateTakenMillis))
                    )
                )
            },
            photoSource
        )
    }

    private fun confirmClearing(photo: PhotoRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.suspect_clear_title)
            .setMessage(getString(R.string.suspect_clear_message, photo.displayName))
            .setPositiveButton(R.string.action_date_right) { _, _ ->
                inventory.markDateSuspect(photo.photoId, false)
                load()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
