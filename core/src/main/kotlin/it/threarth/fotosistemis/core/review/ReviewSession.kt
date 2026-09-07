package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.data.ProposalRepository
import it.threarth.fotosistemis.core.data.TagRepository
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.model.ReviewStatus

/**
 * Navigation and pending work for one pass over a filtered set of photos.
 *
 * Holds no Android types on purpose: ordering, decisions and the queue live
 * here, so the Activity is left with nothing but wiring.
 *
 * Two things are known about each photo, and kept apart (v12): what has
 * happened to it — the truth, in photo_state — and what the user has
 * asked for and is not done yet — a proposal. Asking writes a proposal
 * the moment the gesture is made; the file moves that carry proposals
 * out are applied later in one batch. The queue is nothing but the
 * proposals for the photos in hand, planned into moves: never a second
 * copy of them, and a decision survives the app being closed because it
 * was never only in memory.
 */
class ReviewSession(
    private val stateRepository: PhotoStateRepository,
    private val proposalRepository: ProposalRepository,
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
     * A file move that carries a proposal out, not yet applied.
     *
     * Trashing and filing are the same operation with different
     * destinations, which is why one consent dialog now covers a mixed batch.
     */
    data class PendingMove(
        val photo: PhotoRecord,
        val destinationRelativePath: String,
        val action: Proposal.Action,
        val destinationId: Long?,

        /** The name to give the file, or null to keep the one it has. */
        val newDisplayName: String? = null
    )

    private val photos = ArrayList<PhotoRecord>()

    /**
     * Every photo the chosen folders and period contain, the state filter
     * left aside, keyed by id.
     *
     * The scope is what the queue is drawn from, not the photos on screen:
     * the state filter hides what has been decided, and the work just
     * decided is exactly what "apply" is for.
     */
    private var scopeById: Map<Long, PhotoRecord> = emptyMap()

    /** What has happened to each photo, as far as this session knows. */
    private var truths: Map<Long, PhotoStateRepository.StoredState> = emptyMap()

    /** What is asked of each photo in scope and not done yet. */
    private var proposals: Map<Long, Proposal> = emptyMap()

    /** The proposals in scope, planned into moves; rebuilt when they change. */
    private var queue: List<PendingMove> = emptyList()

    /**
     * Photos decided in this session, in the order they were decided.
     *
     * Undo takes from here, never from the queue: after a load the queue
     * holds decisions taken days ago in database order, and "undo the last
     * one" must mean the last one the user took, not whichever photo the
     * database listed last.
     */
    private val decidedHere = ArrayList<Long>()

    /**
     * Photos this session wrote "kept" for, having found no truth about
     * them. Undo forgets that: it is the one truth a session writes on
     * its own, and the only one an undo may take back.
     */
    private val keptHere = HashSet<Long>()
    private var tagAssignments: Map<Long, List<String>> = emptyMap()
    private var originalPaths: Map<Long, PhotoStateRepository.Location> = emptyMap()

    /**
     * The categories, for planning a proposed filing's destination.
     *
     * Where a photo is bound is recomputed rather than stored: if the
     * category is pointed at a different folder between asking and
     * applying, the photo should go where the category points now.
     */
    var destinationsById: (Long?) -> Destination? = { null }

    var currentIndex: Int = 0
        private set

    val size: Int get() = photos.size
    val pendingCount: Int get() = queue.size
    val queuedMoves: List<PendingMove> get() = queue

    /** True while there is a decision of this session to take back. */
    val canUndo: Boolean get() = decidedHere.isNotEmpty()

    /**
     * Takes a fresh list of photos and rebuilds the queue from the database.
     *
     * [loaded] is what the user leafs through; [scope] is every photo the
     * chosen folders and period contain, the state filter left aside. Only
     * the proposals about photos in scope are kept: a move needs the
     * record it is about, and one outside this scope is applied from its
     * own folder and period, or from the queue screen, which reads
     * everything owed.
     *
     * Rebuilt, not kept. The queue used to survive a load and diverge from
     * the database — a move applied from the queue screen stayed here as
     * still to do, and applying it again would have copied a photograph
     * twice. What the database holds is the one truth, and every load
     * reads it afresh.
     */
    fun load(
        loaded: List<PhotoRecord>,
        states: Map<Long, PhotoStateRepository.StoredState>,
        proposed: Map<Long, Proposal>,
        tags: Map<Long, List<String>>,
        origins: Map<Long, PhotoStateRepository.Location>,
        scope: List<PhotoRecord> = loaded
    ) {
        photos.clear()
        photos.addAll(loaded)
        scopeById = scope.associateBy { it.photoId }
        truths = states
        proposals = proposed.filterKeys { it in scopeById }
        tagAssignments = tags
        originalPaths = origins
        decidedHere.clear()
        keptHere.clear()
        rebuildQueue()

        // Resume where the reviewing stopped: the first photo with nothing
        // known or asked about it. Starting from the beginning would mean
        // scrolling past everything already done to reach the work left.
        val firstUndecided = loaded.indexOfFirst { shownStatus(it.photoId) == null }
        currentIndex = if (firstUndecided >= 0) firstUndecided else 0
    }

    /** Plans every proposal in scope into the move that carries it out. */
    private fun rebuildQueue() {
        queue = proposals.values.mapNotNull { proposal ->
            val photo = scopeById[proposal.photoId] ?: return@mapNotNull null
            MovePlanner.plan(
                photo,
                proposal,
                destinationsById(proposal.destinationId),
                yearFolderPattern(),
                originalPaths[photo.photoId]
            )
        }
    }

    fun current(): PhotoRecord? = photos.getOrNull(currentIndex)

    /** Everything this session is leafing through, in order. */
    fun loadedPhotos(): List<PhotoRecord> = photos.toList()

    /** Photo [offset] places away, used to show where a drag is heading. */
    fun peek(offset: Int): PhotoRecord? = photos.getOrNull(currentIndex + offset)

    fun currentStatus(): ReviewStatus? = current()?.let { shownStatus(it.photoId) }

    /**
     * What the photo is shown as: what is asked of it, or failing that
     * what has happened to it, or nothing when it was never seen.
     *
     * The session already keeps every decision it has taken, so a screen
     * showing how much of a month is done can ask here instead of going
     * back to the database after every swipe.
     */
    fun shownStatus(photoId: Long): ReviewStatus? =
        proposals[photoId]?.shownStatus ?: truths[photoId]?.status

    /** Category the current photo is bound for, or filed in, if any. */
    fun currentDestinationId(): Long? {
        val photoId = current()?.photoId ?: return null
        val proposal = proposals[photoId]
        return if (proposal != null) proposal.destinationId else truths[photoId]?.destinationId
    }

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
     * Keeps the current photo as it is, then advances. No file is touched.
     *
     * One rule: whatever was asked of the photo is withdrawn, and what has
     * happened to it is left standing. A photo filed in Famiglia and then
     * asked into Viaggi is, when kept, in Famiglia; one in the bin stays
     * in the bin. Only a photo nothing is known about gets something
     * written — that it was looked at and left where it is — and that is
     * the one thing an undo can take back.
     */
    fun keepCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        val photoId = photo.photoId
        if (proposals.containsKey(photoId)) {
            proposalRepository.withdraw(photoId).getOrElse { return Result.failure(it) }
            proposals = proposals - photoId
            rebuildQueue()
        }

        if (truths[photoId] == null) {
            stateRepository.record(photo, ReviewStatus.KEPT, null)
                .getOrElse { return Result.failure(it) }
            truths = truths + (photoId to PhotoStateRepository.StoredState(photoId, ReviewStatus.KEPT, null))
            keptHere.add(photoId)
            noteDecided(photoId)
        } else {
            // Nothing written, nothing to undo: what stands stood before.
            decidedHere.remove(photoId)
        }
        goNext()
        return Result.success(Unit)
    }

    /** Asks for the current photo to go to the app's bin, and advances. */
    fun trashCurrent(): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return propose(photo, Proposal.Action.TRASH, null)
    }

    /**
     * Asks for the current photo to go back where it came from, and
     * advances.
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
        if (originOf(photo) == null) {
            return Result.failure(IllegalStateException("Non si sa da dove venga"))
        }
        return propose(photo, Proposal.Action.RESTORE, null)
    }

    /** Asks for the current photo to be filed under [destination], and advances. */
    fun fileCurrent(destination: Destination): Result<Unit> {
        val photo = current() ?: return Result.failure(IllegalStateException("Nessuna foto"))
        return propose(photo, Proposal.Action.FILE, destination.id)
    }

    /**
     * Shared path for every action that moves a file: the proposal is
     * written, and the move it plans into waits for apply.
     */
    private fun propose(photo: PhotoRecord, action: Proposal.Action, destinationId: Long?): Result<Unit> =
        proposalRepository.propose(photo, action, destinationId).map { proposal ->
            // One photo, one request: changing mind replaces the earlier
            // proposal instead of adding a second, contradictory one.
            proposals = proposals + (photo.photoId to proposal)
            rebuildQueue()
            noteDecided(photo.photoId)
            goNext()
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
     * steps. The outcome is written by whoever moves the files; this
     * session hears of it through [noteRestored].
     */
    fun buildRestorePlan(onlyCurrent: Boolean): List<PendingMove> {
        val candidates = if (onlyCurrent) listOfNotNull(current()) else photos
        return candidates.mapNotNull { photo ->
            originOf(photo)?.let { MovePlanner.backHome(photo, it) }
        }
    }

    /**
     * Takes note of restores already applied and recorded: the photos are
     * kept now, and nothing is asked of them any more.
     */
    fun noteRestored(applied: List<PendingMove>) {
        for (move in applied) {
            val photoId = move.photo.photoId
            truths = truths + (photoId to PhotoStateRepository.StoredState(photoId, ReviewStatus.KEPT, null))
            proposals = proposals - photoId
        }
        rebuildQueue()
    }

    /** Where [photo] came from, or null when there is nothing to undo. */
    private fun originOf(photo: PhotoRecord): PhotoStateRepository.Location? {
        val origin = originalPaths[photo.photoId] ?: return null
        return if (MovePlanner.isHome(photo, origin)) null else origin
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
     * Undoes the last decision taken in this session and returns to that
     * photo: withdraws what it asked, and forgets the "kept" it wrote if
     * it wrote one. Tags are left alone, since tagging is not queued.
     *
     * Only this session's: what was asked before the last load is in the
     * queue too, but in database order, and taking it back one at a time
     * from here would undo photos the user cannot see. That is what the
     * queue screen is for.
     */
    fun undoLastMove(): Result<Unit> {
        if (decidedHere.isEmpty()) return Result.failure(IllegalStateException("Niente da annullare"))
        val photoId = decidedHere.removeAt(decidedHere.lastIndex)
        if (proposals.containsKey(photoId)) {
            proposalRepository.withdraw(photoId).getOrElse { return Result.failure(it) }
            proposals = proposals - photoId
        }
        if (keptHere.remove(photoId)) {
            stateRepository.forget(photoId).getOrElse { return Result.failure(it) }
            truths = truths - photoId
        }
        rebuildQueue()

        val position = photos.indexOfFirst { it.photoId == photoId }
        if (position >= 0) currentIndex = position
        return Result.success(Unit)
    }

    /**
     * Withdraws every proposal in scope.
     *
     * Only those: a photo merely marked as kept has nothing queued, its
     * record is already complete and correct, and there is nothing to undo.
     * The photos that were going to move go on being what the truth says
     * they are — never seen, filed, in the bin — because the request never
     * touched that.
     */
    fun discardQueue(): Result<Unit> {
        // What this session is holding, which is the work owed for the
        // folders and period in hand. Not everything owed: a button that
        // says "twelve" must not delete four hundred decided last week in
        // another folder. Taking back all of it is offered where all of it
        // is shown, which is the queue screen.
        val ids = proposals.keys.toList()
        proposalRepository.withdrawAll(ids).getOrElse { return Result.failure(it) }
        proposals = emptyMap()
        decidedHere.removeAll(ids.toSet())
        rebuildQueue()
        return Result.success(Unit)
    }
}
