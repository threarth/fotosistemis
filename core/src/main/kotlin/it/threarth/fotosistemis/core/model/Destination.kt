package it.threarth.fotosistemis.core.model

import java.util.Calendar
import java.util.Locale

/**
 * A folder photos can be filed into.
 *
 * The path is arbitrary and need not sit under the folder being reviewed.
 */
data class Destination(
    val id: Long,
    val label: String,
    val relativePath: String,
    val yearSubfolder: Boolean,
    val sortOrder: Int
) {

    /**
     * Full relative path for a photo taken at [captureMillis].
     *
     * [yearFolderPattern] is the application-wide setting, so every
     * destination names its year folders the same way.
     */
    fun pathFor(captureMillis: Long, yearFolderPattern: String): String {
        val trimmed = relativePath.trim('/')
        if (!yearSubfolder) return "$trimmed/"
        val year = Calendar.getInstance().apply { timeInMillis = captureMillis }
            .get(Calendar.YEAR)
        return "$trimmed/${yearFolderName(year, yearFolderPattern)}/"
    }

    /**
     * Resolves the pattern for [year]. Google Photos names a device folder
     * after its last path segment, so including the label keeps the 2026 of
     * one destination distinguishable from another.
     */
    fun yearFolderName(year: Int, yearFolderPattern: String): String {
        val resolved = yearFolderPattern
            .replace(PLACEHOLDER_YEAR, year.toString())
            .replace(PLACEHOLDER_LABEL, sanitise(label))
        return sanitise(resolved).ifEmpty { year.toString() }
    }

    /** Strips anything that does not belong in a folder name. */
    private fun sanitise(value: String): String = value.trim().lowercase(Locale.ITALY)
        .replace(Regex("[^a-z0-9._{}-]+"), "_")
        .trim('_', '.', '-')

    companion object {
        const val PLACEHOLDER_YEAR = "{anno}"
        const val PLACEHOLDER_LABEL = "{etichetta}"
        const val DEFAULT_YEAR_FOLDER_PATTERN = "$PLACEHOLDER_YEAR-$PLACEHOLDER_LABEL"
    }
}
