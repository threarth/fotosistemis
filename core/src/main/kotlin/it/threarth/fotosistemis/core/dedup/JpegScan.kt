package it.threarth.fotosistemis.core.dedup

import java.io.InputStream

/**
 * Finds where a JPEG stops describing itself and starts being a picture.
 *
 * A JPEG opens with a run of segments — the capture date, a thumbnail,
 * comments, the tables the decoder needs — and then the scan, which is the
 * photograph. Everything before the scan can be rewritten without touching
 * the picture, and the app does exactly that: filing a photo the platform
 * will not let us move copies it and writes the capture date into the copy.
 * The two files then share no byte of header and every byte of scan.
 *
 * Platform-free on purpose, and apart from the class that reads files, so
 * the walk can be tested against bytes built by hand.
 */
object JpegScan {

    /** Every marker is introduced by this byte. */
    private const val MARKER_PREFIX = 0xFF

    /** Start of scan: from here on the file is the picture itself. */
    private const val MARKER_SCAN = 0xDA

    /** End of image: reached without a scan, there is nothing to hash. */
    private const val MARKER_END = 0xD9

    /** The file must open with start-of-image, or it is not a JPEG. */
    private const val MARKER_START = 0xD8

    /**
     * Markers that stand alone, carrying no length and no payload.
     *
     * The restart markers and the two padding ones. Reading a length after
     * one of these would take two bytes of somebody else's data and walk off
     * into the middle of the file.
     */
    private val STANDALONE_MARKERS = (0xD0..0xD9).toSet() + setOf(0x01, MARKER_START)

    /** A segment's length is written as two bytes, most significant first. */
    private const val BITS_PER_BYTE = 8

    /** Those two bytes count themselves in the length they declare. */
    private const val LENGTH_FIELD_BYTES = 2

    /**
     * Advances [stream] to the first byte of the scan, and says whether it
     * got there.
     *
     * False when the file is not a JPEG, or ends before the scan. Both are
     * ordinary answers rather than errors: a PNG has no scan, and neither
     * has a file truncated in transfer.
     */
    fun skipToScan(stream: InputStream): Boolean {
        if (stream.read() != MARKER_PREFIX || stream.read() != MARKER_START) return false

        while (true) {
            val marker = nextMarker(stream) ?: return false
            if (marker == MARKER_SCAN) return true
            if (marker == MARKER_END) return false
            if (marker in STANDALONE_MARKERS) continue

            val high = stream.read()
            val low = stream.read()
            if (high < 0 || low < 0) return false
            if (!skipFully(stream, (high shl BITS_PER_BYTE) + low - LENGTH_FIELD_BYTES)) return false
        }
    }

    /**
     * The next marker's own byte, the padding before it skipped.
     *
     * A marker may be preceded by any number of fill bytes, and taking the
     * first of them for the marker itself would read padding as a segment.
     */
    private fun nextMarker(stream: InputStream): Int? {
        var byte = stream.read()
        while (byte >= 0 && byte != MARKER_PREFIX) byte = stream.read()
        if (byte < 0) return null

        while (byte == MARKER_PREFIX) {
            byte = stream.read()
            if (byte < 0) return null
        }
        return byte
    }

    /** Skips exactly [count] bytes, or says the file ended first. */
    private fun skipFully(stream: InputStream, count: Int): Boolean {
        if (count < 0) return false
        var left = count.toLong()
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped <= 0) {
                if (stream.read() < 0) return false
                left--
            } else {
                left -= skipped
            }
        }
        return true
    }
}
