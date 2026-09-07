package it.threarth.fotosistemis.core.model

/**
 * What makes two paths the same folder.
 *
 * Android serves its shared storage through a layer that ignores ASCII
 * case: Pictures/Famiglia and pictures/Famiglia are one directory on disk,
 * with one inode, and MediaStore has been seen indexing the same directory
 * under both spellings within a day. SQLite's LIKE, already used across the
 * queries, folds case the same way, and the path columns carry the
 * matching NOCASE collation.
 *
 * New: this is the single rule every comparison of folders in Kotlin goes
 * through. Paths used to be compared as plain strings, and one directory
 * showed as two folders in the source tree.
 */
object FolderPath {

    /** The form two paths are compared in: no surrounding separators, no case. */
    fun key(path: String): String = path.trim('/').lowercase()

    /** True when [first] and [second] name the same folder. */
    fun sameFolder(first: String, second: String): Boolean = key(first) == key(second)
}
