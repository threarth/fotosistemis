package it.threarth.fotosistemis.core.model

import java.util.Calendar
import java.util.Locale

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

    /**
     * Where a capture time came from, worst case last.
     *
     * Declaration order is the order of trust and [outranks] depends on it:
     * a date already known must never be replaced by one from a weaker
     * source.
     */
    enum class Source {

        /** EXIF, through MediaStore DATE_TAKEN. Trustworthy. */
        EXIF,

        /** Parsed out of the file name. Usually right. */
        FILENAME,

        /**
         * Our own stamp, written with the mark that says it is a guess.
         *
         * The value is no better than the [FILE_TIMESTAMP] it came from, but
         * it has stopped drifting: it was judged once and written into the
         * name, so it must be shown for review rather than recomputed.
         */
        ESTIMATED,

        /** File modification time. Describes the file, not the photo. */
        FILE_TIMESTAMP;

        /** True when this source deserves more trust than [other]. */
        fun outranks(other: Source): Boolean = ordinal < other.ordinal
    }

    data class Resolved(val millis: Long, val source: Source)

    /** Years outside this range are treated as parsing accidents. */
    private const val MIN_YEAR = 1990
    private const val MAX_YEAR = 2100

    /** 1990-01-01 in milliseconds: no digital photo predates it. */
    private const val MIN_PLAUSIBLE_MILLIS = 631_152_000_000L

    private const val MILLIS_PER_SECOND = 1000L

    /**
     * Opens and closes our own stamp.
     *
     * Doubled, because a single underscore is what the cameras themselves
     * use: a name like 20200805_113940_01_saved.jpg would otherwise read as
     * one of ours, and removing the stamp we thought we had written would
     * leave 01_saved.jpg. Nothing on a phone writes two in a row.
     */
    private const val STAMP_DELIMITER = "__"

    /**
     * Marks a stamp whose date is a fallback rather than a capture time.
     *
     * Sorts before the digits, so the photos whose date nobody can vouch for
     * gather at the top of the folder where they can be dealt with.
     */
    private const val UNCERTAIN_MARK = "+"

    /** Separates the stamp from its collision counter. */
    private const val COUNTER_SEPARATOR = "-"

    /**
     * Year, month, day, then time, joined the way the cameras join them.
     *
     * The order sorts chronologically because comparison runs from the left.
     * The separator matches the native one on purpose: with a different one
     * our names and theirs would part company at that character, and every
     * stamped photo of a day would sort before every unstamped one instead
     * of taking its place among them.
     */
    private const val STAMP_FORMAT = "%04d%02d%02d_%02d%02d%02d"

    /** Our own stamp: delimiter, optional mark, date, time, counter, delimiter. */
    private val STAMP_PATTERN = Regex(
        """^__(\+?)(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?:-(\d+))?__"""
    )

    /** Our own stamp, read back out of a file name. */
    data class Stamp(val millis: Long, val source: Source, val counter: Int?)

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

        // Our own stamp is read before any other pattern in the name: it is
        // the only one that also says how much the date is worth, and the
        // generic patterns would match it while losing that.
        val stamp = readStamp(displayName)
        if (stamp != null) return Resolved(stamp.millis, stamp.source)

        val fromName = parseFileName(displayName)
        if (fromName != null) return Resolved(fromName, Source.FILENAME)
        return Resolved(fileMillis, Source.FILE_TIMESTAMP)
    }

    /**
     * Reads our own stamp from [displayName], or null when there is none.
     *
     * Digits that are not a real date mean the stamp is not ours after all,
     * so it is left alone and the name is read like any other.
     */
    fun readStamp(displayName: String): Stamp? {
        val match = STAMP_PATTERN.find(displayName) ?: return null
        val (mark, year, month, day, hour, minute, second, counter) = match.destructured
        val millis = buildTimestamp(
            year.toInt(), month.toInt(), day.toInt(),
            hour.toInt(), minute.toInt(), second.toInt()
        ) ?: return null

        return Stamp(
            millis = millis,
            source = if (mark == UNCERTAIN_MARK) Source.ESTIMATED else Source.FILENAME,
            counter = counter.toIntOrNull()
        )
    }

    /** [displayName] without our stamp, unchanged when it carries none. */
    fun stripStamp(displayName: String): String =
        if (readStamp(displayName) == null) displayName
        else STAMP_PATTERN.replaceFirst(displayName, "")

    /**
     * Builds the stamp for [millis].
     *
     * [uncertain] writes the mark that keeps a fallback date visible.
     * [counter] separates photos that share a second, which bursts and file
     * timestamps produce in quantity.
     */
    fun formatStamp(millis: Long, uncertain: Boolean, counter: Int?): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        val stamp = String.format(
            Locale.ROOT,
            STAMP_FORMAT,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            calendar.get(Calendar.SECOND)
        )
        val mark = if (uncertain) UNCERTAIN_MARK else ""
        val tail = if (counter == null) "" else COUNTER_SEPARATOR + counter

        return STAMP_DELIMITER + mark + stamp + tail + STAMP_DELIMITER
    }

    /**
     * Accepts DATE_TAKEN only when it can be a real capture time.
     *
     * Public because reading the column back has to go through the same
     * correction: a device that stores seconds would otherwise report a value
     * that never equals the one already recorded.
     *
     * The column is documented as milliseconds, but some devices fill it
     * with seconds. Read as milliseconds such a value lands in 1970, which
     * silently pushes every photo outside any period filter instead of
     * failing visibly. A value too small to be a plausible date in
     * milliseconds, but plausible once multiplied, is treated as seconds.
     */
    fun normaliseExif(exifMillis: Long?): Long? {
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
