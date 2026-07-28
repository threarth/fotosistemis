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
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long,
        val dateTakenMillis: Long
    )

    companion object {

        /** Folder holding photos the user chose to discard. */
        const val TRASH_FOLDER = "_FotoSistemis_Trash"

        /** Prefix of the folders created for tags: famiglia -> arch_famiglia. */
        const val ARCHIVE_PREFIX = "arch_"

        /** Primary external volume: internal shared storage, never the SD card. */
        private val COLLECTION: Uri =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        private val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        /**
         * Best known capture time. DATE_TAKEN comes from EXIF and is absent on
         * screenshots and downloads, so DATE_MODIFIED (seconds) is the
         * fallback. The web version had to guess this from the file name.
         */
        private const val CAPTURE_TIME =
            "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, " +
                    "${MediaStore.Images.Media.DATE_MODIFIED} * 1000)"

        /** Folders this app manages are never offered for review again. */
        private const val EXCLUDE_MANAGED_FOLDERS =
            "${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE '%$ARCHIVE_PREFIX%' " +
                    "AND ${MediaStore.Images.Media.RELATIVE_PATH} NOT LIKE '%$TRASH_FOLDER%'"

        private const val SORT_NEWEST_FIRST = "$CAPTURE_TIME DESC"
        private const val EXPECTED_UPDATED_ROWS = 1
        private const val THUMBNAIL_EDGE_PIXELS = 1024
    }

    /**
     * Lists reviewable photos, newest first, restricted to [periodMillis] when
     * given. Folders created by this app are always excluded.
     */
    fun queryPhotos(periodMillis: LongRange?): Result<List<Photo>> {
        val selection = StringBuilder(EXCLUDE_MANAGED_FOLDERS)
        val arguments = ArrayList<String>()

        if (periodMillis != null) {
            selection.append(" AND $CAPTURE_TIME BETWEEN ? AND ?")
            arguments.add(periodMillis.first.toString())
            arguments.add(periodMillis.last.toString())
        }

        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arguments.toTypedArray())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_NEWEST_FIRST)
        }

        return try {
            val cursor = resolver.query(COLLECTION, PROJECTION, queryArgs, null)
                ?: return Result.failure(IllegalStateException("MediaStore returned no cursor"))
            cursor.use { Result.success(readPhotos(it)) }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Drains [cursor] into Photo objects. */
    private fun readPhotos(cursor: Cursor): List<Photo> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

        val photos = ArrayList<Photo>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            photos.add(
                Photo(
                    mediaId = id,
                    uri = ContentUris.withAppendedId(COLLECTION, id),
                    displayName = cursor.getString(nameColumn) ?: "",
                    relativePath = cursor.getString(pathColumn) ?: "",
                    sizeBytes = cursor.getLong(sizeColumn),
                    dateTakenMillis = captureTimeOf(cursor, takenColumn, modifiedColumn)
                )
            )
        }
        return photos
    }

    /** Mirrors the SQL COALESCE, so sort order and displayed date agree. */
    private fun captureTimeOf(cursor: Cursor, takenColumn: Int, modifiedColumn: Int): Long {
        if (!cursor.isNull(takenColumn)) {
            val taken = cursor.getLong(takenColumn)
            if (taken > 0L) return taken
        }
        return cursor.getLong(modifiedColumn) * 1000L
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
