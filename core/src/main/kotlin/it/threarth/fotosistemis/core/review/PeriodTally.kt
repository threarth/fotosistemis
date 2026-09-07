package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import java.util.Calendar

/**
 * How much of each month has been dealt with, in the chosen folders.
 *
 * New feature. Counted from two lists: the photos present in the folders,
 * and the decided photos that came from those folders and have since
 * left — filed into a category, handed to a bin. Without the second list a
 * month emptied by its own completion disappeared, and what should have
 * read as finished read as never there. A departed photo counts under the
 * month it was taken, as decided, unless the session has taken the
 * decision back; one that is also present — moved within the folders —
 * is counted once, as present.
 */
class PeriodTally private constructor(
    private val byMonth: Map<PhotoFilter.Period.Month, Count>,
    private val stamps: List<Stamp>
) {

    /** How many of a period's photos have been decided, out of how many. */
    data class Count(val seen: Int, val total: Int) {

        /** Both numbers added, for building a period out of smaller ones. */
        operator fun plus(other: Count): Count = Count(seen + other.seen, total + other.total)
    }

    /** One photo reduced to what the tally needs of it. */
    private data class Stamp(val takenMillis: Long, val decided: Boolean)

    /** The months that hold anything, newest first. */
    val months: List<PhotoFilter.Period.Month>
        get() = byMonth.keys.sortedWith(
            compareByDescending<PhotoFilter.Period.Month> { it.year }.thenByDescending { it.month }
        )

    /** The tally of one month; empty for a month that holds nothing. */
    fun of(month: PhotoFilter.Period.Month): Count = byMonth[month] ?: NOTHING

    /** Every month added together. */
    val everywhere: Count get() = byMonth.values.fold(NOTHING) { sum, count -> sum + count }

    /** The tally of the photos taken between the two instants, inclusive. */
    fun between(fromMillis: Long, toMillis: Long): Count =
        stamps.filter { it.takenMillis in fromMillis..toMillis }.fold(NOTHING) { sum, stamp ->
            sum + Count(if (stamp.decided) 1 else 0, 1)
        }

    companion object {

        private val NOTHING = Count(0, 0)

        /**
         * Counts [present] and [departed] against [decided], the decisions
         * as the session currently knows them.
         */
        fun of(
            present: List<PhotoRecord>,
            departed: List<PhotoInventory.Departed>,
            decided: Map<Long, ReviewStatus>
        ): PeriodTally {
            val presentIds = present.mapTo(HashSet()) { it.photoId }
            val stamps = present.map { Stamp(it.dateTakenMillis, it.photoId in decided) } +
                    departed.filter { it.photoId !in presentIds }
                        .map { Stamp(it.dateTakenMillis, it.photoId in decided) }

            val byMonth = LinkedHashMap<PhotoFilter.Period.Month, Count>()
            for (stamp in stamps) {
                val month = monthOf(stamp.takenMillis)
                byMonth[month] = (byMonth[month] ?: NOTHING) +
                        Count(if (stamp.decided) 1 else 0, 1)
            }
            return PeriodTally(byMonth, stamps)
        }

        /** The calendar month an instant falls in, in the device's time zone. */
        fun monthOf(millis: Long): PhotoFilter.Period.Month {
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = millis
            return PhotoFilter.Period.Month(
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.YEAR)
            )
        }
    }
}
