package it.threarth.fotosistemis

/**
 * What the user has already decided about a photo.
 *
 * A photo with no stored status has never been reviewed. That absence is the
 * definition of "unseen", so it is deliberately not a value of this enum:
 * storing an explicit UNSEEN row for every photo on the device would mean
 * writing thousands of rows that carry no information.
 */
enum class ReviewStatus(val storedValue: String) {

    /** Looked at and deliberately left where it is. */
    KEPT("kept"),

    /** Assigned a tag and queued to move into its arch_ folder. */
    CATEGORIZED("categorized"),

    /** Queued to move into the trash folder. */
    TRASHED("trashed");

    companion object {

        /** Parses a stored value, or null when the string is unknown. */
        fun fromStoredValue(value: String?): ReviewStatus? =
            entries.firstOrNull { it.storedValue == value }
    }
}
