package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore

/**
 * Applies queued actions in batches.
 *
 * Moving and trashing need separate system consents: createWriteRequest
 * covers changing a file, createTrashRequest covers sending it to the system
 * trash. A queue containing both therefore costs two dialogs, which is the
 * price of using the real trash instead of a folder of our own. Photos land
 * in the system trash, recoverable for thirty days and purged automatically.
 */
class BatchMover(
    private val context: Context,
    private val mediaRepository: MediaStoreRepository,
    private val stateRepository: PhotoStateRepository
) {

    /** Outcome of applying one batch. */
    data class BatchResult(
        val requested: Int,
        val succeeded: Int,
        val failed: List<ReviewSession.PendingAction>,
        val totalMillis: Long,
        val firstError: String?
    )

    /** Consent covering every file that is about to be moved. */
    fun buildMoveConsent(moves: List<ReviewSession.PendingAction.Move>): IntentSender =
        MediaStore.createWriteRequest(
            context.contentResolver,
            moves.map { it.photo.uri }
        ).intentSender

    /** Consent covering every file that is about to be trashed. */
    fun buildTrashConsent(trashed: List<ReviewSession.PendingAction.Trash>): IntentSender =
        MediaStore.createTrashRequest(
            context.contentResolver,
            trashed.map { it.photo.uri },
            true
        ).intentSender

    /**
     * Files every queued photo into its own destination. Must run off the
     * main thread. Failures are collected rather than aborting the batch, so
     * one bad file cannot block the rest.
     */
    fun applyMoves(moves: List<ReviewSession.PendingAction.Move>): BatchResult {
        var succeeded = 0
        val failed = ArrayList<ReviewSession.PendingAction>()
        var firstError: String? = null

        val startedAt = System.currentTimeMillis()
        for (move in moves) {
            mediaRepository.move(move.photo, move.destinationRelativePath).fold(
                onSuccess = {
                    succeeded++
                    stateRepository.recordMovedPath(move.photo.mediaId, move.destinationRelativePath)
                },
                onFailure = { error ->
                    failed.add(move)
                    if (firstError == null) firstError = describe(move.photo.displayName, error)
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

    /**
     * The trash request itself performs the operation once the user consents,
     * so this only records the outcome in the path history.
     */
    fun recordTrashed(trashed: List<ReviewSession.PendingAction.Trash>) {
        for (action in trashed) {
            stateRepository.recordMovedPath(action.photo.mediaId, TRASH_PATH_MARKER)
        }
    }

    private fun describe(name: String, error: Throwable): String =
        "$name: ${error.message ?: error::class.java.simpleName}"

    private companion object {

        /** Recorded instead of a folder: the system trash has no stable path. */
        const val TRASH_PATH_MARKER = "<cestino di sistema>"
    }
}
