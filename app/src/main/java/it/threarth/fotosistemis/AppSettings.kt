package it.threarth.fotosistemis

import android.content.Context
import it.threarth.fotosistemis.core.data.ClassificationAdopter
import it.threarth.fotosistemis.core.model.Destination

/**
 * Application-wide preferences.
 *
 * Kept in SharedPreferences rather than in the database: these are single
 * scalar choices, not records, and nothing else refers to them.
 */
class AppSettings(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES_NAME = "fotosistemis-settings"
        private const val KEY_YEAR_FOLDER_PATTERN = "year_folder_pattern"
        private const val KEY_SOURCE_ROOTS = "source_roots"
        private const val KEY_WHOLE_DEVICE = "whole_device_as_source"
        private const val KEY_DESTINATION_ROOT = "destination_root"
        private const val KEY_BACKUP_TO_CLOUD = "backup_to_cloud"

        /** One root per line, which is also how the user edits them. */
        private const val ROOT_SEPARATOR = "\n"

        /** Where photos to sort usually live, before the user says otherwise. */
        val DEFAULT_SOURCE_ROOTS = ClassificationAdopter.DEFAULT_ROOTS

        /** Where sorted photos usually go. */
        const val DEFAULT_DESTINATION_ROOT = "Pictures"


        /**
         * Google Photos labels a device folder with its last path segment, so
         * a bare year would look identical for every destination. Including
         * the label keeps Famiglia and Lavoro apart there.
         */
        const val DEFAULT_YEAR_FOLDER_PATTERN = Destination.DEFAULT_YEAR_FOLDER_PATTERN
    }

    /**
     * Name given to the year subfolder, shared by every destination.
     * Placeholders are [PLACEHOLDER_YEAR] and [PLACEHOLDER_LABEL].
     */
    var yearFolderPattern: String
        get() = preferences.getString(KEY_YEAR_FOLDER_PATTERN, DEFAULT_YEAR_FOLDER_PATTERN)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_YEAR_FOLDER_PATTERN
        set(value) {
            val cleaned = value.trim().ifEmpty { DEFAULT_YEAR_FOLDER_PATTERN }
            preferences.edit().putString(KEY_YEAR_FOLDER_PATTERN, cleaned).apply()
        }

    /**
     * Folders the app looks in for photos to sort.
     *
     * Naming them is what keeps the work bounded: a phone holds photos in
     * dozens of folders, most of which are nobody's archive. A root may be
     * several segments deep, since a transfer from another phone can bury an
     * archive under a folder of its own.
     */
    var sourceRoots: List<String>
        get() = preferences.getString(KEY_SOURCE_ROOTS, null)
            ?.split(ROOT_SEPARATOR)
            ?.map { it.trim().trim('/') }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_SOURCE_ROOTS
        set(value) {
            val cleaned = value.map { it.trim().trim('/') }.filter { it.isNotEmpty() }
            preferences.edit()
                .putString(KEY_SOURCE_ROOTS, cleaned.joinToString(ROOT_SEPARATOR))
                .apply()
        }

    /**
     * Ignore the roots and offer every photo on the device.
     *
     * Kept apart from an empty root list so that turning it off restores the
     * roots the user had chosen instead of losing them.
     */
    var wholeDeviceAsSource: Boolean
        get() = preferences.getBoolean(KEY_WHOLE_DEVICE, false)
        set(value) = preferences.edit().putBoolean(KEY_WHOLE_DEVICE, value).apply()

    /**
     * The folder every category sits under.
     *
     * One and only one, unlike the source roots: photos come from wherever
     * they happen to be, but they are put away in a single place.
     */
    var destinationRoot: String
        get() = preferences.getString(KEY_DESTINATION_ROOT, DEFAULT_DESTINATION_ROOT)
            ?.trim()?.trim('/')
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_DESTINATION_ROOT
        set(value) {
            val cleaned = value.trim().trim('/').ifEmpty { DEFAULT_DESTINATION_ROOT }
            preferences.edit().putString(KEY_DESTINATION_ROOT, cleaned).apply()
        }

    /**
     * Whether Android may include this app's data in the account backup.
     *
     * On by default: losing the record of what has already been reviewed is
     * losing the work itself, since the photos on disk say only where they
     * are and not what was decided about them. Read by
     * [FotosistemisBackupAgent] every time a backup is attempted.
     */
    var backupToCloud: Boolean
        get() = preferences.getBoolean(KEY_BACKUP_TO_CLOUD, true)
        set(value) = preferences.edit().putBoolean(KEY_BACKUP_TO_CLOUD, value).apply()

    /** The roots to search, honouring the whole-device choice. */
    fun effectiveSourceRoots(): List<String> =
        if (wholeDeviceAsSource) emptyList() else sourceRoots
}
