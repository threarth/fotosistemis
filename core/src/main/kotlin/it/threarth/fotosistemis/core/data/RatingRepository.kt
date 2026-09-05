package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.port.Database

/**
 * How many stars the user has given each photo.
 *
 * Kept apart from the review decisions: a photo can be worth something long
 * before anything has been decided about where it goes, and rating one is
 * not a decision about filing it.
 */
class RatingRepository(private val database: Database) {

    companion object {

        /** The most stars a photo can carry. */
        const val MAX_STARS = 5

        /** Stars meaning "no rating", which is not the same as a bad one. */
        const val UNRATED = 0
    }

    /** Every rating given, keyed by photo. */
    fun loadAll(): Result<Map<Long, Int>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STARS} " +
                    "FROM ${Schema.TABLE_PHOTO_RATINGS}"
        ).mapNotNull { row ->
            val photoId = row.getLong(Schema.COLUMN_PHOTO_ID) ?: return@mapNotNull null
            photoId to (row.getInt(Schema.COLUMN_STARS) ?: return@mapNotNull null)
        }.toMap()
    }

    /**
     * Records [stars] for a photo, or removes the rating when it is
     * [UNRATED].
     *
     * Storing a zero would make "rated badly" and "never looked at" the same
     * value, and only one of the two is a judgement.
     */
    fun rate(photoId: Long, stars: Int): Result<Unit> = runCatching {
        require(stars in UNRATED..MAX_STARS) { "Stelle fuori intervallo: $stars" }

        database.transaction {
            if (stars == UNRATED) {
                database.execute(
                    "DELETE FROM ${Schema.TABLE_PHOTO_RATINGS} " +
                            "WHERE ${Schema.COLUMN_PHOTO_ID} = ?",
                    listOf(photoId)
                )
            } else {
                database.execute(
                    "INSERT OR REPLACE INTO ${Schema.TABLE_PHOTO_RATINGS} " +
                            "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_STARS}, " +
                            "${Schema.COLUMN_UPDATED_AT}) VALUES (?, ?, ?)",
                    listOf(photoId, stars, System.currentTimeMillis())
                )
            }
            Unit
        }
    }
}
