package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.port.Database

/**
 * What the user has asked to be done to photos and is not done yet.
 *
 * New feature (v12). One proposal per photo at most: asking again replaces
 * the earlier request, and withdrawing deletes it and nothing else. The
 * truth about the photo, in [PhotoStateRepository], is not this class's to
 * touch — except that the first decision about a photo is what fixes where
 * it came from, and proposing is usually that decision.
 */
class ProposalRepository(
    private val database: Database,
    private val states: PhotoStateRepository
) {

    private companion object {

        val PROPOSAL_COLUMNS = listOf(
            Schema.COLUMN_PHOTO_ID, Schema.COLUMN_ACTION, Schema.COLUMN_DESTINATION_ID,
            Schema.COLUMN_PROPOSED_AT
        ).joinToString(", ")
    }

    /** Every proposal, keyed by photo id. */
    fun loadAll(): Result<Map<Long, Proposal>> = runCatching {
        database.query("SELECT $PROPOSAL_COLUMNS FROM ${Schema.TABLE_PROPOSALS}")
            .mapNotNull { row -> proposalOf(row)?.let { it.photoId to it } }
            .toMap()
    }

    /** One row as a proposal, or null when the row cannot be read as one. */
    private fun proposalOf(row: Database.Row): Proposal? {
        val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return null
        val action = Proposal.Action.fromStoredValue(row.getString(Schema.COLUMN_ACTION))
            ?: return null
        return Proposal(
            photoId,
            action,
            row.getLong(Schema.COLUMN_DESTINATION_ID),
            row.getLong(Schema.COLUMN_PROPOSED_AT) ?: 0L
        )
    }

    /**
     * Asks for [action] on one photo, replacing any earlier request, and
     * remembers where the photo is at that moment.
     */
    fun propose(photo: PhotoRecord, action: Proposal.Action, destinationId: Long?): Result<Proposal> =
        runCatching {
            database.transaction {
                val proposal = Proposal(photo.photoId, action, destinationId, System.currentTimeMillis())
                write(proposal)
                states.rememberOrigin(photo)
                proposal
            }
        }

    /**
     * Asks for the same thing on many photos, and says how many.
     *
     * One transaction for the lot: filing a folder is one act as far as the
     * user is concerned, and half a folder asked for would be worse than
     * none.
     */
    fun proposeAll(
        photos: List<PhotoRecord>,
        action: Proposal.Action,
        destinationId: Long?
    ): Result<Int> = runCatching {
        if (photos.isEmpty()) return@runCatching 0

        database.transaction {
            val now = System.currentTimeMillis()
            for (photo in photos) {
                write(Proposal(photo.photoId, action, destinationId, now))
                states.rememberOrigin(photo)
            }
            photos.size
        }
    }

    private fun write(proposal: Proposal) {
        database.execute(
            "INSERT OR REPLACE INTO ${Schema.TABLE_PROPOSALS} ($PROPOSAL_COLUMNS) " +
                    "VALUES (?, ?, ?, ?)",
            listOf(
                proposal.photoId, proposal.action.storedValue, proposal.destinationId,
                proposal.proposedAt
            )
        )
    }

    /**
     * Takes back the requests about [photoIds], and says how many there
     * were. The photos go on being whatever the truth says they are.
     */
    fun withdrawAll(photoIds: List<Long>): Result<Int> = runCatching {
        database.transaction {
            photoIds.sumOf { photoId ->
                database.execute(
                    "DELETE FROM ${Schema.TABLE_PROPOSALS} WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                    listOf(photoId)
                )
            }
        }
    }

    /** Takes back the request about one photo; see [withdrawAll]. */
    fun withdraw(photoId: Long): Result<Int> = withdrawAll(listOf(photoId))

    /** Takes back every request there is, and says how many there were. */
    fun withdrawEvery(): Result<Int> = runCatching {
        database.transaction {
            database.execute("DELETE FROM ${Schema.TABLE_PROPOSALS}")
        }
    }
}
