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

    /** Outcome of applying one batch. */
    data class BatchResult(
        val requested: Int,
        val succeeded: Int,
        val failed: List<ReviewSession.PendingMove>,
        val totalMillis: Long,
        val firstError: String?
    )

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
            photoSource.move(move.photo, move.destinationRelativePath).fold(
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
