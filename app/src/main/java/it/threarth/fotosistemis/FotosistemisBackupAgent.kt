package it.threarth.fotosistemis

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.ParcelFileDescriptor

/**
 * Decides, at the moment Android asks, whether this app hands over its data.
 *
 * The manifest flag that allows backup cannot be changed while the app runs,
 * so it stays on and the choice is made here instead: the agent reads the
 * user's setting each time a backup is attempted, and gives nothing when the
 * answer is no.
 *
 * Only the database and the preferences are ever involved. Photographs are
 * not the app's own files and never enter this: what would travel is the
 * record of where they are and what has been decided about them.
 */
class FotosistemisBackupAgent : BackupAgent() {

    /**
     * Hands the app's files to Android, or nothing at all.
     *
     * Returning without calling through is what withholds the data: the
     * platform takes whatever the agent writes, and an agent that writes
     * nothing produces an empty backup rather than a failure.
     */
    override fun onFullBackup(data: FullBackupDataOutput?) {
        if (!AppSettings(this).backupToCloud) return

        super.onFullBackup(data)
    }

    /**
     * The key/value backup API, unused.
     *
     * Superseded by full backup on every version this app runs on, but
     * declared abstract on [BackupAgent], so it has to be answered. Doing
     * nothing is the honest answer: there is no key/value state to save.
     */
    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) = Unit

    /** The key/value restore API, unused for the same reason. */
    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) = Unit
}
