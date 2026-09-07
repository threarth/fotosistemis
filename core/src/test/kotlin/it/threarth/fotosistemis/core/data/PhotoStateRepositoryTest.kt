package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.port.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handing a file to Android's bin must not decide what became of the
 * photograph.
 *
 * The two cases end in the same place for opposite reasons, and the app
 * once wrote the same truth for both: a photo copied into its category
 * came out recorded as thrown away, which hid a picture the user had asked
 * to keep. These tests pin the difference down.
 */
class PhotoStateRepositoryTest {

    private companion object {
        const val PHOTO_ID = 42L
        const val WHATSAPP_PATH = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"
        const val PHOTO_NAME = "IMG-20200101-WA0000.jpg"
        const val TRUTH_WRITTEN = "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_STATE}"
        const val PATH_TABLE = Schema.TABLE_PHOTO_PATHS
    }

    /** Accepts every statement, keeping the SQL for the asserts. */
    private class RecordingDatabase : Database {
        val statements = ArrayList<String>()

        override fun query(sql: String, args: List<Any?>): List<Database.Row> = emptyList()

        override fun execute(sql: String, args: List<Any?>): Int {
            statements.add(sql)
            return 1
        }

        override fun insert(sql: String, args: List<Any?>): Long {
            statements.add(sql)
            return 1
        }

        override fun <T> transaction(block: () -> T): T = block()
    }

    private val database = RecordingDatabase()
    private val repository = PhotoStateRepository(database)

    private fun handOver(thrownAway: Boolean) = repository.recordSystemBin(
        PHOTO_ID, WHATSAPP_PATH, PHOTO_NAME, thrownAway = thrownAway
    )

    private fun truthsWritten() = database.statements.count { it.startsWith(TRUTH_WRITTEN) }

    @Test
    fun `l'originale di una foto archiviata non diventa cestinato`() {
        assertTrue(handOver(thrownAway = false).isSuccess)
        assertEquals(0, truthsWritten())
    }

    @Test
    fun `una foto scartata viene registrata come cestinata`() {
        assertTrue(handOver(thrownAway = true).isSuccess)
        assertEquals(1, truthsWritten())
    }

    @Test
    fun `la consegna al cestino di sistema viene comunque annotata`() {
        handOver(thrownAway = false)
        assertTrue(database.statements.any { it.contains(PATH_TABLE) })
    }
}
