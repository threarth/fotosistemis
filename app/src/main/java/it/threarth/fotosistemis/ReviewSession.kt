package it.threarth.fotosistemis

import java.util.Locale

/**
 * Navigation and pending work for one pass over a filtered set of photos.
 *
 * Holds no Android types on purpose: all decisions and ordering live here, so
 * the Activity is left with nothing but wiring views to calls.
 *
 * Decisions are recorded the moment the user makes them, while file moves are
 * queued and applied later in one batch. The two are deliberately separate: a
 * decision is what the user meant, and it must survive even if the app is
 * closed before the moves are applied.
 */
class ReviewSession(private val stateRepository: PhotoStateRepository) {

    /** A queued file move, not yet applied. */
    data class PendingMove(
        val photo: MediaStoreRepository.Photo,
        val destinationRelativePath: String,
        val status: ReviewStatus,
        val tag: String?
    )

    private companion object {

        /** Parent of every folder this app creates. */
        const val MANAGED_PARENT = "DCIM"

        /** Longest tag accepted, after normalisation. */
        const val MAX_TAG_LENGTH = 60
    }

    private val photos = ArrayList<MediaStoreRepository.Photo>()
    private val pendingMoves = ArrayList<PendingMove>()
    private var storedStates: Map<Long, PhotoStateRepository.StoredState> = emptyMap()

    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = pendingMoves.size
    val queuedMoves: List<PendingMove> get() = pendingMoves.toList()

    /** Replaces the working set. Any queued move is discarded. */
    fun load(
        loaded: List<MediaStoreRepository.Photo>,
        states: Map<Long, PhotoStateRepository.StoredState>
    ) {
        photos.clear()
        photos.addAll(loaded)
        pendingMoves.clear()
        storedStates = states
        currentIndex = 0
    }

    fun current(): MediaStoreRepository.Photo? = photos.getOrNull(currentIndex)

    /** Recorded decision for the current photo, or null when never reviewed. */
    fun currentStatus(): ReviewStatus? = current()?.let { storedStates[it.mediaId]?.status }

    /** Tag already assigned to the current photo, if any. */
    fun currentTag(): String? = current()?.let { storedStates[it.mediaId]?.tag }

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
        val photo = current() ?: return Result.failure(IllegalStateException("No current photo"))
        return record(photo, ReviewStatus.KEPT, null).onSuccess { goNext() }
    }

    /** Queues the current photo for the trash folder and advances. */
    fun trashCurrent(): Result<Unit> =
        queueCurrent(ReviewStatus.TRASHED, null, "$MANAGED_PARENT/${MediaStoreRepository.TRASH_FOLDER}/")

    /** Queues the current photo for the folder of [tag] and advances. */
    fun categorizeCurrent(tag: String): Result<Unit> {
        val folder = archiveFolderName(tag)
            ?: return Result.failure(IllegalArgumentException("Tag non valido: $tag"))
        return queueCurrent(ReviewStatus.CATEGORIZED, tag, "$MANAGED_PARENT/$folder/")
    }

    /** Shared path for the two actions that move a file. */
    private fun queueCurrent(
        status: ReviewStatus,
        tag: String?,
        destination: String
    ): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("No current photo"))
        return record(photo, status, tag).onSuccess {
            pendingMoves.add(PendingMove(photo, destination, status, tag))
            goNext()
        }
    }

    /**
     * Undoes the last queued move: removes it from the queue, forgets the
     * decision, and returns to that photo.
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

    /** Writes one decision and keeps the in-memory view consistent with it. */
    private fun record(
        photo: MediaStoreRepository.Photo,
        status: ReviewStatus,
        tag: String?
    ): Result<Unit> = stateRepository.record(photo, status, tag).onSuccess {
        storedStates = storedStates +
                (photo.mediaId to PhotoStateRepository.StoredState(photo.mediaId, status, tag))
    }

    /**
     * Turns a free-text tag into a safe folder name: famiglia -> arch_famiglia.
     * Returns null when nothing usable survives normalisation.
     */
    private fun archiveFolderName(tag: String): String? {
        val normalized = tag.trim().lowercase(Locale.ITALY)
            .replace(Regex("[^a-z0-9._-]+"), "_")
            .trim('_', '.', '-')
            .take(MAX_TAG_LENGTH)
        if (normalized.isEmpty()) return null
        return "${MediaStoreRepository.ARCHIVE_PREFIX}$normalized"
    }
}
