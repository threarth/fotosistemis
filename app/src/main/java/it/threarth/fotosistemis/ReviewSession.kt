package it.threarth.fotosistemis

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
    private val tagRepository: TagRepository
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
        val photo: MediaStoreRepository.Photo,
        val destinationRelativePath: String,
        val status: ReviewStatus,
        val destinationId: Long?
    )

    private val photos = ArrayList<MediaStoreRepository.Photo>()
    private val pendingMoves = ArrayList<PendingMove>()
    private var storedStates: Map<Long, PhotoStateRepository.StoredState> = emptyMap()
    private var tagAssignments: Map<Long, List<String>> = emptyMap()

    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = pendingMoves.size
    val queuedMoves: List<PendingMove> get() = pendingMoves.toList()

    /** Replaces the working set. Any queued move is discarded. */
    fun load(
        loaded: List<MediaStoreRepository.Photo>,
        states: Map<Long, PhotoStateRepository.StoredState>,
        tags: Map<Long, List<String>>
    ) {
        photos.clear()
        photos.addAll(loaded)
        pendingMoves.clear()
        storedStates = states
        tagAssignments = tags
        currentIndex = 0
    }

    fun current(): MediaStoreRepository.Photo? = photos.getOrNull(currentIndex)

    fun currentStatus(): ReviewStatus? = current()?.let { storedStates[it.mediaId]?.status }

    fun currentTags(): List<String> = current()?.let { tagAssignments[it.mediaId] } ?: emptyList()

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
            rememberState(photo.mediaId, ReviewStatus.KEPT, null)
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
    fun fileCurrent(destination: DestinationRepository.Destination): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return queueMove(
            ReviewStatus.CATEGORIZED,
            destination.pathFor(photo.dateTakenMillis),
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
            rememberState(photo.mediaId, status, destinationId)
            pendingMoves.add(PendingMove(photo, destinationRelativePath, status, destinationId))
            goNext()
        }
    }

    /** Attaches a tag to the current photo without moving anything. */
    fun tagCurrent(rawName: String): Result<String> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return tagRepository.assign(photo.mediaId, rawName).onSuccess { name ->
            val existing = tagAssignments[photo.mediaId].orEmpty()
            if (name !in existing) {
                tagAssignments = tagAssignments + (photo.mediaId to (existing + name).sorted())
            }
        }
    }

    /** Detaches a tag from the current photo. */
    fun untagCurrent(name: String): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return tagRepository.unassign(photo.mediaId, name).onSuccess {
            tagAssignments = tagAssignments +
                    (photo.mediaId to tagAssignments[photo.mediaId].orEmpty().filterNot { it == name })
        }
    }

    /**
     * Undoes the last queued move: removes it, forgets the decision, and
     * returns to that photo. Tags are left alone, since tagging is not queued.
     */
    fun undoLastMove(): Result<Unit> {
        if (pendingMoves.isEmpty()) return Result.failure(IllegalStateException("Coda vuota"))
        val undone = pendingMoves.removeAt(pendingMoves.lastIndex)
        return stateRepository.forget(undone.photo.mediaId).onSuccess {
            storedStates = storedStates - undone.photo.mediaId
            val position = photos.indexOfFirst { it.mediaId == undone.photo.mediaId }
            if (position >= 0) currentIndex = position
        }
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
