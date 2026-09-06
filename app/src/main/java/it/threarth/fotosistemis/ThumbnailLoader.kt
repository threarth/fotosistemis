package it.threarth.fotosistemis

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import it.threarth.fotosistemis.core.model.PhotoRecord
import java.util.concurrent.Executors

/**
 * Fetches thumbnails a few at a time, for lists that show many at once.
 *
 * A screen that starts one thread per row starts hundreds while a finger
 * moves, and the platform's thumbnail service does not survive that: it is
 * asked for a hundred pictures at once, refuses most of them, and the grid
 * fills with holes that look like missing photographs. Four at a time is
 * enough to keep a scroll fed and few enough to be answered.
 *
 * A picture that could not be produced is remembered as such. Otherwise the
 * same failure is requested again on every pass over the row, and the queue
 * never empties.
 */
class ThumbnailLoader(
    private val photoSource: MediaStorePhotoSource,
    private val edgePixels: Int
) {

    private companion object {

        /** Enough to keep a scrolling list fed, few enough to be answered. */
        const val WORKERS = 4

        const val LOG_TAG = "Fotosistemis"
    }

    private val pool = Executors.newFixedThreadPool(WORKERS)

    /** The queue that always runs, whatever became of the view. */
    private val mainThread = Handler(Looper.getMainLooper())

    /** Touched only from the main thread, so it needs no lock of its own. */
    private val cache = HashMap<Long, Bitmap?>()

    /**
     * Puts [photo]'s thumbnail into [image], now or when it arrives.
     *
     * The view is tagged with the photo it is currently for: rows are reused
     * while a list scrolls, so a picture that took a moment can come back to
     * a view showing something else entirely.
     */
    fun into(image: ImageView, photo: PhotoRecord) {
        val photoId = photo.photoId
        image.tag = photoId

        if (cache.containsKey(photoId)) {
            image.setImageBitmap(cache[photoId])
            return
        }
        image.setImageBitmap(null)

        pool.execute {
            val bitmap = photoSource.loadThumbnail(photo, edgePixels)
                .onFailure { Log.w(LOG_TAG, "miniatura non prodotta: ${it.message}") }
                .getOrNull()

            // Delivered through the main thread's own queue, not the view's.
            // A view posts reliably only while it is attached, and a grid
            // detaches the rows that scroll away: the answer for those was
            // dropped, the result never reached the cache, and the tile
            // stayed empty for good — asked again on the next pass, dropped
            // again, for ever.
            mainThread.post {
                cache[photoId] = bitmap
                if (image.tag == photoId) image.setImageBitmap(bitmap)
            }
        }
    }

    /** Stops the workers when the screen holding them goes away. */
    fun stop() {
        pool.shutdownNow()
    }
}
