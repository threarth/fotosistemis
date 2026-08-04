package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks which photos count as already filed by where they sit. */
class ClassificationAdopterTest {

    private fun destination(id: Long, label: String, path: String) =
        Destination(id, label, path, yearSubfolder = true, sortOrder = 0)

    private fun entry(photoId: Long, path: String) =
        ClassificationAdopter.InventoryEntry(photoId, path)

    private val famiglia = destination(1, "Famiglia", "Pictures/Famiglia")
    private val lavoro = destination(2, "Lavoro", "Pictures/Lavoro")

    @Test
    fun `adopts photos in a year subfolder whatever it is called`() {
        val preview = ClassificationAdopter.preview(
            listOf(
                entry(10, "Pictures/Famiglia/2026-famiglia/"),
                // The naming used before the pattern became configurable.
                entry(11, "Pictures/Famiglia/2025/"),
                entry(12, "Pictures/Lavoro/2026-lavoro/")
            ),
            listOf(famiglia, lavoro),
            alreadyDecided = emptySet()
        )

        assertEquals(3, preview.total)
        assertEquals(mapOf("Famiglia" to 2, "Lavoro" to 1), preview.byDestination)
    }

    @Test
    fun `adopts photos sitting directly in the destination`() {
        val preview = ClassificationAdopter.preview(
            listOf(entry(10, "Pictures/Famiglia/")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )
        assertEquals(1, preview.total)
    }

    @Test
    fun `leaves photos outside any destination alone`() {
        val preview = ClassificationAdopter.preview(
            listOf(entry(10, "DCIM/Camera/"), entry(11, "Pictures/Screenshots/")),
            listOf(famiglia, lavoro),
            alreadyDecided = emptySet()
        )
        assertTrue(preview.candidates.isEmpty())
    }

    @Test
    fun `never overrides a decision already taken`() {
        val preview = ClassificationAdopter.preview(
            listOf(entry(10, "Pictures/Famiglia/2026/"), entry(11, "Pictures/Famiglia/2026/")),
            listOf(famiglia),
            alreadyDecided = setOf(10)
        )

        assertEquals(1, preview.total)
        assertEquals(11L, preview.candidates.single().photoId)
    }

    @Test
    fun `a similar name is not a containing folder`() {
        val preview = ClassificationAdopter.preview(
            listOf(entry(10, "Pictures/FamigliaAllargata/2026/")),
            listOf(famiglia),
            alreadyDecided = emptySet()
        )
        assertTrue("Il confronto deve fermarsi al separatore", preview.candidates.isEmpty())
    }

    @Test
    fun `a nested destination wins over the one containing it`() {
        val nested = destination(3, "Nonni", "Pictures/Famiglia/Nonni")
        val preview = ClassificationAdopter.preview(
            listOf(entry(10, "Pictures/Famiglia/Nonni/2026/")),
            listOf(famiglia, nested),
            alreadyDecided = emptySet()
        )
        assertEquals("Nonni", preview.candidates.single().destination.label)
    }
}
