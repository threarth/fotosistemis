package it.threarth.fotosistemis

import android.content.Context
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
}
