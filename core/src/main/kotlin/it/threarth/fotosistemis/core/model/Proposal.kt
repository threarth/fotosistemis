package it.threarth.fotosistemis.core.model

/**
 * Something the user has asked to be done to a photo, and that has not
 * been done yet.
 *
 * New feature (schema v12): the pending decision lives apart from the
 * truth. Deciding costs a gesture; moving costs the platform's permission
 * and real time. Between them the archive keeps saying what has happened
 * to the photo — kept, filed, thrown away — and this says what is asked
 * of it next. Withdrawing a proposal deletes it and touches nothing else,
 * which is exactly what discarding a decision should mean: the photo goes
 * on being what it was.
 *
 * Nothing else is stored. Where the file will go and what it will be
 * called are computed from the destination and the photo when the move
 * is planned; where a restored photo goes back to is in its path history;
 * and the state it replaces is the state itself, left untouched.
 */
data class Proposal(
    val photoId: Long,
    val action: Action,
    val destinationId: Long?,
    val proposedAt: Long
) {

    /** What is asked, and what it becomes once done. */
    enum class Action(val storedValue: String, val outcome: ReviewStatus) {

        /** Move into a category's folder. */
        FILE("file", ReviewStatus.CATEGORIZED),

        /** Move into the bin. */
        TRASH("trash", ReviewStatus.TRASHED),

        /** Move back out of the bin to where it came from. */
        RESTORE("restore", ReviewStatus.KEPT);

        companion object {

            /** Parses a stored value, or null when the string is unknown. */
            fun fromStoredValue(value: String?): Action? =
                entries.firstOrNull { it.storedValue == value }
        }
    }

    /** ReviewStatus.CATEGORIZED for a filing, and so on: the status the photo is shown with. */
    val shownStatus: ReviewStatus get() = action.outcome
}
