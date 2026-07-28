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

    /** A queued file operation, not yet applied. */
    sealed interface PendingAction {

        val photo: MediaStoreRepository.Photo

        /** File this photo into a destination folder. */
        data class Move(
            override val photo: MediaStoreRepository.Photo,
            val destination: DestinationRepository.Destination,
            val destinationRelativePath: String
        ) : PendingAction

        /** Hand this photo to the system trash, recoverable for 30 days. */
        data class Trash(override val photo: MediaStoreRepository.Photo) : PendingAction
    }

    private val photos = ArrayList<MediaStoreRepository.Photo>()
    private val pendingActions = ArrayList<PendingAction>()
    private var storedStates: Map<Long, PhotoStateRepository.StoredState> = emptyMap()
    private var tagAssignments: Map<Long, List<String>> = emptyMap()

    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = pendingActions.size
    val queuedActions: List<PendingAction> get() = pendingActions.toList()

    val queuedMoves: List<PendingAction.Move>
        get() = pendingActions.filterIsInstance<PendingAction.Move>()

    val queuedTrash: List<PendingAction.Trash>
        get() = pendingActions.filterIsInstance<PendingAction.Trash>()

    /** Replaces the working set. Any queued action is discarded. */
    fun load(
        loaded: List<MediaStoreRepository.Photo>,
        states: Map<Long, PhotoStateRepository.StoredState>,
        tags: Map<Long, List<String>>
    ) {
        photos.clear()
        photos.addAll(loaded)
        pendingActions.clear()
        storedStates = states
        tagAssignments = tags
        currentIndex = 0
    }

    fun current(): MediaStoreRepository.Photo? = photos.getOrNull(currentIndex)

    fun currentStatus(): ReviewStatus? = current()?.let { storedStates[it.mediaId]?.status }

    fun currentDestinationId(): Long? = current()?.let { storedStates[it.mediaId]?.destinationId }

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
        return stateRepository.record(photo, ReviewStatus.KEPT, null)
            .onSuccess { rememberState(photo.mediaId, ReviewStatus.KEPT, null); goNext() }
    }

    /** Queues the current photo for the system trash and advances. */
    fun trashCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return stateRepository.record(photo, ReviewStatus.TRASHED, null).onSuccess {
            rememberState(photo.mediaId, ReviewStatus.TRASHED, null)
            pendingActions.add(PendingAction.Trash(photo))
            goNext()
        }
    }

    /**
     * Queues the current photo for [destination], appending the capture year
     * when that destination asks for it, and advances.
     */
    fun fileCurrent(destination: DestinationRepository.Destination): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        val path = destination.pathFor(photo.dateTakenMillis)
        return stateRepository.record(photo, ReviewStatus.CATEGORIZED, destination.id).onSuccess {
            rememberState(photo.mediaId, ReviewStatus.CATEGORIZED, destination.id)
            pendingActions.add(PendingAction.Move(photo, destination, path))
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
     * Undoes the last queued action: removes it, forgets the decision, and
     * returns to that photo. Tags are left alone, since tagging is not queued.
     */
    fun undoLastAction(): Result<Unit> {
        if (pendingActions.isEmpty()) return Result.failure(IllegalStateException("Coda vuota"))
        val undone = pendingActions.removeAt(pendingActions.lastIndex)
        return stateRepository.forget(undone.photo.mediaId).onSuccess {
            storedStates = storedStates - undone.photo.mediaId
            val position = photos.indexOfFirst { it.mediaId == undone.photo.mediaId }
            if (position >= 0) currentIndex = position
        }
    }

    /** Drops applied actions from the queue, keeping the ones that failed. */
    fun retainFailedActions(failed: List<PendingAction>) {
        pendingActions.clear()
        pendingActions.addAll(failed)
    }

    private fun rememberState(mediaId: Long, status: ReviewStatus, destinationId: Long?) {
        storedStates = storedStates +
                (mediaId to PhotoStateRepository.StoredState(mediaId, status, destinationId))
    }
}
