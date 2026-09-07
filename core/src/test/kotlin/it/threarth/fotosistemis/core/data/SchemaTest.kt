package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.review.ReviewSession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bin's path is written in two places and must say the same thing.
 *
 * The migration needs it as literal SQL and cannot ask the review layer;
 * the review layer owns it. A copy that drifts would leave the migration
 * marking photos as still owed when they are already in the bin, which
 * would offer to move them a second time.
 */
class SchemaTest {

    @Test
    fun `il percorso del cestino nello schema e in ReviewSession coincidono`() {
        assertEquals(
            ReviewSession.DELETION_STAGING_PATH.trim('/'),
            Schema.BIN_PATH.trim('/')
        )
    }
}
