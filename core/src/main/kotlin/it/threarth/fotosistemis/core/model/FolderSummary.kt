package it.threarth.fotosistemis.core.model

/** A folder on one volume that currently holds photos, and how many. */
data class FolderSummary(
    val volumeName: String,
    val relativePath: String,
    val photoCount: Int
) {

    /**
     * True when the folder lives on removable storage.
     *
     * Worth telling apart because a photo is always filed on the volume it
     * already occupies: moving between volumes cannot be a rename, so it
     * would mean copying every byte.
     */
    val isRemovable: Boolean get() = volumeName != PRIMARY_VOLUME

    /**
     * Identity without the photo count, which changes as photos move.
     * Comparing whole objects would make a folder look different from
     * itself after every batch.
     */
    fun sameAs(other: FolderSummary?): Boolean =
        other != null && other.volumeName == volumeName &&
                FolderPath.sameFolder(other.relativePath, relativePath)

    companion object {

        /** Name the platform gives to built-in shared storage. */
        const val PRIMARY_VOLUME = "external_primary"
    }
}
