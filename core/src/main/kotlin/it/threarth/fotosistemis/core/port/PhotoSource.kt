package it.threarth.fotosistemis.core.port

import it.threarth.fotosistemis.core.model.FolderSummary
import it.threarth.fotosistemis.core.model.PhotoRecord

/**
 * Where photos are read from and moved to.
 *
 * Android implements this over MediaStore, a desktop build would implement
 * it over the file system.
 */
interface PhotoSource {

    companion object {

        /**
         * Folder under which each app keeps its own media.
         *
         * In practice, on this archive, it means WhatsApp: fifteen thousand
         * photographs living under Android/media/com.whatsapp. Everything
         * below is the whole of how the app treats them, gathered here
         * because the rules differ from the rest of the archive at almost
         * every step, and each difference looks like a bug to someone
         * reading only the general rule.
         *
         * **The constraint.** Scoped storage lets an app modify a photo it
         * does not own, but not take it out of another app's directory: the
         * consent covers the file, not the place. A move is refused however
         * it is asked for, so no amount of permission-granting will help.
         *
         * **What decides is the folder, never the name.** A photo is treated
         * this way because it sits under Android/media, and for no other
         * reason. Once copied into a category it is an ordinary photograph
         * — moved, renamed, reorganised like any other — even though its
         * name still reads IMG-20260826-WA0007, and even though the
         * original it came from is still subject to every rule below. The
         * name is read for one thing only, the date it carries, and that is
         * read from every file name that has a date in it.
         *
         * **Reviewing and deciding work normally.** These photos are read,
         * shown, dated, rated, tagged and decided about like any others.
         * Only the file operations differ.
         *
         * **Filing copies instead of moving.** The bytes are written to a
         * new file in the category folder and the original stays behind.
         * The copy is a photo in its own right in the inventory, carrying
         * the decision from the start, and the original keeps its own row:
         * two rows, both decided, so neither is ever offered again.
         *
         * **Deleting hands the photo to Android's bin.** Copying it into the
         * app's own bin would leave the picture on the phone twice, and the
         * original is the half taking the room — which for this archive is
         * most of the room. Android's bin is the only place it can go. It is
         * a different bin, named as one every time: not the app's, empties
         * itself after thirty days, recovered from the gallery. The user
         * agrees each time, and Android asks as well.
         *
         * **The original of a copy is offered to the same bin.** Same
         * reasoning, same consent, and the copy in the category stays. A
         * photo already filed can still be deleted afterwards: it becomes a
         * deletion like any other and follows the rule above. Its copy in
         * the category is a separate photograph and is not touched.
         *
         * **Nothing is ever destroyed by this app.** Not here and not
         * anywhere: the strongest thing it does is ask Android to bin
         * something recoverable.
         *
         * **Every act has to be written down, because the path never
         * changes.** An immovable photo stays at the same address whatever
         * happens to it, so "where is it?" can never tell whether the work
         * was done. The copy is recorded as a destination reached, and the
         * handover to Android's bin is recorded as a location. Without those
         * records a check asks the path, is told the photo is still in the
         * wrong place, and copies it again — which is exactly how three
         * copies of one photograph came to sit in Pictures/Famiglia.
         *
         * **Dates need care.** WhatsApp strips EXIF, so a copy would be born
         * with no date at all and land in the gallery under the day it was
         * copied. The date is written into the file and onto the file before
         * the copy is published, which is the one moment the archive reads
         * it. What is known comes from the file name, which carries the day
         * but not the hour.
         *
         * **The name.** The copy is stamped like any filed photo; the
         * original keeps the name it has, since it is not ours to rename.
         */
        const val APP_MEDIA_ROOT = "Android/media/"

        /** True when the platform will refuse to move [relativePath]. */
        fun isImmovable(relativePath: String): Boolean =
            relativePath.trim('/').startsWith(APP_MEDIA_ROOT.trim('/'), ignoreCase = true)
    }

    /** Folders that directly hold photos, with their counts. */
    fun listFolders(): Result<List<FolderSummary>>

    /**
     * Photos in one folder, or every folder when null.
     *
     * Never recursive: a folder means the files inside it, not those in its
     * subfolders. That is also what stops a destination folder from being
     * picked up again by a scan of the folder containing it.
     */
    fun listPhotos(folder: FolderSummary?): Result<List<PhotoRecord>>

    /** How many photos sit directly in [relativePath], across volumes. */
    fun countPhotosIn(relativePath: String): Result<Int>

    /**
     * Files a photo into [destinationRelativePath] on its own volume, giving
     * it [newDisplayName] when one is supplied.
     *
     * Folder and name change together in a single write. Doing them apart
     * would leave a photo that has been moved but not yet named, and a run
     * interrupted at that point could not be told from a finished one.
     *
     * On Android this is a metadata-only rename and costs about 35 ms;
     * crossing volumes is deliberately not offered, since it would mean
     * copying every byte and would give the photo a new platform id.
     */
    fun move(
        photo: PhotoRecord,
        destinationRelativePath: String,
        newDisplayName: String? = null
    ): Result<Unit>

    /**
     * Copies a photo into [destinationRelativePath], returning the new
     * photo's platform id.
     *
     * The way in where [move] is refused. A photo cannot be taken out of
     * another app's folder, but it can be read, and a new file can be
     * written wherever the app is allowed to write: the boundary is never
     * crossed, it is simply not approached.
     *
     * Every byte is copied, which a move never does, so this costs real time
     * and real space. It is for the photos the platform will not move, not
     * for the ones it will.
     *
     * The original is left alone: deleting it is a separate act, and one the
     * user has to agree to.
     */
    fun copyInto(
        photo: PhotoRecord,
        destinationRelativePath: String,
        newDisplayName: String
    ): Result<CopyResult>

    /**
     * What a copy produced: the new photo's platform id, and whether it
     * ended up carrying its capture date.
     *
     * The date is reported rather than assumed because it can fail to stick
     * without the copy failing. A photograph whose date could not be written
     * is still a good copy; it is simply one the user needs to know about,
     * since it will sit in the gallery under the wrong day.
     */
    data class CopyResult(val mediaId: Long, val captureDateWritten: Boolean)

    /**
     * A fingerprint of a photo's own bytes.
     *
     * Every other way of recognising a photo describes it from outside — its
     * name, its size, its date — and all of those can change while the
     * photograph stays the same. This cannot.
     *
     * Only the head of the file is read, together with its length: two
     * different photographs sharing both would be a coincidence nobody has
     * met, and reading gigabytes to rule it out would cost more than it is
     * worth.
     */
    fun contentHash(photo: PhotoRecord): Result<String>

    /**
     * Fingerprint of the picture alone, or null when the file carries no
     * picture this can read.
     *
     * The header is skipped, so two files holding one photograph match even
     * when only one of them carries a capture date. That is the ordinary
     * case here: filing a photo the platform will not let us move copies it
     * and writes the date into the copy, leaving two files whose headers
     * differ and whose pictures are identical to the byte.
     *
     * Null rather than a failure for a file with no readable picture — a
     * PNG, a video: not knowing is a legitimate answer, and [contentHash]
     * still speaks for those.
     */
    fun imageHash(photo: PhotoRecord): Result<String?>
}
