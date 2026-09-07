package it.threarth.fotosistemis.core.review

import it.threarth.fotosistemis.core.data.PhotoStateRepository
import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.model.FolderPath
import it.threarth.fotosistemis.core.model.PhotoRecord
import it.threarth.fotosistemis.core.model.Proposal
import it.threarth.fotosistemis.core.reorg.FileNamer

/**
 * Turns a decision into the one file move that carries it out.
 *
 * New: the single place that answers "where does this photo go, and under
 * what name". Four screens used to answer it each in their own way, and
 * they disagreed on the name: filing from the review screen stamped the
 * file with its date, filing the same decision from the queue or the grid
 * did not. One rule, written once, is the only kind that can be checked.
 */
object MovePlanner {

    /** Into the app's own bin, keeping the name: nothing there is renamed. */
    fun toBin(photo: PhotoRecord): ReviewSession.PendingMove =
        ReviewSession.PendingMove(
            photo, ReviewSession.DELETION_STAGING_PATH, Proposal.Action.TRASH, null
        )

    /**
     * Into [destination], under the year folder its date calls for, and
     * with the date stamped on the name.
     *
     * Stamped here, not only when the whole filesystem is reorganised. The
     * stamp is what makes a flat folder sort by time, and it has to work
     * for the photos that carry no date of their own — which is most of
     * what arrives from elsewhere. Filing is the moment the photo enters
     * the archive, so it is the moment to name it.
     */
    fun toCategory(
        photo: PhotoRecord,
        destination: Destination,
        yearFolderPattern: String
    ): ReviewSession.PendingMove {
        val naming = FileNamer.nameAll(
            listOf(
                FileNamer.Request(
                    photo.photoId, photo.displayName, photo.dateTakenMillis, photo.dateSource
                )
            )
        ).firstOrNull()

        return ReviewSession.PendingMove(
            photo,
            destination.pathFor(photo.dateTakenMillis, yearFolderPattern),
            Proposal.Action.FILE,
            destination.id,
            naming?.displayName
        )
    }

    /**
     * Back to where the app first saw it, under the name it had then.
     *
     * A photo returned to its folder still carrying a stamped name has not
     * really been put back.
     */
    fun backHome(
        photo: PhotoRecord,
        origin: PhotoStateRepository.Location
    ): ReviewSession.PendingMove =
        ReviewSession.PendingMove(
            photo, origin.relativePath, Proposal.Action.RESTORE, null, origin.displayName
        )

    /**
     * Why a proposal cannot be carried out as things stand.
     *
     * New: named so the queue can show such a proposal instead of hiding
     * it. Hidden, it still counted and still coloured the photo as
     * decided, and the only way to take it back was to call off everything.
     */
    enum class Obstacle {

        /** A filing whose category has since been deleted. */
        NO_CATEGORY,

        /** A restore of a photo the app never saw anywhere else. */
        NO_ORIGIN,

        /** A restore of a photo that is already back where it came from. */
        ALREADY_HOME
    }

    /**
     * What stands in the way of [proposal], or null when nothing does.
     *
     * The proposal stays in the database either way; only the move cannot
     * be planned from here.
     */
    fun obstacle(
        photo: PhotoRecord,
        proposal: Proposal,
        destination: Destination?,
        origin: PhotoStateRepository.Location?
    ): Obstacle? = when (proposal.action) {
        Proposal.Action.TRASH -> null
        Proposal.Action.FILE -> if (destination == null) Obstacle.NO_CATEGORY else null
        Proposal.Action.RESTORE -> when {
            origin == null -> Obstacle.NO_ORIGIN
            isHome(photo, origin) -> Obstacle.ALREADY_HOME
            else -> null
        }
    }

    /**
     * The move a proposal asks for, or null when an [obstacle] stands in
     * the way.
     *
     * The destination is recomputed rather than stored: if the category
     * was pointed at a different folder between asking and applying, the
     * photo should go where the category points now.
     */
    fun plan(
        photo: PhotoRecord,
        proposal: Proposal,
        destination: Destination?,
        yearFolderPattern: String,
        origin: PhotoStateRepository.Location?
    ): ReviewSession.PendingMove? {
        if (obstacle(photo, proposal, destination, origin) != null) return null
        return when (proposal.action) {
            Proposal.Action.TRASH -> toBin(photo)
            Proposal.Action.FILE -> toCategory(photo, destination!!, yearFolderPattern)
            Proposal.Action.RESTORE -> backHome(photo, origin!!)
        }
    }

    /**
     * True when [photo] is already where [origin] says, folder and name.
     *
     * Both have to match: a photo moved back but still carrying a stamped
     * name has not been restored yet. A history row written before v6 has
     * no name, and then only the folder can be compared.
     */
    fun isHome(photo: PhotoRecord, origin: PhotoStateRepository.Location): Boolean =
        FolderPath.sameFolder(origin.relativePath, photo.relativePath) &&
                (origin.displayName == null || origin.displayName == photo.displayName)
}
