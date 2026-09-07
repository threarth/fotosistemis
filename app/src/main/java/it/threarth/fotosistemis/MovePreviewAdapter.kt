package it.threarth.fotosistemis

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * A photograph as a card: the picture, its name, and where it stands.
 *
 * A list of paths is checkable only by someone who remembers what each file
 * looks like. The picture is what makes a wrong destination obvious, and in
 * the previews this is the last moment before the writing.
 */
class MovePreviewAdapter(
    context: Context,
    private val rows: List<Row>,
    private val photoSource: MediaStorePhotoSource
) : BaseAdapter() {

    /**
     * One card. [second] is optional because not every list is about a move:
     * where a photo is going only makes sense when it is going somewhere.
     */
    data class Row(
        val photo: PhotoRecord,
        val title: String,
        val first: String,
        val second: String? = null
    )

    companion object {

        /** Small enough to decode quickly while a list is being scrolled. */
        private const val THUMBNAIL_EDGE_PIXELS = 128

        /** Cards for a batch about to be written: from here, to there. */
        fun forMoves(
            context: Context,
            moves: List<ReviewSession.PendingMove>,
            photoSource: MediaStorePhotoSource
        ): MovePreviewAdapter {
            val rows = moves.map { rowFor(context, it) }
            return MovePreviewAdapter(context, rows, photoSource)
        }

        /** The card for one move: the name it will have, from here, to there. */
        fun rowFor(context: Context, move: ReviewSession.PendingMove): Row {
            val name = move.newDisplayName ?: move.photo.displayName
            return Row(
                photo = move.photo,
                title = name,
                first = context.getString(
                    R.string.move_from,
                    move.photo.relativePath + move.photo.displayName
                ),
                second = context.getString(
                    R.string.move_to,
                    move.destinationRelativePath + name
                )
            )
        }
    }

    private val context = context
    private val inflater = LayoutInflater.from(context)
    private val cache = HashMap<Long, Bitmap>()

    override fun getCount(): Int = rows.size

    override fun getItem(position: Int): Row = rows[position]

    override fun getItemId(position: Int): Long = rows[position].photo.photoId

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_move_preview, parent, false)
        val row = rows[position]

        view.findViewById<TextView>(R.id.moveName).text = row.title
        view.findViewById<TextView>(R.id.moveFrom).text = row.first
        view.findViewById<TextView>(R.id.moveTo).apply {
            text = row.second.orEmpty()
            visibility = if (row.second == null) View.GONE else View.VISIBLE
        }

        bindThumbnail(view.findViewById(R.id.moveThumb), row.photo)

        // Wired here rather than in each screen that shows cards: a preview
        // exists to be recognised, a thumbnail this size often cannot be,
        // and a list that forgot to offer the larger view would be a preview
        // that cannot do its one job.
        view.findViewById<ImageView>(R.id.moveThumb).setOnClickListener { openFullScreen(position) }
        return view
    }

    /** Opens the whole list at [position], to be leafed through in the large. */
    private fun openFullScreen(position: Int) {
        CardViewerActivity.pendingRows = rows
        CardViewerActivity.pendingIndex = position
        context.startActivity(Intent(context, CardViewerActivity::class.java))
    }

    /**
     * Fills the picture when it arrives, and only if the row still wants it.
     *
     * Rows are recycled while a list scrolls, so a thumbnail that took a
     * moment to decode can come back to a view showing something else. The
     * tag says which photo the view is currently for.
     */
    private fun bindThumbnail(image: ImageView, photo: PhotoRecord) {
        val photoId = photo.photoId
        image.tag = photoId
        image.setImageBitmap(cache[photoId])
        if (cache.containsKey(photoId)) return

        thread {
            val bitmap = photoSource.loadThumbnail(photo, THUMBNAIL_EDGE_PIXELS).getOrNull()
            image.post {
                if (bitmap != null) cache[photoId] = bitmap
                if (image.tag == photoId) image.setImageBitmap(bitmap)
            }
        }
    }
}
