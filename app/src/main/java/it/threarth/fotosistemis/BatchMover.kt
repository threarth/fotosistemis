package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.review.ReviewSession

/**
 * Applies the queued moves in one batch.
 *
 * Filing and trashing are the same operation with different destinations, so
 * a single createWriteRequest covers the whole queue: one consent dialog per
 * batch, whatever it contains.
 */
class BatchMover(
    private val context: Context,
    private val photoSource: MediaStorePhotoSource,
    private val stateRepository: PhotoStateRepository
) {

    private companion object {

        /**
         * Files per consent request. Chosen well below what the binder
         * transaction can hold: asking twice costs the user a tap, while
         * overflowing it fails the whole batch.
         */
        const val MAX_FILES_PER_CONSENT = 500
    }

    /** Outcome of applying one batch. */
    data class BatchResult(
        val requested: Int,
        val succeeded: Int,
        val failed: List<ReviewSession.PendingMove>,
        val totalMillis: Long,
        val firstError: String?
    )

    /**
     * Splits a queue into batches one consent request can carry.
     *
     * The URIs travel to the system in a single binder transaction, which is
     * bounded: a reorganisation of a whole archive is far larger than a
     * review session and would not fit in one. Reviewing keeps asking once
     * because its queues are small enough to make a single batch.
     */
    fun consentBatches(
        moves: List<ReviewSession.PendingMove>
    ): List<List<ReviewSession.PendingMove>> = moves.chunked(MAX_FILES_PER_CONSENT)

    /** Consent covering every file in the queue. */
    fun buildConsent(moves: List<ReviewSession.PendingMove>): IntentSender =
        MediaStore.createWriteRequest(
            context.contentResolver,
            moves.map { photoSource.uriFor(it.photo) }
        ).intentSender

    /**
     * Moves every queued photo to its own destination. Must run off the main
     * thread. Failures are collected rather than aborting the batch, so one
     * bad file cannot block the rest.
     */
    fun applyAll(moves: List<ReviewSession.PendingMove>): BatchResult {
        var succeeded = 0
        val failed = ArrayList<ReviewSession.PendingMove>()
        var firstError: String? = null

        val startedAt = System.currentTimeMillis()
        for (move in moves) {
            photoSource.move(
                move.photo,
                move.destinationRelativePath,
                move.newDisplayName
            ).fold(
                onSuccess = {
                    succeeded++
                    stateRepository.recordMovedPath(move.photo.platformId, move.destinationRelativePath)
                },
                onFailure = { error ->
                    failed.add(move)
                    if (firstError == null) {
                        firstError = "${move.photo.displayName}: " +
                                (error.message ?: error::class.java.simpleName)
                    }
                }
            )
        }

        return BatchResult(
            requested = moves.size,
            succeeded = succeeded,
            failed = failed,
            totalMillis = System.currentTimeMillis() - startedAt,
            firstError = firstError
        )
    }
}
