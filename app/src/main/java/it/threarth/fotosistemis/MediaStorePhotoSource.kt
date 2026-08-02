package it.threarth.fotosistemis

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.port.PhotoSource

/**
 * Reads and moves photos through MediaStore.
 *
 * Moving is an update of RELATIVE_PATH. Measured on One UI 5.1 it costs about
 * 35 ms per photo, against seconds per photo for the web version, which had
 * to copy every byte through the storage access bridge.
 */
class MediaStorePhotoSource(context: Context) : PhotoSource {

    private val resolver: ContentResolver = context.contentResolver

    private companion object {

        /**
         * Every mounted volume, so photos on a memory card are listed too.
         *
         * A photo keeps its own volume when filed: the relative path is
         * relative to the volume the file already lives on, and MediaStore
         * cannot move a file across volumes with an update.
         */
        val COLLECTION: Uri =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        /** DATE_MODIFIED is stored in seconds, everything else in millis. */
        const val MILLIS_PER_SECOND = 1000L

        const val EXPECTED_UPDATED_ROWS = 1
    }

    /**
     * Address of the photo's row, built on its own volume: an update has to
     * reach the row on the volume that actually holds the file.
     */
    fun uriFor(photo: PhotoRecord): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(photo.volumeName), photo.platformId
    )

    /**
     * Loads a downscaled bitmap for display.
     *
     * Android specific on purpose, and absent from the shared interface:
     * nothing in the shared code draws anything, and a bitmap would be the
     * wrong shape for a front end that serves images over HTTP.
     */
    fun loadThumbnail(photo: PhotoRecord, maxEdgePixels: Int): Result<Bitmap> = runCatching {
        resolver.loadThumbnail(uriFor(photo), Size(maxEdgePixels, maxEdgePixels), null)
    }

    /**
     * Lists the folders that directly contain photos, with their counts.
     *
     * Reads one column for the whole collection and groups in memory: a
     * single-column scan is cheap, and MediaStore rejects aggregate columns
     * in a projection.
     */
    override fun listFolders(): Result<List<FolderSummary>> = runCatching {
        val counts = HashMap<Pair<String, String>, Int>()
        val projection = arrayOf(
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val cursor = resolver.query(COLLECTION, projection, null, null, null)
            ?: throw IllegalStateException("MediaStore non ha restituito risultati")
        cursor.use {
            while (it.moveToNext()) {
                val volume = it.getString(0) ?: continue
                val path = it.getString(1) ?: continue
                val key = volume to path
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        counts.map { FolderSummary(it.key.first, it.key.second, it.value) }
            .sortedWith(compareBy({ it.isRemovable }, { it.relativePath }))
    }

    /** Counts the photos in one folder across every volume. */
    override fun countPhotosIn(relativePath: String): Result<Int> = runCatching {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath))
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = resolver.query(COLLECTION, projection, queryArgs, null)
            ?: throw IllegalStateException("MediaStore non ha restituito risultati")
        cursor.use { it.count }
    }

    /**
     * Lists photos in one folder, newest first.
     *
     * The folder is matched exactly, so a scan never descends into
     * subfolders. Sorting uses the resolved capture time, which cannot be
     * done in SQL: when EXIF is missing the real date comes from the file
     * name, which the database knows nothing about.
     */
    override fun listPhotos(folder: FolderSummary?): Result<List<PhotoRecord>> = runCatching {
        val queryArgs = Bundle().apply {
            if (folder != null) {
                putString(
                    ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Images.Media.VOLUME_NAME} = ? " +
                            "AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?"
                )
                putStringArray(
                    ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                    arrayOf(folder.volumeName, folder.relativePath)
                )
            }
        }
        val cursor = resolver.query(COLLECTION, PROJECTION, queryArgs, null)
            ?: throw IllegalStateException("MediaStore non ha restituito risultati")
        cursor.use { readPhotos(it) }.sortedByDescending { it.dateTakenMillis }
    }

    /** Drains a cursor into photo records. */
    private fun readPhotos(cursor: Cursor): List<PhotoRecord> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

        val photos = ArrayList<PhotoRecord>(cursor.count)
        while (cursor.moveToNext()) {
            val displayName = cursor.getString(nameColumn).orEmpty()
            val exifMillis = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn)
            val resolved = CaptureDateResolver.resolve(
                displayName = displayName,
                exifMillis = exifMillis,
                fileMillis = cursor.getLong(modifiedColumn) * MILLIS_PER_SECOND
            )
            photos.add(
                PhotoRecord(
                    platformId = cursor.getLong(idColumn),
                    volumeName = cursor.getString(volumeColumn)
                        ?: MediaStore.VOLUME_EXTERNAL_PRIMARY,
                    displayName = displayName,
                    relativePath = cursor.getString(pathColumn).orEmpty(),
                    sizeBytes = cursor.getLong(sizeColumn),
                    dateTakenMillis = resolved.millis,
                    dateSource = resolved.source
                )
            )
        }
        return photos
    }

    /**
     * Moves one photo to [destinationRelativePath], which must end with '/'.
     * The destination folder is created by the media provider if missing.
     *
     * Requires write access to the photo, granted per batch beforehand
     * through a system consent request.
     */
    override fun move(photo: PhotoRecord, destinationRelativePath: String): Result<Unit> =
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
            }
            val updated = resolver.update(uriFor(photo), values, null, null)
            if (updated != EXPECTED_UPDATED_ROWS) {
                throw IllegalStateException(
                    "Aggiornate $updated righe per ${photo.displayName}"
                )
            }
        }
}
