package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks that a month is counted from the photos still there and the
 * photos that were there, so finishing it leaves it green rather than gone.
 */
class PeriodTallyTest {

    private companion object {
        val JULY_2018 = PhotoFilter.Period.Month(7, 2018)
        val AUGUST_2018 = PhotoFilter.Period.Month(8, 2018)
    }

    private fun millisAt(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis

    private fun photo(id: Long, year: Int, month: Int, day: Int = 10) = PhotoRecord(
        photoId = id, platformId = id, volumeName = "external_primary",
        displayName = "IMG_$id.jpg", relativePath = "WhatsApp Images/", sizeBytes = 10,
        dateTakenMillis = millisAt(year, month, day),
        dateSource = CaptureDateResolver.Source.EXIF
    )

    private fun departed(id: Long, year: Int, month: Int, day: Int = 10) =
        PhotoInventory.Departed(id, "WhatsApp Images/", millisAt(year, month, day))

    private fun decided(vararg ids: Long): Map<Long, ReviewStatus> =
        ids.associateWith { ReviewStatus.CATEGORIZED }

    @Test
    fun `an emptied month is whole and finished`() {
        val tally = PeriodTally.of(
            present = emptyList(),
            departed = listOf(departed(1, 2018, 7), departed(2, 2018, 7)),
            decided = decided(1, 2)
        )

        assertEquals(listOf(JULY_2018), tally.months)
        assertEquals(PeriodTally.Count(2, 2), tally.of(JULY_2018))
        assertEquals(PeriodTally.Count(2, 2), tally.everywhere)
    }

    @Test
    fun `present and departed add up, newest month first`() {
        val tally = PeriodTally.of(
            present = listOf(photo(1, 2018, 7), photo(2, 2018, 8)),
            departed = listOf(departed(3, 2018, 7)),
            decided = decided(3)
        )

        assertEquals(listOf(AUGUST_2018, JULY_2018), tally.months)
        assertEquals(PeriodTally.Count(1, 2), tally.of(JULY_2018))
        assertEquals(PeriodTally.Count(0, 1), tally.of(AUGUST_2018))
    }

    @Test
    fun `a photo moved within the folders is counted once`() {
        val tally = PeriodTally.of(
            present = listOf(photo(1, 2018, 7)),
            departed = listOf(departed(1, 2018, 7)),
            decided = decided(1)
        )

        assertEquals(PeriodTally.Count(1, 1), tally.of(JULY_2018))
    }

    @Test
    fun `a decision taken back undoes the credit`() {
        val tally = PeriodTally.of(
            present = emptyList(),
            departed = listOf(departed(1, 2018, 7)),
            decided = emptyMap()
        )

        assertEquals(PeriodTally.Count(0, 1), tally.of(JULY_2018))
    }

    @Test
    fun `a range counts what falls inside it, departed included`() {
        val tally = PeriodTally.of(
            present = listOf(photo(1, 2018, 7, day = 3), photo(2, 2018, 7, day = 25)),
            departed = listOf(departed(3, 2018, 7, day = 20)),
            decided = decided(3)
        )

        val count = tally.between(millisAt(2018, 7, 15), millisAt(2018, 7, 31))
        assertEquals(PeriodTally.Count(1, 2), count)
    }
}
