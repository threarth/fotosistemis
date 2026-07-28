package it.threarth.fotosistemis

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * Reads and moves photos through MediaStore.
 *
 * This class exists to validate a single assumption: that moving a photo is a
 * metadata-only rename performed by the media provider, not a byte-for-byte
 * copy. ContentResolver.update() on MediaColumns.RELATIVE_PATH is the only API
 * that can do that. If Samsung's One UI media provider silently falls back to
 * copying, the timings produced here will show it.
 */
class MediaStoreRepository(context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    /** A single photo as indexed by MediaStore. */
    data class Photo(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long
    )

    private companion object {

        /** Primary external volume: internal shared storage, never the SD card. */
        val COLLECTION: Uri =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.SIZE
        )

        const val PATH_SELECTION = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        const val SORT_ORDER = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        const val EXPECTED_UPDATED_ROWS = 1
    }

    /**
     * Lists photos whose RELATIVE_PATH starts with [relativePathPrefix],
     * newest first, capped at [limit] rows.
     *
     * Sorting uses DATE_TAKEN, read from real EXIF metadata by the media
     * provider. The web version had to guess dates from file names.
     */
    fun queryPhotos(relativePathPrefix: String, limit: Int): Result<List<Photo>> {
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, PATH_SELECTION)
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("$relativePathPrefix%")
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, SORT_ORDER)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

        return try {
            val cursor = resolver.query(COLLECTION, PROJECTION, queryArgs, null)
                ?: return Result.failure(IllegalStateException("MediaStore returned no cursor"))
            cursor.use { Result.success(readPhotos(it)) }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /** Drains [cursor] into Photo objects. Assumes PROJECTION column order. */
    private fun readPhotos(cursor: Cursor): List<Photo> {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

        val photos = ArrayList<Photo>(cursor.count)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            photos.add(
                Photo(
                    uri = ContentUris.withAppendedId(COLLECTION, id),
                    displayName = cursor.getString(nameColumn) ?: "",
                    relativePath = cursor.getString(pathColumn) ?: "",
                    sizeBytes = cursor.getLong(sizeColumn)
                )
            )
        }
        return photos
    }

    /**
     * Moves one photo to [destinationRelativePath], which must end with '/'.
     * The destination folder is created by the media provider if missing.
     *
     * Requires write access to the URI, obtained in batch beforehand through
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
