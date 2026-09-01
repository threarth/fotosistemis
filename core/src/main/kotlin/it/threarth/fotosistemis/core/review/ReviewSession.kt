package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.TagRepository
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.ReviewStatus

/**
 * Navigation and pending work for one pass over a filtered set of photos.
 *
 * Holds no Android types on purpose: ordering, decisions and the queue live
 * here, so the Activity is left with nothing but wiring.
 *
 * Decisions are written the moment the user makes them, while file operations
 * are queued and applied later in one batch. The two are deliberately
 * separate: a decision is what the user meant, and must survive the app being
 * closed before anything is moved.
 */
class ReviewSession(
    private val stateRepository: PhotoStateRepository,
    private val tagRepository: TagRepository,
    private val yearFolderPattern: () -> String
) {

    companion object {

        /**
         * Where photos wait to be deleted.
         *
         * Deleting locally would leave the backed-up copy in Google Photos
         * untouched, and no public API can remove it: the Photos API only
         * reaches content an app created itself. So the app does not delete.
         * It gathers the candidates into one folder, which appears under
         * Library / Device folders in Google Photos, where a single
         * select-all and delete removes both the cloud copy and the local
         * file.
         *
         * No .nomedia file here on purpose: hiding the folder from Google
         * Photos would defeat its whole purpose.
         */
        const val DELETION_STAGING_PATH = "Pictures/_FotoSistemis_DaEliminare/"
    }

    /**
     * A queued file move, not yet applied.
     *
     * Trashing and filing are the same operation with different
     * destinations, which is why one consent dialog now covers a mixed batch.
     */
    data class PendingMove(
        val photo: PhotoRecord,
        val destinationRelativePath: String,
        val status: ReviewStatus,
        val destinationId: Long?,

        /** The name to give the file, or null to keep the one it has. */
        val newDisplayName: String? = null
    )

    private val photos = ArrayList<PhotoRecord>()
    private val pendingMoves = ArrayList<PendingMove>()
    private var storedStates: Map<Long, PhotoStateRepository.StoredState> = emptyMap()
    private var tagAssignments: Map<Long, List<String>> = emptyMap()
    private var originalPaths: Map<Long, PhotoStateRepository.Location> = emptyMap()


    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = pendingMoves.size
    val queuedMoves: List<PendingMove> get() = pendingMoves.toList()

    /** Replaces the working set. Any queued move is discarded. */
    fun load(
        loaded: List<PhotoRecord>,
        states: Map<Long, PhotoStateRepository.StoredState>,
        tags: Map<Long, List<String>>,
        origins: Map<Long, PhotoStateRepository.Location>
    ) {
        photos.clear()
        photos.addAll(loaded)
        pendingMoves.clear()
        storedStates = states
        tagAssignments = tags
        originalPaths = origins

        // Resume where the reviewing stopped: the first photo with no
        // decision recorded. Starting from the beginning would mean
        // scrolling past everything already done to reach the work left.
        val firstUndecided = loaded.indexOfFirst { states[it.photoId] == null }
        currentIndex = if (firstUndecided >= 0) firstUndecided else 0
    }

    fun current(): PhotoRecord? = photos.getOrNull(currentIndex)

    /** Photo [offset] places away, used to show where a drag is heading. */
    fun peek(offset: Int): PhotoRecord? = photos.getOrNull(currentIndex + offset)

    fun currentStatus(): ReviewStatus? = current()?.let { storedStates[it.photoId]?.status }

    /** Destination the current photo was filed into, if any. */
    fun currentDestinationId(): Long? = current()?.let { storedStates[it.photoId]?.destinationId }

    fun currentTags(): List<String> = current()?.let { tagAssignments[it.photoId] } ?: emptyList()

    fun canGoNext(): Boolean = currentIndex < photos.size - 1

    fun canGoPrevious(): Boolean = currentIndex > 0

    /** Pure navigation: looking at a photo is not a decision about it. */
    fun goNext(): Boolean {
        if (!canGoNext()) return false
        currentIndex++
        return true
    }

    fun goPrevious(): Boolean {
        if (!canGoPrevious()) return false
        currentIndex--
        return true
    }

    /**
     * Marks the current photo as reviewed and left in place, then advances.
     * No file is touched, so nothing is queued.
     */
    fun keepCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return stateRepository.record(photo, ReviewStatus.KEPT, null).onSuccess {
            // Keeping a photo revokes any move queued for it earlier: the
            // last decision is the one that counts, and leaving the old
            // entry would move a photo the user has since chosen to keep.
            dequeue(photo.photoId)
            rememberState(photo.photoId, ReviewStatus.KEPT, null)
            goNext()
        }
    }

    /** Queues the current photo for the deletion staging folder and advances. */
    fun trashCurrent(): Result<Unit> =
        queueMove(ReviewStatus.TRASHED, DELETION_STAGING_PATH, null)

    /**
     * Queues the current photo for [destination], appending the capture year
     * when that destination asks for it, and advances.
     */
    fun fileCurrent(destination: Destination): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return queueMove(
            ReviewStatus.CATEGORIZED,
            destination.pathFor(photo.dateTakenMillis, yearFolderPattern()),
            destination.id
        )
    }

    /** Shared path for every action that moves a file. */
    private fun queueMove(
        status: ReviewStatus,
        destinationRelativePath: String,
        destinationId: Long?
    ): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return stateRepository.record(photo, status, destinationId).onSuccess {
            // One photo, one destination: changing mind replaces the queued
            // move instead of adding a second, contradictory one.
            dequeue(photo.photoId)
            rememberState(photo.photoId, status, destinationId)
            pendingMoves.add(PendingMove(photo, destinationRelativePath, status, destinationId))
            goNext()
        }
    }

    /**
     * Where the current photo would go back to, or null when the app never
     * saw it anywhere else. Also null when it is already there.
     */
    fun currentRestorePath(): String? {
        val origin = originOf(current() ?: return null) ?: return null
        return origin.relativePath + (origin.displayName ?: "")
    }

    /** How many loaded photos could be put back where they came from. */
    fun restorableCount(): Int = photos.count { originOf(it) != null }

    /**
     * Builds the list of moves that would put photos back, without touching
     * the pending queue.
     *
     * Restoring deliberately bypasses the queue. The queue exists so that
     * filing and deleting can be reviewed before they happen; a restore is
     * itself the correction of an earlier decision, and making the user
     * queue and then apply a correction turns one idea into two steps.
     */
    fun buildRestorePlan(onlyCurrent: Boolean): List<PendingMove> {
        val candidates = if (onlyCurrent) listOfNotNull(current()) else photos
        return candidates.mapNotNull { photo ->
            val origin = originOf(photo) ?: return@mapNotNull null
            PendingMove(
                photo,
                origin.relativePath,
                ReviewStatus.KEPT,
                null,
                origin.displayName
            )
        }
    }

    /**
     * Records the outcome of restores that have already been applied.
     * A restored photo counts as reviewed and left alone, so its status
     * becomes KEPT.
     */
    fun commitRestores(applied: List<PendingMove>): Result<Unit> {
        for (move in applied) {
            val outcome = stateRepository.record(move.photo, ReviewStatus.KEPT, null)
            if (outcome.isFailure) return outcome
            rememberState(move.photo.photoId, ReviewStatus.KEPT, null)
        }
        return Result.success(Unit)
    }

    /**
     * Where [photo] came from, or null when there is nothing to undo.
     *
     * Both folder and name have to match for a photo to count as already
     * home: a photo moved back but still carrying a stamped name has not
     * been restored yet. A history row written before v6 has no name, and
     * then only the folder can be compared.
     */
    private fun originOf(photo: PhotoRecord): PhotoStateRepository.Location? {
        val origin = originalPaths[photo.photoId] ?: return null
        val homeFolder = origin.relativePath == photo.relativePath
        val homeName = origin.displayName == null || origin.displayName == photo.displayName

        return if (homeFolder && homeName) null else origin
    }

    /** Removes any queued move for [mediaId]. */
    private fun dequeue(mediaId: Long) {
        pendingMoves.removeAll { it.photo.photoId == mediaId }
    }

    /** Attaches a tag to the current photo without moving anything. */
    fun tagCurrent(rawName: String): Result<String> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return tagRepository.assign(photo.photoId, rawName).onSuccess { name ->
            val existing = tagAssignments[photo.photoId].orEmpty()
            if (name !in existing) {
                tagAssignments = tagAssignments + (photo.photoId to (existing + name).sorted())
            }
        }
    }

    /** Detaches a tag from the current photo. */
    fun untagCurrent(name: String): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return tagRepository.unassign(photo.photoId, name).onSuccess {
            tagAssignments = tagAssignments +
                    (photo.photoId to tagAssignments[photo.photoId].orEmpty().filterNot { it == name })
        }
    }

    /**
     * Undoes the last queued move: removes it, forgets the decision, and
     * returns to that photo. Tags are left alone, since tagging is not queued.
     */
    fun undoLastMove(): Result<Unit> {
        if (pendingMoves.isEmpty()) return Result.failure(IllegalStateException("Coda vuota"))
        val undone = pendingMoves.removeAt(pendingMoves.lastIndex)
        return stateRepository.forget(undone.photo.photoId).onSuccess {
            storedStates = storedStates - undone.photo.photoId
            val position = photos.indexOfFirst { it.photoId == undone.photo.photoId }
            if (position >= 0) currentIndex = position
        }
    }

    /**
     * Throws away the queued moves and the decisions that produced them.
     *
     * Only those: a photo merely marked as kept has nothing queued, its
     * record is already complete and correct, and there is nothing to undo.
     * The photos that were going to move go back to never seen, because
     * leaving them recorded as filed while their files never moved would be
     * a state the app could never make true.
     */
    fun discardQueue(): Result<Unit> {
        for (move in pendingMoves) {
            val outcome = stateRepository.forget(move.photo.photoId)
            if (outcome.isFailure) return outcome
            storedStates = storedStates - move.photo.photoId
        }
        pendingMoves.clear()
        return Result.success(Unit)
    }

    /** Drops applied moves from the queue, keeping the ones that failed. */
    fun retainFailedMoves(failed: List<PendingMove>) {
        pendingMoves.clear()
        pendingMoves.addAll(failed)
    }

    private fun rememberState(mediaId: Long, status: ReviewStatus, destinationId: Long?) {
        storedStates = storedStates +
                (mediaId to PhotoStateRepository.StoredState(mediaId, status, destinationId))
    }
}
