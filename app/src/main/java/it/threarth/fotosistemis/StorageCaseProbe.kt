package it.threarth.fotosistemis

import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/**
 * Safety check behind the case-blind path comparisons.
 *
 * New feature. The app treats `Pictures` and `pictures` as one folder
 * because shared storage is a FUSE mount that resolves both spellings to
 * the same directory — verified on the phone: same inode. That is a
 * property of the mount, not of Android in general, and a future release
 * could change it. Rather than trust the assumption silently, the probe
 * asks the filesystem once at startup: it stats the standard pictures
 * folder under both spellings and compares the inodes. Two inodes, or a
 * spelling the filesystem refuses, would mean the storage distinguishes
 * case and the app's comparisons are looser than the disk — worth a
 * warning, but not a behaviour switch, because the comparisons are still
 * the safer reading: merging two spellings can only hide a move that was
 * a no-op, never move a file.
 */
class StorageCaseProbe(private val settings: AppSettings) {

    /** What the filesystem answered. */
    enum class Verdict { SAME_FOLDER, DISTINCT_FOLDERS, UNKNOWN }

    /**
     * Stats the pictures folder under both spellings.
     *
     * [UNKNOWN] when either stat fails for a reason other than the file
     * not existing — permissions, an unmounted volume — since that says
     * nothing about case. A missing lowercase spelling next to an
     * existing capitalised one is the clearest sign of a case-sensitive
     * filesystem, so it counts as [DISTINCT_FOLDERS].
     */
    fun probe(): Verdict {
        val root = Environment.getExternalStorageDirectory()
        val spelled = File(root, Environment.DIRECTORY_PICTURES)
        val lowered = File(root, Environment.DIRECTORY_PICTURES.lowercase())
        val spelledInode = inodeOf(spelled) ?: return Verdict.UNKNOWN
        if (spelledInode == MISSING) return Verdict.UNKNOWN
        val loweredInode = inodeOf(lowered) ?: return Verdict.UNKNOWN
        return if (loweredInode == spelledInode) Verdict.SAME_FOLDER else Verdict.DISTINCT_FOLDERS
    }

    /**
     * True the first time the filesystem turns out to distinguish case.
     * The warning is remembered so it does not greet the user on every
     * launch; the log line repeats, since it costs nothing.
     */
    fun shouldWarn(): Boolean {
        val verdict = probe()
        Log.i(LOG_TAG, "storage case probe: $verdict")
        if (verdict != Verdict.DISTINCT_FOLDERS || settings.caseWarningShown) return false
        settings.caseWarningShown = true
        return true
    }

    /** The inode of [file], [MISSING] when it does not exist, null on any other failure. */
    private fun inodeOf(file: File): Long? = try {
        Os.stat(file.path).st_ino
    } catch (error: ErrnoException) {
        if (error.errno != OsConstants.ENOENT) {
            Log.w(LOG_TAG, "stat non riuscito: ${error.message}")
        }
        if (error.errno == OsConstants.ENOENT) MISSING else null
    }

    private companion object {
        const val LOG_TAG = "Fotosistemis"

        /** Stands in for the inode of a path that does not exist. */
        private const val MISSING = -1L
    }
}
