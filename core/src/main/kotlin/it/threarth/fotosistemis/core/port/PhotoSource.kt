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
}
