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
import android.media.MediaScannerConnection
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import it.threarth.fotosistemis.core.date.CaptureDateCheck
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
class MediaStorePhotoSource(private val context: Context) : PhotoSource {

    private val resolver: ContentResolver = context.contentResolver

    /** Rows this app created are the only ones it may write into. */
    private val ownPackageName: String = context.packageName

    private companion object {

        /** EXIF has no time zone: the stamp is written as local time. */
        const val EXIF_DATE_FORMAT = "yyyy:MM:dd HH:mm:ss"

        /** Reading and writing: saving EXIF needs both on the same handle. */
        const val FILE_MODE_READ_WRITE = "rw"

        const val LOG_TAG = "Fotosistemis"

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

        const val HASH_ALGORITHM = "SHA-256"

        /** How much of the file the fingerprint is taken from. */
        const val HASH_HEAD_BYTES = 256 * 1024

        const val HASH_CHUNK_BYTES = 32 * 1024

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
        MediaStore.Images.Media.getContentUri(volumeOf(photo)), photo.platformId
    )

    /**
     * Which volume a photo lives on, falling back to the built-in storage.
     *
     * Rows the app wrote before it recorded volumes, and rows rebuilt by an
     * early migration, carry an empty volume name. Handing that to
     * MediaStore yields content://media//images/media — an address with a
     * hole in it, which every later call rejects as an invalid URI, in a
     * place far from the row that caused it.
     *
     * Nearly every photo is on the built-in storage, so assuming it is right
     * far more often than failing is, and being wrong here costs a photo not
     * found rather than a photo damaged.
     */
    private fun volumeOf(photo: PhotoRecord): String =
        photo.volumeName.ifBlank { MediaStore.VOLUME_EXTERNAL_PRIMARY }

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
    ): Result<PhotoSource.CopyResult> = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, destinationRelativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(newDisplayName))
            // Ignored by MediaProvider, which derives the date from the
            // file's own EXIF and clears it when there is none. Kept because
            // it costs nothing and other platforms may honour it; the date
            // that actually holds has to be written inside the file.
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

        // Before publishing, not after: leaving the pending state is what
        // makes MediaProvider scan the file, and the scan is where the date
        // in the archive comes from. Written here, it is read as the photo's
        // own and MediaStore records it without being asked.
        val exifWritten = writeCaptureDate(target, photo.dateTakenMillis)

        // And on the file itself, before it is published: the scan that
        // publishing triggers is the one chance to have the date recorded,
        // and it is the file's timestamp that the scan believes. Both have
        // to hold for the copy to carry its date, so both are reported.
        val stamped = filePathOf(target)
            ?.let { File(it).setLastModified(photo.dateTakenMillis) } ?: false
        val dateWritten = exifWritten && stamped

        resolver.update(
            target,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null
        )
        PhotoSource.CopyResult(ContentUris.parseId(target), dateWritten)
    }

    /**
     * Hashes the head of the file, salted with its length.
     *
     * The head carries the markers, the metadata and the first rows of the
     * image: two distinct photographs matching in all of that and in size as
     * well is not something that happens. Reading the whole of a three
     * megabyte file, for seven hundred photos, would cost two gigabytes of
     * reading to rule out a coincidence nobody has met.
     */
    /**
     * Writes the capture date into the copy itself, and says whether it took.
     *
     * MediaStore will not be told a photo's date: it derives it from the
     * file's EXIF and clears it when there is none, which is every photo
     * that came through WhatsApp. The date has to live inside the file, and
     * there it survives the scan, the gallery, and the move to a computer.
     *
     * A date the photograph already carries is never overwritten: this fills
     * a gap, it does not correct anybody.
     */
    private fun writeCaptureDate(uri: Uri, dateTakenMillis: Long): Boolean = try {
        resolver.openFileDescriptor(uri, FILE_MODE_READ_WRITE).use { descriptor ->
            val file = requireNotNull(descriptor) { "copia non riapribile" }
            val exif = ExifInterface(file.fileDescriptor)

            if (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) != null) {
                true
            } else {
                val stamp = SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US)
                    .format(Date(dateTakenMillis))
                exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
                exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
                exif.saveAttributes()
                true
            }
        }
    } catch (error: Exception) {
        // The copy is good even when its date is not: it is reported back
        // rather than thrown, so the photo is filed and the user is told.
        Log.w(LOG_TAG, "data non scritta nella copia: ${error.message}")
        false
    }

    /** What a folder holds, counting what Android has hidden inside it. */
    data class BinContents(
        val visible: Int,
        val trashed: Int,
        val earliestExpiryMillis: Long?
    ) {
        val total: Int get() = visible + trashed
    }

    /**
     * Counts a folder including the photos Android has put in its own trash.
     *
     * Deleting a photo on this phone does not remove it: the file is renamed
     * with a .trashed- prefix, kept in place for thirty days, and hidden from
     * every ordinary query. So the app looked into its own bin, was told
     * almost nothing was there, and reported an empty bin while a hundred and
     * seventy-nine photographs sat in it waiting to be destroyed.
     *
     * A bin that lies about being empty is worse than no bin: the photos are
     * still recoverable right up until they are not.
     */
    fun binContents(relativePath: String): Result<BinContents> = runCatching {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(relativePath)
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.IS_TRASHED,
            MediaStore.MediaColumns.DATE_EXPIRES
        )
        val cursor = resolver.query(COLLECTION, projection, queryArgs, null)
            ?: throw IllegalStateException("MediaStore non ha restituito risultati")

        cursor.use { rows ->
            val trashedColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)
            val expiryColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)
            var visible = 0
            var trashed = 0
            var earliest: Long? = null

            while (rows.moveToNext()) {
                if (rows.getInt(trashedColumn) == 0) {
                    visible++
                    continue
                }
                trashed++
                if (rows.isNull(expiryColumn)) continue

                val expiry = rows.getLong(expiryColumn) * MILLIS_PER_SECOND
                if (earliest == null || expiry < earliest) earliest = expiry
            }
            BinContents(visible, trashed, earliest)
        }
    }

    /**
     * Every photo this app created.
     *
     * Ownership is the whole of the permission: these are files the app made
     * and may write to. Which of them need anything done is not decided here
     * — that is what checking the carriers is for.
     */
    fun ownPhotos(): Result<List<PhotoRecord>> = runCatching {
        val queryArgs = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?"
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(ownPackageName)
            )
        }
        val cursor = resolver.query(COLLECTION, PROJECTION, queryArgs, null)
            ?: throw IllegalStateException("MediaStore non ha restituito risultati")

        cursor.use { readPhotos(it) }
    }

    /**
     * Reads what each of the three carriers says about when [photo] was taken.
     *
     * All three, always. Asking one and acting on it is how the archive and
     * the file came to disagree in the first place: EXIF was written, the
     * photo was called mended, and the gallery went on showing the day of the
     * copy because nothing had told it otherwise.
     */
    fun readCarriers(photo: PhotoRecord): Result<CaptureDateCheck.Carriers> = runCatching {
        val uri = uriFor(photo)
        val path = filePathOf(uri)

        CaptureDateCheck.Carriers(
            exifMillis = readExifMillis(uri),
            fileMillis = path?.let { File(it).lastModified() }?.takeIf { it > 0 },
            indexMillis = readIndexMillis(uri)
        )
    }

    /**
     * Writes [expectedMillis] into whichever carrier does not already say it,
     * and reports what the carriers say afterwards.
     *
     * An EXIF date that is present but different is never overwritten. The
     * app's own date may have been read off a file name, and the photograph's
     * own account of itself outranks that; a disagreement there is something
     * for the user to look at, not for the app to settle quietly.
     *
     * The verdict returned is read back from the file and the archive after
     * writing, never assumed from the writing having been attempted.
     */
    fun healCaptureDate(
        photo: PhotoRecord,
        expectedMillis: Long
    ): Result<CaptureDateCheck.Verdict> = runCatching {
        val before = CaptureDateCheck.check(
            expectedMillis, readCarriers(photo).getOrThrow()
        )
        if (before.settled) return@runCatching before

        val uri = uriFor(photo)
        if (before.exif == CaptureDateCheck.State.SILENT) writeCaptureDate(uri, expectedMillis)

        // The file's timestamp last, because writing EXIF changes it, and a
        // scan after that: the index only revisits a photo whose file has
        // moved under it, and that scan is the only way it is ever filled.
        val path = filePathOf(uri)
        if (path != null) {
            File(path).setLastModified(expectedMillis)
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        }

        CaptureDateCheck.check(expectedMillis, readCarriers(photo).getOrThrow())
    }

    /** The date the file itself claims, or null when it claims none. */
    private fun readExifMillis(uri: Uri): Long? = try {
        resolver.openInputStream(uri).use { stream ->
            val exif = ExifInterface(requireNotNull(stream) { "foto illeggibile" })
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?.let { SimpleDateFormat(EXIF_DATE_FORMAT, Locale.US).parse(it) }
                ?.time
        }
    } catch (error: Exception) {
        Log.w(LOG_TAG, "EXIF non leggibile: ${error.message}")
        null
    }

    /** The date the archive has recorded, or null when it has none. */
    private fun readIndexMillis(uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_TAKEN), null, null, null)
            ?.use { row ->
                if (row.moveToFirst() && !row.isNull(0)) row.getLong(0) else null
            }
    } catch (error: Exception) {
        Log.w(LOG_TAG, "data d'archivio non leggibile: ${error.message}")
        null
    }

    /** Where a photo actually sits on disk, when the platform will say. */
    private fun filePathOf(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { row ->
                if (row.moveToFirst()) row.getString(0) else null
            }
    } catch (error: Exception) {
        Log.w(LOG_TAG, "percorso non leggibile: ${error.message}")
        null
    }

    override fun contentHash(photo: PhotoRecord): Result<String> = runCatching {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        digest.update(photo.sizeBytes.toString().toByteArray())

        resolver.openInputStream(uriFor(photo)).use { input ->
            val stream = requireNotNull(input) { "${photo.displayName}: illeggibile" }
            val buffer = ByteArray(HASH_CHUNK_BYTES)
            var read = 0
            while (read < HASH_HEAD_BYTES) {
                val got = stream.read(buffer)
                if (got <= 0) break
                digest.update(buffer, 0, got)
                read += got
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
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
