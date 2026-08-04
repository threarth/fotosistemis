package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.CaptureDateResolver
import it.threarth.fotosistemis.core.model.PhotoRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks how photos are recognised when the platform identifier changes.
 *
 * The failure this guards against is silent: a photo that is not recognised
 * comes back as never seen, and the tags and decisions attached to it stay in
 * the database out of reach.
 */
class PhotoMatcherTest {

    private fun stored(
        photoId: Long,
        mediaId: Long?,
        name: String = "IMG_0001.jpg",
        size: Long = 5_900_000L,
        taken: Long = 1_596_556_537_000L
    ) = PhotoMatcher.Stored(photoId, mediaId, name, size, taken)

    private fun record(
        platformId: Long,
        name: String = "IMG_0001.jpg",
        size: Long = 5_900_000L,
        taken: Long = 1_596_556_537_000L
    ) = PhotoRecord(
        platformId = platformId,
        volumeName = "external_primary",
        displayName = name,
        relativePath = "DCIM/Camera/",
        sizeBytes = size,
        dateTakenMillis = taken,
        dateSource = CaptureDateResolver.Source.EXIF
    )

    @Test
    fun `matches on the platform id when it still resolves`() {
        val plan = PhotoMatcher.match(listOf(stored(7, 42)), listOf(record(42)))
        val match = plan.matches.single()

        assertEquals(PhotoMatcher.MatchKind.PLATFORM_ID, match.kind)
        assertEquals(7L, match.photoId)
        assertTrue("Nessuna riscrittura necessaria", !match.needsRekey)
    }

    @Test
    fun `recognises a photo whose platform id changed`() {
        // What happens after a card is remounted: same file, new id.
        val plan = PhotoMatcher.match(listOf(stored(7, 42)), listOf(record(999)))
        val match = plan.matches.single()

        assertEquals(PhotoMatcher.MatchKind.FINGERPRINT, match.kind)
        assertEquals("Deve ritrovare la stessa foto", 7L, match.photoId)
        assertTrue("Il vecchio id va riscritto", match.needsRekey)
        assertEquals(0, plan.newCount)
    }

    @Test
    fun `recognises a renamed photo when only one candidate fits`() {
        val plan = PhotoMatcher.match(
            listOf(stored(7, 42, name = "IMG_0001.jpg")),
            listOf(record(999, name = "vacanza.jpg"))
        )
        val match = plan.matches.single()

        assertEquals(PhotoMatcher.MatchKind.SIZE_AND_DATE, match.kind)
        assertEquals(7L, match.photoId)
    }

    @Test
    fun `refuses to guess when several photos share size and date`() {
        // Guessing here would move one photo's tags onto another.
        val plan = PhotoMatcher.match(
            listOf(stored(7, 42, name = "a.jpg"), stored(8, 43, name = "b.jpg")),
            listOf(record(999, name = "sconosciuta.jpg"))
        )

        assertEquals(PhotoMatcher.MatchKind.NEW, plan.matches.single().kind)
    }

    @Test
    fun `a stored photo is claimed only once`() {
        val plan = PhotoMatcher.match(
            listOf(stored(7, 42)),
            listOf(record(42), record(999))
        )

        assertEquals(PhotoMatcher.MatchKind.PLATFORM_ID, plan.matches[0].kind)
        assertEquals(
            "La seconda non puo' rivendicare la stessa riga",
            PhotoMatcher.MatchKind.NEW,
            plan.matches[1].kind
        )
    }

    @Test
    fun `unseen photos are reported as new`() {
        val plan = PhotoMatcher.match(
            emptyList(),
            listOf(record(1, name = "a.jpg"), record(2, name = "b.jpg", size = 100L))
        )

        assertEquals(2, plan.newCount)
        assertTrue(plan.matches.all { it.photoId == null })
    }

    @Test
    fun `missing photos surface only after a complete scan`() {
        val stored = listOf(stored(7, 42), stored(8, 43, name = "sparita.jpg", size = 100L))

        val partial = PhotoMatcher.match(stored, listOf(record(42)))
        assertTrue("Una cartella sola non prova che manchi", partial.missingPhotoIds.isEmpty())

        val complete = PhotoMatcher.match(stored, listOf(record(42)), recordsAreComplete = true)
        assertEquals(listOf(8L), complete.missingPhotoIds)
    }

    @Test
    fun `a photo with no stored platform id is still recognised`() {
        // Rows created by the migration start out with no characteristics
        // beyond what the old tables held.
        val plan = PhotoMatcher.match(listOf(stored(7, null)), listOf(record(42)))

        assertEquals(PhotoMatcher.MatchKind.FINGERPRINT, plan.matches.single().kind)
        assertEquals(7L, plan.matches.single().photoId)
    }
}
