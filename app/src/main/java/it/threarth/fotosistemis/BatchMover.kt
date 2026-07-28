package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore

/**
 * Applies queued moves in one batch.
 *
 * Each queued item carries its own destination, so a single batch can mix
 * trashing and several different tags, and still cost the user one consent
 * dialog for the whole lot.
 */
class BatchMover(private val context: Context, private val repository: MediaStoreRepository) {

    /** Outcome of applying a batch. */
    data class BatchResult(
        val requested: Int,
        val succeeded: Int,
        val failed: List<ReviewSession.PendingMove>,
        val totalMillis: Long,
        val firstError: String?
    )

    /**
     * Builds the system consent dialog covering every photo in [moves].
     *
     * One IntentSender for the whole queue is what makes the "review now,
     * apply later" model bearable: one prompt per batch, not one per photo.
     */
    fun buildWriteConsent(moves: List<ReviewSession.PendingMove>): IntentSender =
        MediaStore.createWriteRequest(
            context.contentResolver,
            moves.map { it.photo.uri }
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
            repository.move(move.photo, move.destinationRelativePath).fold(
                onSuccess = { succeeded++ },
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
