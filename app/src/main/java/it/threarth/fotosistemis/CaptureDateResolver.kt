package it.threarth.fotosistemis

import java.util.Calendar

/**
 * Works out when a photo was actually taken.
 *
 * MediaStore's DATE_TAKEN is read from EXIF and is the only trustworthy
 * source, but it is missing on screenshots, downloads, images saved from
 * chat apps, and anything an editor has rewritten.
 *
 * When it is missing, the file name is a far better guess than the file
 * timestamp: DATE_MODIFIED describes the file, not the photograph, and any
 * copy resets it to the moment of copying. A 2020 photo copied today would
 * otherwise be filed under 2026.
 */
object CaptureDateResolver {

    /** Where a capture time came from, worst case last. */
    enum class Source {

        /** EXIF, through MediaStore DATE_TAKEN. Trustworthy. */
        EXIF,

        /** Parsed out of the file name. Usually right. */
        FILENAME,

        /** File modification time. Describes the file, not the photo. */
        FILE_TIMESTAMP
    }

    data class Resolved(val millis: Long, val source: Source)

    /** Years outside this range are treated as parsing accidents. */
    private const val MIN_YEAR = 1990
    private const val MAX_YEAR = 2100

    /** 1990-01-01 in milliseconds: no digital photo predates it. */
    private const val MIN_PLAUSIBLE_MILLIS = 631_152_000_000L

    private const val MILLIS_PER_SECOND = 1000L

    /**
     * Date and time together, e.g. IMG_20260728_153045.jpg,
     * PXL_20260728_153045123.jpg, Screenshot_20260728-153045.png.
     */
    private val DATE_TIME_PATTERNS = listOf(
        Regex("""(?:^|[^0-9])(20\d{2})(\d{2})(\d{2})[T_\- .]?(\d{2})(\d{2})(\d{2})"""),
        Regex("""(?:^|[^0-9])(20\d{2})[-_.](\d{2})[-_.](\d{2})[T_\- .](\d{2})[-_.:](\d{2})[-_.:](\d{2})""")
    )

    /** Date only, e.g. IMG-20260728-WA0001.jpg. */
    private val DATE_PATTERNS = listOf(
        Regex("""(?:^|[^0-9])(20\d{2})(\d{2})(\d{2})(?:[^0-9]|$)"""),
        Regex("""(?:^|[^0-9])(20\d{2})[-_.](\d{2})[-_.](\d{2})(?:[^0-9]|$)""")
    )

    /**
     * Picks the best available capture time for a photo.
     *
     * [exifMillis] should be null or zero when MediaStore has no DATE_TAKEN.
     */
    fun resolve(displayName: String, exifMillis: Long?, fileMillis: Long): Resolved {
        val exif = normaliseExif(exifMillis)
        if (exif != null) return Resolved(exif, Source.EXIF)
        val fromName = parseFileName(displayName)
        if (fromName != null) return Resolved(fromName, Source.FILENAME)
        return Resolved(fileMillis, Source.FILE_TIMESTAMP)
    }

    /**
     * Accepts DATE_TAKEN only when it can be a real capture time.
     *
     * The column is documented as milliseconds, but some devices fill it
     * with seconds. Read as milliseconds such a value lands in 1970, which
     * silently pushes every photo outside any period filter instead of
     * failing visibly. A value too small to be a plausible date in
     * milliseconds, but plausible once multiplied, is treated as seconds.
     */
    private fun normaliseExif(exifMillis: Long?): Long? {
        if (exifMillis == null || exifMillis <= 0L) return null
        if (exifMillis >= MIN_PLAUSIBLE_MILLIS) return exifMillis
        val asMillis = exifMillis * MILLIS_PER_SECOND
        return if (asMillis >= MIN_PLAUSIBLE_MILLIS) asMillis else null
    }

    /** Extracts a timestamp from [displayName], or null when none is found. */
    fun parseFileName(displayName: String): Long? {
        val stem = displayName.substringBeforeLast('.', displayName)

        for (pattern in DATE_TIME_PATTERNS) {
            val match = pattern.find(stem) ?: continue
            val (year, month, day, hour, minute, second) = match.destructured
            val millis = buildTimestamp(
                year.toInt(), month.toInt(), day.toInt(),
                hour.toInt(), minute.toInt(), second.toInt()
            )
            if (millis != null) return millis
        }

        for (pattern in DATE_PATTERNS) {
            val match = pattern.find(stem) ?: continue
            val values = match.groupValues
            val millis = buildTimestamp(
                values[1].toInt(), values[2].toInt(), values[3].toInt(), 0, 0, 0
            )
            if (millis != null) return millis
        }
        return null
    }

    /**
     * Builds a timestamp, rejecting values that are out of range or that the
     * calendar silently rolls over, such as the 31st of February.
     */
    private fun buildTimestamp(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long? {
        if (year < MIN_YEAR || year > MAX_YEAR) return null
        if (month !in 1..12 || day !in 1..31) return null
        if (hour > 23 || minute > 59 || second > 59) return null

        val calendar = Calendar.getInstance().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }
        return try {
            calendar.timeInMillis
        } catch (error: IllegalArgumentException) {
            null
        }
    }
}
