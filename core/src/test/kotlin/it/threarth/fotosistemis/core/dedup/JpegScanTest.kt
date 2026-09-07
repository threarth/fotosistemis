package it.threarth.fotosistemis.core.dedup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import org.junit.Test

/**
 * The walk has to land on the scan whatever the header holds.
 *
 * Built from bytes rather than from a real photograph: the case this exists
 * for is two files whose headers differ and whose scans do not, and only a
 * hand-built pair can say that plainly.
 */
class JpegScanTest {

    private companion object {
        const val PREFIX = 0xFF
        const val START = 0xD8
        const val SCAN = 0xDA
        const val END = 0xD9
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val COMMENT = 0xFE

        /** Stands in for the photograph: what both files must share. */
        val PICTURE = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
    }

    /** A segment: its marker, its declared length, then that many bytes. */
    private fun segment(marker: Int, payload: Int): List<Int> {
        val length = payload + 2
        return listOf(PREFIX, marker, length shr 8, length and 0xFF) + List(payload) { 0x55 }
    }

    private fun jpeg(vararg header: List<Int>): ByteArray {
        val bytes = listOf(PREFIX, START) + header.toList().flatten() + listOf(PREFIX, SCAN)
        return bytes.map { it.toByte() }.toByteArray() + PICTURE
    }

    /** What is left of the stream once the walk says it has arrived. */
    private fun remainderOf(file: ByteArray): ByteArray {
        val stream = ByteArrayInputStream(file)
        assertTrue(JpegScan.skipToScan(stream))
        return stream.readBytes()
    }

    @Test
    fun `due file con intestazioni diverse lasciano la stessa immagine`() {
        val withJfif = jpeg(segment(APP0, 14))
        val withExif = jpeg(segment(APP1, 222), segment(COMMENT, 30))

        assertArrayEquals(PICTURE, remainderOf(withJfif))
        assertArrayEquals(PICTURE, remainderOf(withExif))
    }

    @Test
    fun `il riempimento prima di un marcatore non viene letto come segmento`() {
        val padded = byteArrayOf(
            PREFIX.toByte(), START.toByte(), PREFIX.toByte(), PREFIX.toByte(),
            PREFIX.toByte(), SCAN.toByte()
        ) + PICTURE

        assertArrayEquals(PICTURE, remainderOf(padded))
    }

    @Test
    fun `un file che non e' un jpeg non ha immagine da leggere`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        assertFalse(JpegScan.skipToScan(ByteArrayInputStream(png)))
    }

    @Test
    fun `un jpeg che finisce prima dell'immagine non ne ha una`() {
        val truncated = listOf(PREFIX, START) + segment(APP1, 100) + listOf(PREFIX, END)

        assertFalse(
            JpegScan.skipToScan(
                ByteArrayInputStream(truncated.map { it.toByte() }.toByteArray())
            )
        )
    }

    @Test
    fun `un segmento troncato non manda la lettura oltre la fine`() {
        val lying = listOf(PREFIX, START) + listOf(PREFIX, APP1, 0x10, 0x00) + listOf(1, 2, 3)

        assertFalse(
            JpegScan.skipToScan(
                ByteArrayInputStream(lying.map { it.toByte() }.toByteArray())
            )
        )
    }
}
