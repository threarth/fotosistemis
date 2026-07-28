package it.threarth.fotosistemis

import java.util.Calendar

/**
 * Which photos to load into a review session.
 *
 * Split in two independent axes, because they are answered by different
 * stores: the time range is resolved by MediaStore in SQL, while the review
 * axis is resolved against the local decisions table. Combining them into one
 * enum would force every new period to be crossed with every new status.
 */
data class PhotoFilter(
    val period: Period = Period.Any,
    val reviewScope: ReviewScope = ReviewScope.ALL
) {

    /** Time window, expressed as a closed range over DATE_TAKEN. */
    sealed interface Period {

        /** No restriction. */
        data object Any : Period

        /** A whole calendar month, as "mm-yyyy". */
        data class Month(val month: Int, val year: Int) : Period

        /** An explicit range; both ends inclusive, as whole days. */
        data class Range(val fromMillis: Long, val toMillis: Long) : Period
    }

    /** Which review states are of interest. */
    enum class ReviewScope {

        /** Everything in the period, reviewed or not. */
        ALL,

        /** Only photos with no decision recorded yet. */
        UNSEEN,

        /** Everything except photos that already carry a tag. */
        UNCATEGORIZED
    }

    /**
     * Resolves [period] into an inclusive millisecond range, or null when the
     * period places no restriction.
     */
    fun resolvePeriodMillis(): LongRange? = when (period) {
        is Period.Any -> null
        is Period.Month -> monthRange(period.month, period.year)
        is Period.Range -> period.fromMillis..endOfDay(period.toMillis)
    }

    /** True when [status] passes the review axis of this filter. */
    fun accepts(status: ReviewStatus?): Boolean = when (reviewScope) {
        ReviewScope.ALL -> true
        ReviewScope.UNSEEN -> status == null
        ReviewScope.UNCATEGORIZED -> status != ReviewStatus.CATEGORIZED
    }

    private companion object {

        /** First millisecond of [month] in [year] to the last of that month. */
        fun monthRange(month: Int, year: Int): LongRange {
            val start = Calendar.getInstance().apply {
                clear()
                set(year, month - 1, 1, 0, 0, 0)
            }
            val end = (start.clone() as Calendar).apply {
                add(Calendar.MONTH, 1)
                add(Calendar.MILLISECOND, -1)
            }
            return start.timeInMillis..end.timeInMillis
        }

        /**
         * Pushes a timestamp to 23:59:59.999 of its own day, so that a range
         * ending on a given date includes photos taken during that date.
         */
        fun endOfDay(millis: Long): Long = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
    }
}
