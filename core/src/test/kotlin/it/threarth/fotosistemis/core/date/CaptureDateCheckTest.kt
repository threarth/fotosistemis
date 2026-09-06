package it.threarth.fotosistemis.core.date

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the app now works by: a date is right only when every carrier
 * says the same thing.
 */
class CaptureDateCheckTest {

    private val quando = 1_787_788_800_000L

    @Test
    fun `tutti d'accordo e' l'unico stato a posto`() {
        val verdict = CaptureDateCheck.check(
            quando,
            CaptureDateCheck.Carriers(quando, quando, quando)
        )

        assertTrue(verdict.settled)
        assertTrue(verdict.wrong.isEmpty())
    }

    /** Exactly the state the WhatsApp copies were left in. */
    @Test
    fun `exif scritto ma archivio e file fermi non e' a posto`() {
        val oggi = quando + 11L * 24 * 60 * 60 * 1000
        val verdict = CaptureDateCheck.check(
            quando,
            CaptureDateCheck.Carriers(exifMillis = quando, fileMillis = oggi, indexMillis = null)
        )

        assertFalse(verdict.settled)
        assertEquals(CaptureDateCheck.State.AGREES, verdict.exif)
        assertEquals(CaptureDateCheck.State.DISAGREES, verdict.file)
        assertEquals(CaptureDateCheck.State.SILENT, verdict.index)
        assertEquals(
            listOf(CaptureDateCheck.CARRIER_FILE, CaptureDateCheck.CARRIER_INDEX),
            verdict.wrong
        )
    }

    /** A carrier saying nothing is as wrong as one saying the wrong thing. */
    @Test
    fun `il silenzio conta come disaccordo`() {
        val verdict = CaptureDateCheck.check(
            quando,
            CaptureDateCheck.Carriers(null, null, null)
        )

        assertFalse(verdict.settled)
        assertEquals(3, verdict.wrong.size)
    }

    /**
     * Seconds against milliseconds: without a tolerance every photograph on
     * the phone would be reported broken.
     */
    @Test
    fun `una manciata di millisecondi non e' un disaccordo`() {
        val verdict = CaptureDateCheck.check(
            quando,
            CaptureDateCheck.Carriers(quando - 999, quando + 1_500, quando)
        )

        assertTrue(verdict.settled)
    }

    @Test
    fun `un giorno di scarto e' un disaccordo`() {
        val verdict = CaptureDateCheck.check(
            quando,
            CaptureDateCheck.Carriers(quando + 86_400_000, quando, quando)
        )

        assertEquals(CaptureDateCheck.State.DISAGREES, verdict.exif)
        assertFalse(verdict.settled)
    }
}
