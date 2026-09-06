package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
import android.net.Uri
import it.threarth.fotosistemis.core.data.PhotoInventory
import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.model.ReviewStatus
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
        val copiedOriginals: List<Uri> = emptyList(),

        /**
         * Photos the platform will not let us move, decided against.
         *
         * They cannot go into the app's own bin without being duplicated,
         * so they are offered to Android's, which is a different thing with
         * a different rule: it is not ours, and it empties itself after
         * thirty days. Handing them over needs the user's consent, and
         * saying which bin it is needs saying plainly.
         */
        val forSystemBin: List<ReviewSession.PendingMove> = emptyList()
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
        val toSystemBin = ArrayList<ReviewSession.PendingMove>()
        for (move in moves) {
            // A photo the platform will not let us move, decided against:
            // copying it into our own bin would leave the picture on the
            // phone twice, and the original is the one that takes the room.
            // Android's own bin is the only place it can go, so it is handed
            // over — and the user is told it is a different bin, with a
            // different rule, that empties itself.
            if (PhotoSource.isImmovable(move.photo.relativePath) &&
                move.status == ReviewStatus.TRASHED
            ) {
                toSystemBin.add(move)
                continue
            }

            // Where the platform forbids a move and the photo is being
            // filed, copy: the boundary is not crossed, a new file is
            // simply written on this side of it.
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
                    val name = move.newDisplayName ?: move.photo.displayName
                    stateRepository.recordMovedPath(
                        move.photo.photoId, move.destinationRelativePath, name
                    )
                    // Where the photo is, not only where it has been: without
                    // this the inventory keeps the old folder until the next
                    // full scan, and every screen reading it says the photo
                    // is still where it no longer is.
                    inventory.recordRelocation(
                        move.photo.photoId, move.destinationRelativePath, name
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
            copiedOriginals = originals,
            forSystemBin = toSystemBin
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
            .mapCatching { copy ->
                val copyId = inventory.recordCopy(
                    move.photo.photoId, copy.mediaId, move.destinationRelativePath, name
                ).getOrThrow()

                // A copy whose date could not be written will sit in the
                // gallery under the day it was copied. Saying so is better
                // than letting the user find it there by accident.
                if (!copy.captureDateWritten) inventory.markDateSuspect(copyId, true)

                stateRepository.recordMovedPath(
                    move.photo.photoId, move.destinationRelativePath, name
                ).getOrThrow()
            }
    }

    private fun describe(move: ReviewSession.PendingMove, error: Throwable): String =
        "${move.photo.displayName}: ${error.message ?: error::class.java.simpleName}"
}
