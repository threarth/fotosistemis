package it.threarth.fotosistemis.core.reorg

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the names photos take once a category is flattened: chronological
 * order, no stacked prefixes, and no two files fighting for one name.
 */
class FileNamerTest {

    private fun millisAt(year: Int, month: Int, day: Int, second: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, second)
        }.timeInMillis

    private fun request(
        photoId: Long,
        displayName: String,
        millis: Long,
        source: CaptureDateResolver.Source = CaptureDateResolver.Source.EXIF
    ) = FileNamer.Request(photoId, displayName, millis, source)

    private fun namesOf(vararg requests: FileNamer.Request): List<String> =
        FileNamer.nameAll(requests.toList()).map { it.displayName }

    @Test
    fun `a decade of devices ends up in chronological order`() {
        val named = namesOf(
            request(1, "PXL_20240103_182233456.jpg", millisAt(2024, 1, 3, 0)),
            request(2, "Screenshot_20180922-071010.png", millisAt(2018, 9, 22, 0)),
            request(3, "IMG-20160530-WA0002.jpg", millisAt(2016, 5, 30, 0))
        )

        assertEquals(
            "Sorted by name is sorted by time",
            named.sorted(),
            named.sortedBy { it }
        )
        assertEquals(
            listOf(
                "__20160530_120000__IMG-20160530-WA0002.jpg",
                "__20180922_120000__Screenshot_20180922-071010.png",
                "__20240103_120000__PXL_20240103_182233456.jpg"
            ),
            named.sorted()
        )
    }

    @Test
    fun `running it again changes nothing`() {
        val original = request(1, "IMG001.jpg", millisAt(2020, 7, 14, 0))
        val once = FileNamer.nameAll(listOf(original)).single().displayName
        val twice = FileNamer.nameAll(
            listOf(original.copy(displayName = once))
        ).single().displayName

        assertEquals("A second pass must not stack a second stamp", once, twice)
    }

    @Test
    fun `two photos sharing a second and a name are told apart`() {
        val sameSecond = millisAt(2026, 8, 30, 0)
        val named = namesOf(
            request(1, "IMG001.jpg", sameSecond),
            request(2, "IMG001.jpg", sameSecond)
        )

        assertEquals(
            listOf("__20260830_120000__IMG001.jpg", "__20260830_120000-2__IMG001.jpg"),
            named
        )
        assertEquals("Two files, two names", 2, named.toSet().size)
    }

    @Test
    fun `photos sharing a second but not a name need no counter`() {
        val sameSecond = millisAt(2026, 8, 30, 0)
        val named = namesOf(
            request(1, "IMG001.jpg", sameSecond),
            request(2, "IMG002.jpg", sameSecond)
        )

        assertTrue("No collision, no counter", named.none { it.contains("-2__") })
    }

    @Test
    fun `counters follow the photo, not the order it arrived in`() {
        val sameSecond = millisAt(2026, 8, 30, 0)
        val first = request(1, "IMG001.jpg", sameSecond)
        val second = request(2, "IMG001.jpg", sameSecond)

        assertEquals(
            "Discovering them the other way round must not renumber them",
            FileNamer.nameAll(listOf(first, second)),
            FileNamer.nameAll(listOf(second, first))
        )
    }

    @Test
    fun `a date that is only the file timestamp is marked`() {
        val named = FileNamer.nameAll(
            listOf(
                request(
                    1,
                    "received_1234567890.jpeg",
                    millisAt(2025, 3, 14, 0),
                    CaptureDateResolver.Source.FILE_TIMESTAMP
                )
            )
        ).single()

        assertTrue("The mark keeps it visible for review", named.uncertain)
        assertEquals("__+20250314_120000__received_1234567890.jpeg", named.displayName)
    }

    @Test
    fun `a date already judged a guess stays a guess`() {
        val alreadyMarked = request(
            1,
            "__+20250314_120000__received_1234567890.jpeg",
            millisAt(2025, 3, 14, 0),
            CaptureDateResolver.Source.ESTIMATED
        )

        assertEquals(
            alreadyMarked.displayName,
            FileNamer.nameAll(listOf(alreadyMarked)).single().displayName
        )
    }

    @Test
    fun `a date from the photograph carries no mark`() {
        for (source in listOf(
            CaptureDateResolver.Source.EXIF,
            CaptureDateResolver.Source.FILENAME
        )) {
            val named = FileNamer.nameAll(
                listOf(request(1, "IMG001.jpg", millisAt(2020, 7, 14, 0), source))
            ).single()

            assertTrue("$source is not a guess", !named.uncertain)
        }
    }

    @Test
    fun `a very long name is shortened, the stamp and extension are not`() {
        val longStem = "v".repeat(400)
        val named = FileNamer.nameAll(
            listOf(request(1, "$longStem.jpg", millisAt(2020, 7, 14, 0)))
        ).single().displayName

        assertTrue("Fits what the file system accepts", named.toByteArray().size <= 255)
        assertTrue("The date survives", named.startsWith("__20200714_120000__"))
        assertTrue("The file is still a jpeg", named.endsWith(".jpg"))
    }

    @Test
    fun `a stamp already written is left alone`() {
        val stamped = request(
            1,
            "__20180717_002424__IMG-20180717-WA0037.jpg",
            millisAt(2018, 7, 17, 0),
            CaptureDateResolver.Source.FILENAME
        )

        assertEquals(
            "Nothing new to say, nothing to rewrite",
            stamped.displayName,
            FileNamer.nameAll(listOf(stamped)).single().displayName
        )
    }

    @Test
    fun `a date recovered from exif replaces one guessed from the name`() {
        // What the WhatsApp photos need: the name gives the day, the file
        // itself still holds the hour.
        val recovered = FileNamer.Request(
            photoId = 1,
            displayName = "__20180717_000000__IMG-20180717-WA0037.jpg",
            captureMillis = Calendar.getInstance().apply {
                clear()
                set(2018, Calendar.JULY, 17, 20, 24, 24)
            }.timeInMillis,
            source = CaptureDateResolver.Source.EXIF
        )

        assertEquals(
            "__20180717_202424__IMG-20180717-WA0037.jpg",
            FileNamer.nameAll(listOf(recovered)).single().displayName
        )
    }

    @Test
    fun `a weaker reading never overwrites the stamp`() {
        val drifted = request(
            1,
            "__20180717_002424__IMG-20180717-WA0037.jpg",
            millisAt(2026, 7, 31, 0),
            CaptureDateResolver.Source.FILE_TIMESTAMP
        )

        assertEquals(
            "The transfer date must not win",
            drifted.displayName,
            FileNamer.nameAll(listOf(drifted)).single().displayName
        )
    }

    @Test
    fun `stamped and native names interleave by time within a day`() {
        // The reason the stamp joins date and time the way the cameras do.
        val stamped = FileNamer.nameAll(
            listOf(
                request(
                    1, "IMG-20250927-WA0009.jpg",
                    Calendar.getInstance().apply {
                        clear()
                        set(2025, Calendar.SEPTEMBER, 27, 23, 0, 0)
                    }.timeInMillis
                )
            )
        ).single().displayName

        assertTrue("08:00 comes before 23:00", "20250927_080000.jpg" < stamped)
    }

    @Test
    fun `a photo that came from a chat app says so in its name`() {
        val named = FileNamer.nameAll(
            listOf(
                FileNamer.Request(
                    photoId = 1,
                    displayName = "IMG-20250929-WA0012.jpg",
                    captureMillis = millisAt(2025, 9, 29, 0),
                    source = CaptureDateResolver.Source.FILENAME,
                    origin = "whatsapp"
                )
            )
        ).single().displayName

        assertEquals("__20250929_120000_from_whatsapp__IMG-20250929-WA0012.jpg", named)
    }

    @Test
    fun `the origin survives being read back and written again`() {
        val timbrata = FileNamer.Request(
            photoId = 1,
            displayName = "__20250929_120000_from_whatsapp__IMG-20250929-WA0012.jpg",
            captureMillis = millisAt(2025, 9, 29, 0),
            source = CaptureDateResolver.Source.FILENAME
        )

        assertEquals(
            "Rileggerla non deve perdere da dove viene",
            timbrata.displayName,
            FileNamer.nameAll(listOf(timbrata)).single().displayName
        )
        assertEquals(
            "whatsapp",
            CaptureDateResolver.readStamp(timbrata.displayName)?.origin
        )
    }
}
