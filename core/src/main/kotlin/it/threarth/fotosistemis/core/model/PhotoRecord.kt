package it.threarth.fotosistemis.core.model

/**
 * One photo as the app knows it, independent of where it was read from.
 *
 * Android fills this from MediaStore, a desktop build would fill it from the
 * file system. Nothing here is specific to either.
 */
data class PhotoRecord(

    /**
     * Our own identity for this photo, or zero before the inventory has
     * assigned one.
     *
     * A platform source cannot fill this in: it is resolved by reconciling
     * what the platform reports against what the inventory already holds.
     */
    val photoId: Long = 0,

    /**
     * Identifier assigned by the platform: the MediaStore _ID on Android.
     *
     * Convenient but not an identity. The platform may reassign it when a
     * card is remounted or the media index is rebuilt, so it is treated as a
     * shortcut that can be rewritten, never as the way a photo is known.
     */
    val platformId: Long,

    /** Storage volume holding the file. Photos never leave their own. */
    val volumeName: String,

    val displayName: String,

    /** Folder holding the file, relative to the volume root, trailing slash. */
    val relativePath: String,

    val sizeBytes: Long,

    /** Best available capture time, resolved by [CaptureDateResolver]. */
    val dateTakenMillis: Long,

    val dateSource: CaptureDateResolver.Source,

    /**
     * Fingerprint of the file's own bytes, when one has been taken.
     *
     * Carried on the record so a scan can hand it to the matcher; filled
     * only for photos a decision has been made about.
     */
    val contentHash: String? = null,

    /**
     * Fingerprint of the picture with its metadata left out, or null when
     * the file is not a JPEG or has not been read yet.
     *
     * Answers "is this the same photograph?" where [contentHash] answers
     * "is this the same file?". Two copies of one picture whose capture
     * date was written into only one of them share this and nothing else.
     */
    val imageHash: String? = null
)
