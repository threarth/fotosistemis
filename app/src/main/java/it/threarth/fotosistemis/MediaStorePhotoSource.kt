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

        /** Enough of a photo to tell it from whichever one took its id. */
        val IDENTITY_PROJECTION = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )
    }

    /**
     * Address of the photo's row, built on its own volume: an update has to
     * reach the row on the volume that actually holds the file.
     */
    fun uriFor(photo: PhotoRecord): Uri = ContentUris.withAppendedId(
        MediaStore.Images.Media.getContentUri(photo.volumeName), photo.platformId
    )

    /**
     * Writes a new photo with the same bytes, in a folder we may write to.
     *
     * The values are set on insert, which is the one moment they can be:
     * MediaProvider refuses writes to DATE_TAKEN on a row owned by someone
     * else, but this row is ours from the start, so the copy keeps the
     * capture date instead of being born today.
     *
     * IS_PENDING hides the file until the bytes are there: a half-written
     * photo appearing in every gallery on the device is worse than a slow
     * one.
     */
    override fun copyInto(
        photo: PhotoRecord,
        destinationRelativePath: String,
        newDisplayName: String
    ): Result<Long> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(newDisplayName))
            put(MediaStore.Images.Media.DATE_TAKEN, photo.dateTakenMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(photo.volumeName)
        val target = resolver.insert(collection, values)
            ?: throw IllegalStateException("${photo.displayName}: copia non creata")

        try {
            resolver.openInputStream(uriFor(photo)).use { input ->
                resolver.openOutputStream(target).use { output ->
                    requireNotNull(input) { "sorgente illeggibile" }
                        .copyTo(requireNotNull(output) { "destinazione non scrivibile" })
                }
            }
        } catch (error: Exception) {
            // A copy that never finished is worse than no copy: it would sit
            // in the archive as a truncated photo nobody asked for.
            resolver.delete(target, null, null)
            throw error
        }

        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        ContentUris.parseId(target)
    }

    /** JPEG unless the name says otherwise; nothing else is written here. */
    private fun mimeTypeOf(displayName: String): String =
        when (displayName.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

    /**
     * Throws unless the row still holds the photo the caller planned for.
     *
     * A write addresses a photo by its MediaStore id, and that id is a
     * rewritable shortcut rather than an identity: a rescan or a remount can
     * hand it to a different photo. Between reading an archive and applying a
     * batch there can be minutes, and without this the app would quietly move
     * or rename whichever photo inherited the number.
     *
     * Name and size are always compared, because the app stores them exactly
     * as MediaStore reported them. The capture date joins them only when it
     * came from MediaStore as well: two thirds of the photos on a phone have
     * no DATE_TAKEN, and for those the app holds a date read out of the file
     * name, which would never match and would reject every photo.
     */
    private fun confirmIdentity(photo: PhotoRecord) {
        val current = readIdentity(photo)
            ?: throw IllegalStateException(
                "${photo.displayName}: non e' piu' nell'indice di sistema"
            )

        val changed = current.displayName != photo.displayName ||
                current.sizeBytes != photo.sizeBytes ||
                (photo.dateSource == CaptureDateResolver.Source.EXIF &&
                        current.exifMillis != photo.dateTakenMillis)

        if (changed) {
            throw IllegalStateException(
                "${photo.displayName}: l'indice di sistema e' cambiato, " +
                        "quel numero ora e' di ${current.displayName}"
            )
        }
    }

    /** What a photo's row says about it right now. */
    private data class Identity(
        val displayName: String,
        val sizeBytes: Long,
        val exifMillis: Long?
    )

    /** Reads the row back, or null when it has gone. */
    private fun readIdentity(photo: PhotoRecord): Identity? =
        resolver.query(uriFor(photo), IDENTITY_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            Identity(
                displayName = cursor.getString(0).orEmpty(),
                sizeBytes = cursor.getLong(1),
                exifMillis = CaptureDateResolver.normaliseExif(cursor.getLong(2))
            )
        }

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
     * Moves one photo to [destinationRelativePath], which must end with '/',
     * renaming it to [newDisplayName] when one is given. The destination
     * folder is created by the media provider if missing.
     *
     * Both columns travel in one ContentValues, so the provider either
     * applies the whole change or none of it.
     *
     * Requires write access to the photo, granted per batch beforehand
     * through a system consent request.
     */
    override fun move(
        photo: PhotoRecord,
        destinationRelativePath: String,
        newDisplayName: String?
    ): Result<Unit> =
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
                if (newDisplayName != null && newDisplayName != photo.displayName) {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                }
            }
            confirmIdentity(photo)
            val updated = resolver.update(uriFor(photo), values, null, null)
            if (updated != EXPECTED_UPDATED_ROWS) {
                throw IllegalStateException(
                    "Aggiornate $updated righe per ${photo.displayName}"
                )
            }
        }
}
