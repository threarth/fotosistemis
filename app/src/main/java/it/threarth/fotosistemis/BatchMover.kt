package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
import android.net.Uri
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.port.PhotoSource
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
    private val stateRepository: PhotoStateRepository,
    private val inventory: PhotoInventory
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
        val firstError: String?,

        /**
         * Originals of photos that were copied instead of moved.
         *
         * They still exist, holding the same picture twice. Removing them is
         * a deletion, which is the user's to allow, so they are handed back
         * rather than dealt with here.
         */
        val copiedOriginals: List<Uri> = emptyList()
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
        val originals = ArrayList<Uri>()
        for (move in moves) {
            // Where the platform forbids a move, copy: the boundary is not
            // crossed, a new file is simply written on this side of it.
            if (PhotoSource.isImmovable(move.photo.relativePath)) {
                copyOne(move).fold(
                    onSuccess = { succeeded++; originals.add(photoSource.uriFor(move.photo)) },
                    onFailure = { error ->
                        failed.add(move)
                        if (firstError == null) firstError = describe(move, error)
                    }
                )
                continue
            }

            photoSource.move(
                move.photo,
                move.destinationRelativePath,
                move.newDisplayName
            ).fold(
                onSuccess = {
                    succeeded++
                    stateRepository.recordMovedPath(
                        move.photo.photoId,
                        move.destinationRelativePath,
                        move.newDisplayName ?: move.photo.displayName
                    )
                },
                onFailure = { error ->
                    failed.add(move)
                    if (firstError == null) firstError = describe(move, error)
                }
            )
        }

        return BatchResult(
            requested = moves.size,
            succeeded = succeeded,
            failed = failed,
            totalMillis = System.currentTimeMillis() - startedAt,
            firstError = firstError,
            copiedOriginals = originals
        )
    }

    /**
     * Copies one photo and points our record at the copy.
     *
     * The record has to follow before the original is deleted, or everything
     * decided about the photo would be left attached to a file about to
     * disappear.
     */
    private fun copyOne(move: ReviewSession.PendingMove): Result<Unit> {
        val name = move.newDisplayName ?: move.photo.displayName
        return photoSource.copyInto(move.photo, move.destinationRelativePath, name)
            .mapCatching { newMediaId ->
                inventory.rekeyToCopy(
                    move.photo.photoId, newMediaId, move.destinationRelativePath, name
                ).getOrThrow()
                stateRepository.recordMovedPath(
                    move.photo.photoId, move.destinationRelativePath, name
                ).getOrThrow()
            }
    }

    private fun describe(move: ReviewSession.PendingMove, error: Throwable): String =
        "${move.photo.displayName}: ${error.message ?: error::class.java.simpleName}"
}
