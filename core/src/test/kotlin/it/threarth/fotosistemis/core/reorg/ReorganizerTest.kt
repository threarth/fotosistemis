package it.threarth.fotosistemis.core.reorg

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.Destination
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks what the reorganisation would write: flattening a category, folding
 * it back into years, renaming it, and refusing to guess when two photos
 * would end up sharing a name.
 */
class ReorganizerTest {

    private companion object {
        const val YEAR_PATTERN = "{anno}-{etichetta}"
        const val FAMIGLIA_ID = 1L
        const val INTERNAL = "external_primary"
    }

    private fun millisIn(year: Int, day: Int): Long = Calendar.getInstance().apply {
        clear()
        set(year, Calendar.JULY, day, 12, 0, 0)
    }.timeInMillis

    private fun famiglia(yearSubfolder: Boolean) =
        Destination(FAMIGLIA_ID, "Famiglia", "Pictures/Famiglia", yearSubfolder, sortOrder = 0)

    private fun entry(
        photoId: Long,
        path: String,
        name: String,
        year: Int,
        day: Int = 14,
        source: CaptureDateResolver.Source = CaptureDateResolver.Source.EXIF
    ) = Reorganizer.Entry(
        photoId = photoId,
        destinationId = FAMIGLIA_ID,
        volumeName = INTERNAL,
        relativePath = path,
        displayName = name,
        captureMillis = millisIn(year, day),
        source = source
    )

    @Test
    fun `flattening brings the year folders into the category`() {
        val plan = Reorganizer.plan(
            listOf(
                entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016),
                entry(2, "Pictures/Famiglia/2025-famiglia/", "IMG002.jpg", 2025)
            ),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = true)),
            YEAR_PATTERN
        )

        assertEquals(2, plan.total)
        assertTrue(plan.moves.all { it.toRelativePath == "Pictures/Famiglia/" })
        assertEquals(
            listOf("__20160714_120000__IMG001.jpg", "__20250714_120000__IMG002.jpg"),
            plan.moves.map { it.toDisplayName }
        )
        assertTrue("Nothing ambiguous", !plan.isBlocked)
    }

    @Test
    fun `folding back into years is the same operation`() {
        val plan = Reorganizer.plan(
            listOf(entry(1, "Pictures/Famiglia/", "__20160714_120000__IMG001.jpg", 2016)),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = true), stampNames = true)),
            YEAR_PATTERN
        )

        assertEquals("Pictures/Famiglia/2016-famiglia/", plan.moves.single().toRelativePath)
    }

    @Test
    fun `renaming a category rewrites the path of every photo in it`() {
        val renamed = Destination(
            FAMIGLIA_ID, "Famiglia e amici", "Pictures/Famiglia e amici",
            yearSubfolder = false, sortOrder = 0
        )
        val plan = Reorganizer.plan(
            listOf(
                entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016),
                entry(2, "Pictures/Famiglia/2025-famiglia/", "IMG002.jpg", 2025)
            ),
            listOf(Reorganizer.Choice(renamed, stampNames = false)),
            YEAR_PATTERN
        )

        assertEquals(2, plan.total)
        assertTrue(plan.moves.all { it.toRelativePath == "Pictures/Famiglia e amici/" })
        assertEquals("Famiglia e amici", plan.moves.first().categoryLabel)
    }

    @Test
    fun `turning the stamp off takes it back off the names`() {
        val plan = Reorganizer.plan(
            listOf(entry(1, "Pictures/Famiglia/", "__20160714_120000__IMG001.jpg", 2016)),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = false)),
            YEAR_PATTERN
        )

        assertEquals("IMG001.jpg", plan.moves.single().toDisplayName)
    }

    @Test
    fun `flattening without the stamp reports the names that would clash`() {
        val plan = Reorganizer.plan(
            listOf(
                entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016),
                entry(2, "Pictures/Famiglia/2025-famiglia/", "IMG001.jpg", 2025)
            ),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = false)),
            YEAR_PATTERN
        )

        assertTrue("The user has to decide, not the app", plan.isBlocked)
        val conflict = plan.conflicts.single()
        assertEquals("IMG001.jpg", conflict.displayName)
        assertEquals(listOf(1L, 2L), conflict.photoIds.sorted())
    }

    @Test
    fun `the stamp is what makes flattening safe`() {
        val clashing = listOf(
            entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016),
            entry(2, "Pictures/Famiglia/2025-famiglia/", "IMG001.jpg", 2025)
        )
        val plan = Reorganizer.plan(
            clashing,
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = true)),
            YEAR_PATTERN
        )

        assertTrue("The date tells them apart", !plan.isBlocked)
        assertEquals(2, plan.moves.map { it.toDisplayName }.toSet().size)
    }

    @Test
    fun `a photo already in place is not moved`() {
        val plan = Reorganizer.plan(
            listOf(entry(1, "Pictures/Famiglia/", "IMG001.jpg", 2016)),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = false)),
            YEAR_PATTERN
        )

        assertEquals(0, plan.total)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun `a category nobody asked about is left alone`() {
        val other = Reorganizer.Entry(
            photoId = 9, destinationId = 2L, volumeName = INTERNAL,
            relativePath = "Pictures/Lavoro/2020-lavoro/", displayName = "IMG009.jpg",
            captureMillis = millisIn(2020, 14), source = CaptureDateResolver.Source.EXIF
        )
        val plan = Reorganizer.plan(
            listOf(entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016), other),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = true)),
            YEAR_PATTERN
        )

        assertEquals(1, plan.total)
        assertEquals(1L, plan.moves.single().photoId)
    }

    @Test
    fun `photos on different volumes do not clash with each other`() {
        val onCard = Reorganizer.Entry(
            photoId = 2, destinationId = FAMIGLIA_ID, volumeName = "sd_card",
            relativePath = "Pictures/Famiglia/2025-famiglia/", displayName = "IMG001.jpg",
            captureMillis = millisIn(2025, 14), source = CaptureDateResolver.Source.EXIF
        )
        val plan = Reorganizer.plan(
            listOf(entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016), onCard),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = false)),
            YEAR_PATTERN
        )

        assertTrue("Two parallel archives, not one folder", !plan.isBlocked)
    }

    @Test
    fun `counts the photos whose date the app cannot vouch for`() {
        val plan = Reorganizer.plan(
            listOf(
                entry(1, "Pictures/Famiglia/2016-famiglia/", "IMG001.jpg", 2016),
                entry(
                    2, "Pictures/Famiglia/2025-famiglia/", "received_1.jpeg", 2025,
                    source = CaptureDateResolver.Source.FILE_TIMESTAMP
                )
            ),
            listOf(Reorganizer.Choice(famiglia(yearSubfolder = false), stampNames = true)),
            YEAR_PATTERN
        )

        assertEquals(1, plan.uncertainCount)
        assertEquals(mapOf("Famiglia" to 2), plan.byCategory)
    }

    @Test
    fun `a day full of file timestamps is an import, not a day of shooting`() {
        val imported = (1L..12L).map {
            entry(
                it, "Pictures/Famiglia/2025-famiglia/", "received_$it.jpeg", 2025,
                source = CaptureDateResolver.Source.FILE_TIMESTAMP
            )
        }
        val shot = entry(99, "Pictures/Famiglia/2025-famiglia/", "IMG099.jpg", 2025, day = 20)

        val clusters = Reorganizer.clustersOf(imported + shot)

        assertEquals(1, clusters.size)
        assertEquals("2025-07-14", clusters.single().dayLabel)
        assertEquals(12, clusters.single().photoCount)
    }
}
