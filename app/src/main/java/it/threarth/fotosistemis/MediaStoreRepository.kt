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

/**
 * Reads and moves photos through MediaStore.
 *
 * Moving is a ContentResolver.update() on MediaColumns.RELATIVE_PATH. Measured
 * on One UI 5.1 it costs about 35 ms per photo, against seconds per photo for
 * the web version, which had to copy every byte through the SAF bridge.
 */
class MediaStoreRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /** A single photo as indexed by MediaStore. */
    data class Photo(
        val mediaId: Long,
        val volumeName: String,
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long,
        val dateTakenMillis: Long,
        val dateSource: CaptureDateResolver.Source
    )

    private companion object {

        /**
         * Every mounted volume, so photos on a memory card are listed too.
         *
         * A photo keeps its own volume when filed: RELATIVE_PATH is relative
         * to the volume the file already lives on, and MediaStore cannot
         * move a file across volumes with an update. Crossing volumes would
         * mean copying every byte, which is exactly the cost the native
         * version exists to avoid.
         */
        private val COLLECTION: Uri =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        /** DATE_MODIFIED is stored in seconds, everything else in millis. */
        private const val MILLIS_PER_SECOND = 1000L

        private const val EXPECTED_UPDATED_ROWS = 1
        private const val THUMBNAIL_EDGE_PIXELS = 1024
    }

    /** A folder on one volume that currently holds photos, and how many. */
    data class FolderSummary(
        val volumeName: String,
        val relativePath: String,
        val photoCount: Int
    ) {

        /** True when the folder lives on a memory card rather than internal storage. */
        val isRemovable: Boolean get() = volumeName != MediaStore.VOLUME_EXTERNAL_PRIMARY

        /**
         * Identity without the photo count, which changes as photos move.
         * Comparing whole objects would make a folder look different from
         * itself after every batch.
         */
        fun sameAs(other: FolderSummary?): Boolean =
            other != null && other.volumeName == volumeName && other.relativePath == relativePath
    }

    /**
     * Lists the folders that directly contain photos, with their counts.
     *
     * Reads one column for the whole collection and groups in memory: a
     * single-column scan is cheap, and MediaStore rejects aggregate columns
     * in a projection.
     */
    fun queryFolders(): Result<List<FolderSummary>> = try {
        val counts = HashMap<Pair<String, String>, Int>()
        val projection = arrayOf(
            MediaStore.Images.Media.VOLUME_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        val cursor = resolver.query(COLLECTION, projection, null, null, null)
            ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use {
            while (it.moveToNext()) {
                val volume = it.getString(0) ?: continue
                val path = it.getString(1) ?: continue
                val key = volume to path
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        Result.success(
            counts.map { FolderSummary(it.key.first, it.key.second, it.value) }
                .sortedWith(compareBy({ it.isRemovable }, { it.relativePath }))
        )
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Counts the photos in one folder across every volume.
     *
     * Restricted to that folder on purpose: asking the folder list for the
     * same number would read every row on the device to learn about one
     * directory.
     */
    fun countPhotosIn(relativePath: String): Result<Int> = try {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            )
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(relativePath))
        }
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val cursor = resolver.query(COLLECTION, projection, queryArgs, null)
            ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use { Result.success(it.count) }
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Lists photos in one folder, newest first.
     *
     * Sorted on the resolved capture time. The period filter is applied by
     * the caller, not here and not in SQL: when EXIF is missing the real
     * date comes from the file name, which the database knows nothing about.
     *
     * [folderRelativePath] matches the folder **exactly**, so scanning a
     * folder never descends into its subfolders: selecting DCIM/ returns the
     * files sitting in DCIM and not those in DCIM/Famiglia/. That is also
     * what keeps destination folders from being picked up again by a scan of
     * the folder that contains them. Passing null searches every folder.
     *
     * Photos in the system trash are omitted by MediaStore itself.
     */
    fun queryPhotos(folder: FolderSummary?): Result<List<Photo>> {
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

        return try {
            val cursor = resolver.query(COLLECTION, PROJECTION, queryArgs, null)
                ?: return Result.failure(IllegalStateException("MediaStore returned no cursor"))
            val photos = cursor.use { readPhotos(it) }
            Result.success(photos.sortedByDescending { it.dateTakenMillis })
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Drains [cursor] into Photo objects. */
    private fun readPhotos(cursor: Cursor): List<Photo> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.VOLUME_NAME)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

        val photos = ArrayList<Photo>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val volume = cursor.getString(volumeColumn) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            val displayName = cursor.getString(nameColumn) ?: ""
            val exifMillis = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn)
            val resolved = CaptureDateResolver.resolve(
                displayName = displayName,
                exifMillis = exifMillis,
                fileMillis = cursor.getLong(modifiedColumn) * MILLIS_PER_SECOND
            )
            photos.add(
                Photo(
                    mediaId = id,
                    volumeName = volume,
                    // Built on the photo's own volume: an update must reach
                    // the row on the volume that actually holds the file.
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.getContentUri(volume), id
                    ),
                    displayName = displayName,
                    relativePath = cursor.getString(pathColumn) ?: "",
                    sizeBytes = cursor.getLong(sizeColumn),
                    dateTakenMillis = resolved.millis,
                    dateSource = resolved.source
                )
            )
        }
        return photos
    }

    /**
     * Loads a downscaled bitmap for review.
     *
     * loadThumbnail hits the system thumbnail cache, so it avoids decoding a
     * multi-megabyte JPEG for a screen that cannot show that many pixels.
     */
    fun loadThumbnail(photo: Photo): Result<Bitmap> = try {
        val size = Size(THUMBNAIL_EDGE_PIXELS, THUMBNAIL_EDGE_PIXELS)
        Result.success(resolver.loadThumbnail(photo.uri, size, null))
    } catch (error: Exception) {
        Result.failure(error)
    }

    /**
     * Moves one photo to [destinationRelativePath], which must end with '/'.
     * The destination folder is created by the media provider if missing.
     *
     * Requires write access to the URI, granted per batch beforehand through
     * MediaStore.createWriteRequest().
     */
    fun move(photo: Photo, destinationRelativePath: String): Result<Unit> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
        }

        return try {
            val updatedRows = resolver.update(photo.uri, values, null, null)
            if (updatedRows == EXPECTED_UPDATED_ROWS) {
                Result.success(Unit)
            } else {
                Result.failure(
                    IllegalStateException(
                        "update() changed $updatedRows rows for ${photo.displayName}"
                    )
                )
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
