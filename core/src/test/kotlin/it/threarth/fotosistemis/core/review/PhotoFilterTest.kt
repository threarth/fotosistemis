package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.ReviewStatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Checks the date arithmetic behind the period filter.
 *
 * Runs on the JVM: Calendar and the parsing logic carry no Android
 * dependencies, so the filter can be verified without a device.
 */
class PhotoFilterTest {

    /** Builds a local timestamp the same way a photo's capture time is read. */
    private fun millisOf(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    @Test
    fun `month filter covers the whole month`() {
        val filter = PhotoFilter(PhotoFilter.Period.Month(7, 2026))
        val range = filter.resolvePeriodMillis()
        assertNotNull("Un filtro mensile deve produrre un intervallo", range)

        assertTrue("Primo istante di luglio", millisOf(2026, 7, 1, 0, 0) in range!!)
        assertTrue("Meta' mese", millisOf(2026, 7, 15) in range)
        assertTrue("Ultimo giorno", millisOf(2026, 7, 31, 23, 59) in range)
    }

    @Test
    fun `month filter excludes neighbouring months`() {
        val range = PhotoFilter(PhotoFilter.Period.Month(7, 2026)).resolvePeriodMillis()!!
        assertTrue("Giugno escluso", millisOf(2026, 6, 30, 23, 59) !in range)
        assertTrue("Agosto escluso", millisOf(2026, 8, 1, 0, 0) !in range)
    }

    @Test
    fun `any period places no restriction`() {
        assertNull(PhotoFilter(PhotoFilter.Period.Any).resolvePeriodMillis())
    }

    @Test
    fun `explicit range includes the whole final day`() {
        val filter = PhotoFilter(
            PhotoFilter.Period.Range(millisOf(2026, 7, 1, 0, 0), millisOf(2026, 7, 10, 0, 0))
        )
        val range = filter.resolvePeriodMillis()!!
        assertTrue("Sera dell'ultimo giorno", millisOf(2026, 7, 10, 23, 30) in range)
        assertTrue("Giorno successivo escluso", millisOf(2026, 7, 11, 0, 30) !in range)
    }

    @Test
    fun `review scope filters on stored status`() {
        val unseenOnly = PhotoFilter(reviewScope = PhotoFilter.ReviewScope.UNSEEN)
        assertTrue(unseenOnly.accepts(null))
        assertTrue(!unseenOnly.accepts(ReviewStatus.KEPT))

        val uncategorized = PhotoFilter(reviewScope = PhotoFilter.ReviewScope.UNCATEGORIZED)
        assertTrue(uncategorized.accepts(null))
        assertTrue(uncategorized.accepts(ReviewStatus.KEPT))
        assertTrue(!uncategorized.accepts(ReviewStatus.CATEGORIZED))
    }

    @Test
    fun `capture date prefers exif then file name then file timestamp`() {
        val exif = millisOf(2020, 8, 4)
        val fileTime = millisOf(2026, 7, 29)

        val fromExif = CaptureDateResolver.resolve("20200804_180537.jpg", exif, fileTime)
        assertEquals(CaptureDateResolver.Source.EXIF, fromExif.source)

        val fromName = CaptureDateResolver.resolve("20200804_180537.jpg", null, fileTime)
        assertEquals(CaptureDateResolver.Source.FILENAME, fromName.source)
        assertEquals(millisOf(2020, 8, 4, 18, 5).let { it - it % 60000 }, fromName.millis - 37000)

        val fromFile = CaptureDateResolver.resolve("senza_data.jpg", null, fileTime)
        assertEquals(CaptureDateResolver.Source.FILE_TIMESTAMP, fromFile.source)
    }

    @Test
    fun `exif in seconds is recognised and rescaled`() {
        val julyMillis = millisOf(2026, 7, 15)
        val julySeconds = julyMillis / 1000

        val fromSeconds = CaptureDateResolver.resolve("IMG_0001.jpg", julySeconds, 0L)
        assertEquals(CaptureDateResolver.Source.EXIF, fromSeconds.source)
        assertTrue(
            "Un valore in secondi deve essere riportato a millisecondi",
            fromSeconds.millis in PhotoFilter(PhotoFilter.Period.Month(7, 2026))
                .resolvePeriodMillis()!!
        )
    }

    @Test
    fun `implausible exif falls back to the file name`() {
        val resolved = CaptureDateResolver.resolve("20200804_180537.jpg", 42L, 0L)
        assertEquals(CaptureDateResolver.Source.FILENAME, resolved.source)
    }

    @Test
    fun `file name parsing rejects impossible dates`() {
        assertNull(CaptureDateResolver.parseFileName("20260231_120000.jpg"))
        assertNull(CaptureDateResolver.parseFileName("IMG_0001.jpg"))
        assertNotNull(CaptureDateResolver.parseFileName("IMG-20260728-WA0001.jpg"))
    }
}
