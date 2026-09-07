package it.threarth.fotosistemis

import android.content.Context
import android.content.IntentSender
import android.provider.MediaStore
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
         * Photos that had to be copied, whose originals are still there.
         *
         * A copy leaves the picture on the phone twice, and the original is
         * the one taking the room. It is not destroyed and never will be by
         * this app: it is offered to Android's bin, which keeps it for
         * thirty days, and only with the user agreeing each time.
         */
        val copiedOriginals: List<ReviewSession.PendingMove> = emptyList(),

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
        val originals = ArrayList<ReviewSession.PendingMove>()
        val toSystemBin = ArrayList<ReviewSession.PendingMove>()

        val startedAt = System.currentTimeMillis()
        for (move in moves) {
            when (val outcome = applyOne(move)) {
                Outcome.MOVED -> succeeded++
                Outcome.COPIED -> {
                    succeeded++
                    originals.add(move)
                }
                Outcome.FOR_SYSTEM_BIN -> toSystemBin.add(move)
                is Outcome.Failed -> {
                    failed.add(move)
                    if (firstError == null) firstError = describe(move, outcome.error)
                }
            }
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

    /** What became of one move. */
    private sealed interface Outcome {
        data object MOVED : Outcome
        data object COPIED : Outcome
        data object FOR_SYSTEM_BIN : Outcome
        data class Failed(val error: Throwable) : Outcome
    }

    /**
     * Carries out one move the way the photo's folder allows.
     *
     * A photo the platform will not let us move, decided against: copying
     * it into our own bin would leave the picture on the phone twice, and
     * the original is the one that takes the room. Android's own bin is the
     * only place it can go, so it is handed over — and the user is told it
     * is a different bin, with a different rule, that empties itself.
     *
     * Where the platform forbids a move and the photo is being filed, copy:
     * the boundary is not crossed, a new file is simply written on this
     * side of it.
     */
    private fun applyOne(move: ReviewSession.PendingMove): Outcome {
        val immovable = PhotoSource.isImmovable(move.photo.relativePath)
        if (immovable && move.status == ReviewStatus.TRASHED) return Outcome.FOR_SYSTEM_BIN

        return if (immovable) {
            copyOne(move).fold({ Outcome.COPIED }, { Outcome.Failed(it) })
        } else {
            moveOne(move).fold({ Outcome.MOVED }, { Outcome.Failed(it) })
        }
    }

    /**
     * Moves one photo and writes down that it went.
     *
     * One transaction for the three facts this produces: where the photo
     * now is, that it went there, and that the work is no longer owed.
     * Written apart, a death between them leaves a photo called filed whose
     * folder was never updated. And if the writing fails, the move counts
     * as failed even though the file went: the archive still owes it, and
     * will offer it again, which is the truth.
     */
    private fun moveOne(move: ReviewSession.PendingMove): Result<Unit> {
        val name = move.newDisplayName ?: move.photo.displayName
        return photoSource.move(move.photo, move.destinationRelativePath, name)
            .mapCatching {
                stateRepository.markCarriedOut(
                    move.photo.photoId, move.destinationRelativePath, name
                ).getOrThrow()
            }
    }

    /**
     * Copies one photo and records the copy as the photograph now.
     *
     * The copy gets its own row, carrying the decision from the start; the
     * original keeps its own row and is marked done without having moved,
     * since the inventory must not be told it went anywhere.
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

                stateRepository.markCarriedOut(
                    move.photo.photoId, move.destinationRelativePath, name,
                    relocated = false
                ).getOrThrow()
            }
    }

    private fun describe(move: ReviewSession.PendingMove, error: Throwable): String =
        "${move.photo.displayName}: ${error.message ?: error::class.java.simpleName}"
}
