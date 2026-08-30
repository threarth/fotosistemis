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
                "_20160530-120000_IMG-20160530-WA0002.jpg",
                "_20180922-120000_Screenshot_20180922-071010.png",
                "_20240103-120000_PXL_20240103_182233456.jpg"
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
            listOf("_20260830-120000_IMG001.jpg", "_20260830-120000-2_IMG001.jpg"),
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

        assertTrue("No collision, no counter", named.none { it.contains("-2_") })
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
        assertEquals("_~20250314-120000_received_1234567890.jpeg", named.displayName)
    }

    @Test
    fun `a date already judged a guess stays a guess`() {
        val alreadyMarked = request(
            1,
            "_~20250314-120000_received_1234567890.jpeg",
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
        assertTrue("The date survives", named.startsWith("_20200714-120000_"))
        assertTrue("The file is still a jpeg", named.endsWith(".jpg"))
    }
}
