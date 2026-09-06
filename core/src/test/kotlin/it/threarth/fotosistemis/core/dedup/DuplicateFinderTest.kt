package it.threarth.fotosistemis.core.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule: same length and same bytes, or it is not the same photograph.
 */
class DuplicateFinderTest {

    private fun file(id: Long, name: String, size: Long, hash: String? = null) =
        DuplicateFinder.Candidate(id, "Pictures/Famiglia/", name, size, hash)

    @Test
    fun `una lunghezza che nessun altro ha non va nemmeno letta`() {
        val da = DuplicateFinder.needingHash(
            listOf(file(1, "a.jpg", 100), file(2, "b.jpg", 200), file(3, "c.jpg", 100))
        )

        assertEquals(listOf(1L, 3L), da.map { it.photoId })
    }

    @Test
    fun `stessa lunghezza e stessi byte sono la stessa foto`() {
        val gruppi = DuplicateFinder.groups(
            listOf(file(1, "a.jpg", 100, "x"), file(2, "a (1).jpg", 100, "x"))
        )

        assertEquals(1, gruppi.size)
        assertEquals(2, gruppi.first().copies.size)
        assertEquals(1, gruppi.first().extra)
    }

    /** The coincidence the length alone cannot rule out. */
    @Test
    fun `stessa lunghezza ma byte diversi non sono un doppione`() {
        val gruppi = DuplicateFinder.groups(
            listOf(file(1, "a.jpg", 100, "x"), file(2, "b.jpg", 100, "y"))
        )

        assertTrue(gruppi.isEmpty())
    }

    /** Not knowing is not knowing they are the same. */
    @Test
    fun `senza hash una foto non entra in nessun gruppo`() {
        val gruppi = DuplicateFinder.groups(
            listOf(file(1, "a.jpg", 100, "x"), file(2, "a (1).jpg", 100, null))
        )

        assertTrue(gruppi.isEmpty())
    }

    @Test
    fun `tre copie fanno un gruppo solo, con due di troppo`() {
        val gruppi = DuplicateFinder.groups(
            listOf(
                file(1, "a.jpg", 100, "x"),
                file(2, "a (1).jpg", 100, "x"),
                file(3, "a (2).jpg", 100, "x")
            )
        )

        assertEquals(1, gruppi.size)
        assertEquals(2, gruppi.first().extra)
    }

    /** The worst offenders first: a group of five matters before a pair. */
    @Test
    fun `i gruppi piu' numerosi vengono per primi`() {
        val gruppi = DuplicateFinder.groups(
            listOf(
                file(1, "a.jpg", 100, "x"), file(2, "a2.jpg", 100, "x"),
                file(3, "b.jpg", 200, "y"), file(4, "b2.jpg", 200, "y"),
                file(5, "b3.jpg", 200, "y")
            )
        )

        assertEquals(2, gruppi.first().extra)
        assertEquals(1, gruppi.last().extra)
    }

    private fun copy(
        name: String,
        path: String,
        catalogued: Boolean = false,
        immovable: Boolean = false,
        stamped: Boolean = false
    ) = DuplicateFinder.Candidate(
        photoId = name.hashCode().toLong(),
        relativePath = path,
        displayName = name,
        sizeBytes = 100,
        contentHash = "x",
        catalogued = catalogued,
        immovable = immovable,
        stamped = stamped
    )

    /**
     * The case that prompted the rule: the WhatsApp original sorted first
     * alphabetically, so the app proposed keeping the one copy that can
     * never be organised again.
     */
    @Test
    fun `fra una catalogata e l'originale whatsapp vince la catalogata`() {
        val gruppo = DuplicateFinder.Group(
            listOf(
                copy("IMG.jpg", "Android/media/com.whatsapp/", immovable = true),
                copy("__20240827__IMG.jpg", "Pictures/Famiglia/", catalogued = true, stamped = true)
            )
        )

        assertEquals("Pictures/Famiglia/", gruppo.suggested.relativePath)
    }

    /** Filed outranks everything, even a copy that is easier to handle. */
    @Test
    fun `la catalogata vince anche se e' l'unica immovibile`() {
        val gruppo = DuplicateFinder.Group(
            listOf(
                copy("a.jpg", "Pictures/Varie/"),
                copy("b.jpg", "Android/media/com.whatsapp/", catalogued = true, immovable = true)
            )
        )

        assertTrue(gruppo.suggested.catalogued)
    }

    /** Neither filed: the one that can still be moved is worth more. */
    @Test
    fun `a parita' di stato vince quella spostabile`() {
        val gruppo = DuplicateFinder.Group(
            listOf(
                copy("a.jpg", "Android/media/com.whatsapp/", immovable = true),
                copy("b.jpg", "Pictures/Varie/")
            )
        )

        assertEquals("Pictures/Varie/", gruppo.suggested.relativePath)
    }

    /** All else equal, the stamp decides; and after it, the alphabet. */
    @Test
    fun `a parita' vince la timbrata, poi l'ordine alfabetico`() {
        val gruppo = DuplicateFinder.Group(
            listOf(
                copy("z.jpg", "Pictures/Famiglia/"),
                copy("__20240827__a.jpg", "Pictures/Famiglia/", stamped = true)
            )
        )

        assertTrue(gruppo.suggested.stamped)
    }
}
