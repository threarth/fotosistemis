package it.threarth.fotosistemis.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the stamp the app writes at the front of a file name, and the order
 * of trust between the sources a capture date can come from.
 */
class CaptureDateResolverTest {

    /** A capture time in the same zone the stamp is written and read in. */
    private fun millisAt(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day, hour, minute, second)
    }.timeInMillis

    private val summer2026 = millisAt(2026, 8, 30, 12, 0, 0)

    @Test
    fun `writes the year first so names sort chronologically`() {
        val stamp = CaptureDateResolver.formatStamp(summer2026, uncertain = false, counter = null)

        assertEquals("_20260830-120000_", stamp)
    }

    @Test
    fun `reads back the time it wrote`() {
        val stamp = CaptureDateResolver.formatStamp(summer2026, uncertain = false, counter = null)
        val read = CaptureDateResolver.readStamp(stamp + "IMG001.jpg")

        assertEquals(summer2026, read?.millis)
        assertEquals(CaptureDateResolver.Source.FILENAME, read?.source)
        assertNull("Nothing to disambiguate", read?.counter)
    }

    @Test
    fun `the mark says the date is only a fallback`() {
        val stamp = CaptureDateResolver.formatStamp(summer2026, uncertain = true, counter = null)

        assertEquals("_~20260830-120000_", stamp)
        assertEquals(
            CaptureDateResolver.Source.ESTIMATED,
            CaptureDateResolver.readStamp(stamp + "foto.jpg")?.source
        )
    }

    @Test
    fun `the counter travels inside the stamp`() {
        val stamp = CaptureDateResolver.formatStamp(summer2026, uncertain = false, counter = 2)

        assertEquals("_20260830-120000-2_", stamp)
        assertEquals(2, CaptureDateResolver.readStamp(stamp + "IMG001.jpg")?.counter)
    }

    @Test
    fun `stamping twice does not stack prefixes`() {
        val once = CaptureDateResolver.formatStamp(summer2026, uncertain = false, counter = null) +
                "IMG001.jpg"
        val twice = CaptureDateResolver.formatStamp(summer2026, uncertain = false, counter = null) +
                CaptureDateResolver.stripStamp(once)

        assertEquals(once, twice)
    }

    @Test
    fun `leaves a name that is not ours alone`() {
        val foreign = listOf("IMG_20260728_153045.jpg", "_MG_1234.jpg", "vacanza mare.jpg")

        for (name in foreign) {
            assertNull("Not our stamp: $name", CaptureDateResolver.readStamp(name))
            assertEquals(name, CaptureDateResolver.stripStamp(name))
        }
    }

    @Test
    fun `digits that are not a date are not our stamp`() {
        // The 30th of February: the shape matches, the date does not exist.
        val name = "_20260230-120000_IMG001.jpg"

        assertNull(CaptureDateResolver.readStamp(name))
        assertEquals(
            "Left untouched rather than half read",
            name,
            CaptureDateResolver.stripStamp(name)
        )
    }

    @Test
    fun `exif still wins over the stamp`() {
        val exif = millisAt(2011, 3, 3, 19, 45, 0)
        val name = CaptureDateResolver.formatStamp(summer2026, uncertain = true, counter = null) +
                "foto.jpg"

        val resolved = CaptureDateResolver.resolve(name, exifMillis = exif, fileMillis = 0L)

        assertEquals(exif, resolved.millis)
        assertEquals(CaptureDateResolver.Source.EXIF, resolved.source)
    }

    @Test
    fun `the stamp keeps a photo from being redated by its own move`() {
        val stamped = CaptureDateResolver.formatStamp(summer2026, uncertain = true, counter = null) +
                "received_1234567890.jpeg"
        val afterAMove = millisAt(2027, 1, 1, 9, 0, 0)

        val resolved =
            CaptureDateResolver.resolve(stamped, exifMillis = null, fileMillis = afterAMove)

        assertEquals("The file system must not move the date", summer2026, resolved.millis)
        assertNotEquals(afterAMove, resolved.millis)
        assertEquals(CaptureDateResolver.Source.ESTIMATED, resolved.source)
    }

    @Test
    fun `without a stamp an opaque name falls back to the file`() {
        val fileMillis = millisAt(2025, 3, 14, 12, 0, 0)

        val resolved = CaptureDateResolver.resolve(
            "received_1234567890.jpeg",
            exifMillis = null,
            fileMillis = fileMillis
        )

        assertEquals(fileMillis, resolved.millis)
        assertEquals(CaptureDateResolver.Source.FILE_TIMESTAMP, resolved.source)
    }

    @Test
    fun `sources rank from the photograph to the file`() {
        val order = listOf(
            CaptureDateResolver.Source.EXIF,
            CaptureDateResolver.Source.FILENAME,
            CaptureDateResolver.Source.ESTIMATED,
            CaptureDateResolver.Source.FILE_TIMESTAMP
        )

        for ((index, better) in order.withIndex()) {
            for (worse in order.drop(index + 1)) {
                assertTrue("$better should outrank $worse", better.outranks(worse))
                assertTrue("$worse must not outrank $better", !worse.outranks(better))
            }
        }
    }
}
