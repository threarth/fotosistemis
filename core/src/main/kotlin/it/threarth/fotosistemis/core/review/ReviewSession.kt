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
 * Decisions are written the moment the user makes them, marked as still
 * owed, while the file operations that carry them out are applied later in
 * one batch. The queue held here is a view of what the database owes for
 * the photos in hand, never a second copy of it: it is rebuilt from the
 * database at every load, and a decision survives the app being closed
 * because it was never only in memory.
 */
class ReviewSession(
    private val stateRepository: PhotoStateRepository,
    private val tagRepository: TagRepository,
    private val yearFolderPattern: () -> String
) {

    companion object {

        /**
         * The app's own bin: where a photo goes when it is decided against.
         *
         * Deleting locally would leave the backed-up copy in Google Photos
         * untouched, and no public API can remove it: the Photos API only
         * reaches content an app created itself. So the app does not
         * delete. It gathers the candidates into one folder, which appears
         * under Library / Device folders in Google Photos, where a single
         * select-all and delete removes both the cloud copy and the local
         * file. No .nomedia file here on purpose: hiding the folder from
         * Google Photos would defeat its whole purpose.
         *
         * A special folder, and the rules the rest of the archive lives by
         * do not apply inside it. Written down because each exemption is
         * deliberate and each would look like a bug to someone reading only
         * the general rule:
         *
         * - **No stamp.** Photos filed into a category are renamed with a
         *   `__date__` prefix so a flat folder sorts by time. Nothing in the
         *   bin is renamed: the name it arrived with is the name it must go
         *   back with, and a photo about to be destroyed has no use for
         *   tidy sorting.
         * - **No state filter.** Everywhere else the app shows what has not
         *   been decided yet. Everything here has been decided, by
         *   definition, so filtering that way would show an empty bin over a
         *   full one.
         * - **Not a source.** It is excluded from the folders reviewed and
         *   from what the recogniser reads, or photos thrown away would come
         *   back offered as though they were sorted.
         * - **Not a category.** It holds no year folders and takes no
         *   destination: the one act available inside it is putting a photo
         *   back where it came from.
         * - **Nothing here is destroyed.** Emptying it is the user's doing,
         *   from the gallery. The app only ever moves photos in and out.
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

    /**
     * What the database owes for the photos in scope, plus what this
     * session has decided since the last load.
     *
     * The scope is the folders and period chosen, not the photos on screen:
     * the state filter hides what has been decided, and the work just
     * decided is exactly what "apply" is for.
     */
    private val pendingMoves = ArrayList<PendingMove>()

    /**
     * Photos decided in this session, in the order they were decided.
     *
     * Undo takes from here, never from the queue: after a load the queue
     * holds decisions taken days ago in database order, and "undo the last
     * one" must mean the last one the user took, not whichever photo the
     * database listed last.
     */
    private val decidedHere = ArrayList<Long>()
    private var storedStates: Map<Long, PhotoStateRepository.StoredState> = emptyMap()
    private var tagAssignments: Map<Long, List<String>> = emptyMap()
    private var originalPaths: Map<Long, PhotoStateRepository.Location> = emptyMap()

    /**
     * The categories, for rebuilding a queued move's destination.
     *
     * Where a photo is bound is recomputed rather than stored: if the
     * category is pointed at a different folder between deciding and
     * applying, the photo should go where the category points now.
     */
    var destinationsById: (Long?) -> Destination? = { null }


    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = pendingMoves.size
    val queuedMoves: List<PendingMove> get() = pendingMoves.toList()

    /** True while there is a decision of this session to take back. */
    val canUndo: Boolean get() = decidedHere.isNotEmpty()

    /**
     * Takes a fresh list of photos and rebuilds the queue from the database.
     *
     * [loaded] is what the user leafs through; [scope] is every photo the
     * chosen folders and period contain, the state filter left aside. The
     * queue is rebuilt for the scope: with the filter on "not yet seen" the
     * photos just decided are no longer on screen, and a queue drawn from
     * the screen alone would say there is nothing to apply right after an
     * afternoon of work.
     *
     * Rebuilt, not kept. The queue used to survive a load and diverge from
     * the database — a move applied from the queue screen stayed here as
     * still to do, and applying it again would have copied a photograph
     * twice. What the database owes is the one truth, and every load reads
     * it afresh. Nothing is lost by that: a decision is written before it
     * is queued, so it is there to be read back.
     */
    fun load(
        loaded: List<PhotoRecord>,
        states: Map<Long, PhotoStateRepository.StoredState>,
        tags: Map<Long, List<String>>,
        origins: Map<Long, PhotoStateRepository.Location>,
        scope: List<PhotoRecord> = loaded
    ) {
        photos.clear()
        photos.addAll(loaded)
        storedStates = states
        tagAssignments = tags
        // Before the queue: a restore still owed is planned from here.
        originalPaths = origins

        pendingMoves.clear()
        decidedHere.clear()
        rebuildQueueFromSpool(scope, states)

        // Resume where the reviewing stopped: the first photo with no
        // decision recorded. Starting from the beginning would mean
        // scrolling past everything already done to reach the work left.
        val firstUndecided = loaded.indexOfFirst { states[it.photoId] == null }
        currentIndex = if (firstUndecided >= 0) firstUndecided else 0
    }

    fun current(): PhotoRecord? = photos.getOrNull(currentIndex)

    /** Everything this session is leafing through, in order. */
    fun loadedPhotos(): List<PhotoRecord> = photos.toList()

    /** Photo [offset] places away, used to show where a drag is heading. */
    fun peek(offset: Int): PhotoRecord? = photos.getOrNull(currentIndex + offset)

    fun currentStatus(): ReviewStatus? = current()?.let { storedStates[it.photoId]?.status }

    /**
     * What has been decided about [photoId], as far as this session knows.
     *
     * The session already keeps every decision it has taken, so a screen
     * showing how much of a month is done can ask here instead of going
     * back to the database after every swipe.
     */
    fun statusOf(photoId: Long): ReviewStatus? = storedStates[photoId]?.status

    /** Destination the current photo was filed into, if any. */
    fun currentDestinationId(): Long? = current()?.let { storedStates[it.photoId]?.destinationId }

    fun currentTags(): List<String> = current()?.let { tagAssignments[it.photoId] } ?: emptyList()

    fun canGoNext(): Boolean = currentIndex < photos.size - 1

    fun canGoPrevious(): Boolean = currentIndex > 0

    /**
     * True when moving on leads somewhere, the wrap included.
     *
     * Kept apart from [canGoNext], which answers the narrower question of
     * whether a photo follows this one. The gesture needs the wider one: at
     * the last photo there is no next, and yet a swipe still goes somewhere.
     */
    fun canLeafForward(): Boolean = photos.size > 1

    /**
     * Puts into the queue the decisions the database still owes for
     * [scope].
     *
     * Only for photos in hand: a move needs the record it is about, and one
     * outside this scope is applied from its own folder and period, or from
     * the queue screen, which reads everything owed.
     */
    private fun rebuildQueueFromSpool(
        scope: List<PhotoRecord>,
        states: Map<Long, PhotoStateRepository.StoredState>
    ) {
        for (photo in scope) {
            val state = states[photo.photoId] ?: continue
            if (!state.pending) continue

            val move = MovePlanner.forOwed(
                photo,
                state.status,
                destinationsById(state.destinationId),
                yearFolderPattern(),
                originalPaths[photo.photoId]
            ) ?: continue
            pendingMoves.add(move)
        }
    }

    /**
     * Moves on, and past the last photo comes the first again.
     *
     * Pure navigation: looking at a photo is not a decision about it.
     *
     * A list that stops dead at the end leaves the reader stranded: the
     * photos that were skipped rather than decided are behind them, and
     * getting back to those meant swiping the other way as many times as
     * they had come. Wrapping round is how leafing through a stack works,
     * and it costs nothing when there is only one photo — there is nowhere
     * else to go anyway.
     */
    fun goNext(): Boolean {
        if (photos.isEmpty()) return false
        if (canGoNext()) {
            currentIndex++
            return true
        }
        if (photos.size == 1) return false

        currentIndex = 0
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
        // Nothing to move, so nothing is owed: done as it is taken.
        return stateRepository.record(photo, ReviewStatus.KEPT, null, pending = false)
            .onSuccess {
                // Keeping a photo revokes any move queued for it earlier: the
                // last decision is the one that counts, and leaving the old
                // entry would move a photo the user has since chosen to keep.
                dequeue(photo.photoId)
                rememberState(photo.photoId, ReviewStatus.KEPT, null, pending = false)
                noteDecided(photo.photoId)
                goNext()
            }
    }

    /** Queues the current photo for the app's bin and advances. */
    fun trashCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return queueMove(MovePlanner.toBin(photo))
    }

    /**
     * Queues the current photo to go back where it came from, and advances.
     *
     * Restoring used to bypass the queue, on the reasoning that a correction
     * of an earlier decision should not itself need two steps. Working in
     * the bin showed the opposite: there, restoring is not a correction but
     * the ordinary act, done to one photo after another while leafing
     * through. Every other act there is reviewed before it happens, and this
     * one moves files just as they do.
     *
     * The original name goes back with the folder. A photo returned to its
     * place still carrying a stamped name has not really been put back.
     */
    fun restoreCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        val origin = originOf(photo)
            ?: return Result.failure(IllegalStateException("Non si sa da dove venga"))

        return queueMove(MovePlanner.backHome(photo, origin))
    }

    /**
     * Queues the current photo for [destination], appending the capture year
     * when that destination asks for it, and advances.
     */
    fun fileCurrent(destination: Destination): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return queueMove(MovePlanner.toCategory(photo, destination, yearFolderPattern()))
    }

    /**
     * Shared path for every action that moves a file: the decision is
     * written as owed, then the move waits for apply.
     */
    private fun queueMove(move: PendingMove): Result<Unit> {
        val photo = move.photo
        return stateRepository.record(photo, move.status, move.destinationId).onSuccess {
            // One photo, one destination: changing mind replaces the queued
            // move instead of adding a second, contradictory one.
            dequeue(photo.photoId)
            rememberState(photo.photoId, move.status, move.destinationId, pending = true)
            pendingMoves.add(move)
            noteDecided(photo.photoId)
            goNext()
        }
    }

    /** Last decision on a photo is the one undo reaches first. */
    private fun noteDecided(photoId: Long) {
        decidedHere.remove(photoId)
        decidedHere.add(photoId)
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
     * The second of two ways to restore, and both are kept. [restoreCurrent]
     * queues, for the bin, where putting back is the ordinary act done to
     * one photo after another. This one is for the menu, anywhere else: a
     * single correction of an earlier decision, applied on the spot, since
     * queueing one correction and then applying it turns one idea into two
     * steps. The outcome is recorded by [commitRestores] once the files
     * have moved.
     */
    fun buildRestorePlan(onlyCurrent: Boolean): List<PendingMove> {
        val candidates = if (onlyCurrent) listOfNotNull(current()) else photos
        return candidates.mapNotNull { photo ->
            originOf(photo)?.let { MovePlanner.backHome(photo, it) }
        }
    }

    /**
     * Records the outcome of restores that have already been applied.
     * A restored photo counts as reviewed and left alone, so its status
     * becomes KEPT.
     */
    fun commitRestores(applied: List<PendingMove>): Result<Unit> {
        for (move in applied) {
            // Already applied when this is called: nothing left owed.
            val outcome =
                stateRepository.record(move.photo, ReviewStatus.KEPT, null, pending = false)
            if (outcome.isFailure) return outcome
            rememberState(move.photo.photoId, ReviewStatus.KEPT, null, pending = false)
        }
        return Result.success(Unit)
    }

    /** Where [photo] came from, or null when there is nothing to undo. */
    private fun originOf(photo: PhotoRecord): PhotoStateRepository.Location? {
        val origin = originalPaths[photo.photoId] ?: return null
        return if (MovePlanner.isHome(photo, origin)) null else origin
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
     * Undoes the last decision taken in this session: removes its move,
     * forgets the decision, and returns to that photo. Tags are left alone,
     * since tagging is not queued.
     *
     * Only this session's: what was decided before the last load is in the
     * queue too, but in database order, and taking it back one at a time
     * from here would undo photos the user cannot see. That is what the
     * queue screen is for.
     */
    fun undoLastMove(): Result<Unit> {
        if (decidedHere.isEmpty()) return Result.failure(IllegalStateException("Niente da annullare"))
        val photoId = decidedHere.removeAt(decidedHere.lastIndex)
        return stateRepository.forget(photoId).onSuccess {
            dequeue(photoId)
            storedStates = storedStates - photoId
            val position = photos.indexOfFirst { it.photoId == photoId }
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
        // What this session is holding, which is the work owed for the
        // folders and period in hand. Not everything owed: a button that
        // says "twelve" must not delete four hundred decided last week in
        // another folder. Taking back all of it is offered where all of it
        // is shown, which is the queue screen.
        val ids = pendingMoves.map { it.photo.photoId }
        val outcome = stateRepository.forgetAll(ids)
        if (outcome.isFailure) return Result.failure(outcome.exceptionOrNull()!!)

        for (photoId in ids) storedStates = storedStates - photoId
        pendingMoves.clear()
        decidedHere.clear()
        return Result.success(Unit)
    }

    private fun rememberState(
        mediaId: Long,
        status: ReviewStatus,
        destinationId: Long?,
        pending: Boolean
    ) {
        storedStates = storedStates +
                (mediaId to PhotoStateRepository.StoredState(mediaId, status, destinationId, pending))
    }
}
