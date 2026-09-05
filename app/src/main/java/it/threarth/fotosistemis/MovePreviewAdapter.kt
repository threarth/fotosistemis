package it.threarth.fotosistemis

import android.content.Context
import android.graphics.Bitmap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import it.threarth.fotosistemis.core.review.ReviewSession
import kotlin.concurrent.thread

/**
 * Shows what a batch would write: the photo itself, and both its addresses.
 *
 * A list of paths is checkable only by someone who remembers what each file
 * looks like. The picture is what makes a wrong destination obvious, and this
 * is the last moment before the writing.
 */
class MovePreviewAdapter(
    context: Context,
    private val moves: List<ReviewSession.PendingMove>,
    private val photoSource: MediaStorePhotoSource
) : BaseAdapter() {

    private companion object {

        /** Small enough to decode quickly while a list is being scrolled. */
        const val THUMBNAIL_EDGE_PIXELS = 128
    }

    private val inflater = LayoutInflater.from(context)
    private val cache = HashMap<Long, Bitmap>()

    override fun getCount(): Int = moves.size

    override fun getItem(position: Int): ReviewSession.PendingMove = moves[position]

    override fun getItemId(position: Int): Long = moves[position].photo.photoId

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: inflater.inflate(R.layout.item_move_preview, parent, false)
        val move = moves[position]
        val photo = move.photo
        val name = move.newDisplayName ?: photo.displayName

        view.findViewById<TextView>(R.id.moveName).text = name
        view.findViewById<TextView>(R.id.moveFrom).text =
            view.context.getString(R.string.move_from, photo.relativePath + photo.displayName)
        view.findViewById<TextView>(R.id.moveTo).text =
            view.context.getString(R.string.move_to, move.destinationRelativePath + name)

        bindThumbnail(view.findViewById(R.id.moveThumb), move)
        return view
    }

    /**
     * Fills the picture when it arrives, and only if the row still wants it.
     *
     * Rows are recycled while a list scrolls, so a thumbnail that took a
     * moment to decode can come back to a view showing something else. The
     * tag says which photo the view is currently for.
     */
    private fun bindThumbnail(image: ImageView, move: ReviewSession.PendingMove) {
        val photoId = move.photo.photoId
        image.tag = photoId
        image.setImageBitmap(cache[photoId])
        if (cache.containsKey(photoId)) return

        thread {
            val bitmap = photoSource.loadThumbnail(move.photo, THUMBNAIL_EDGE_PIXELS).getOrNull()
            image.post {
                if (bitmap != null) cache[photoId] = bitmap
                if (image.tag == photoId) image.setImageBitmap(bitmap)
            }
        }
    }
}
