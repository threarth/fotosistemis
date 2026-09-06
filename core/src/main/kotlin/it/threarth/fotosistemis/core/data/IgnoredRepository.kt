package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.port.Database

/**
 * What the user has told a check to stop reporting.
 *
 * A check that cannot be told "leave this alone" is a check that gets
 * ignored wholesale: the same photos come back at every scan, the list stops
 * being read, and the one new problem in it goes unseen. Remembering the
 * answer is what keeps the question worth asking.
 *
 * The answer is recorded per check, not per photo. Saying that a photo's
 * date is fine as it is must not also silence the fact that it is sitting in
 * the wrong folder: those are different questions about the same picture.
 */
class IgnoredRepository(private val database: Database) {

    /** Which check was told to leave a photo alone. */
    enum class Check(val storedValue: String) {

        /** Photos inside a category folder that nobody filed there. */
        STRANGERS("strangers"),

        /** Filed photos that are not in the folder their category names. */
        MISPLACED("misplaced"),

        /** Photos whose carriers disagree about when they were taken. */
        DATES("dates")
    }

    /** Photo ids this check has been told to skip. */
    fun idsFor(check: Check): Result<Set<Long>> = runCatching {
        database.query(
            "SELECT ${Schema.COLUMN_PHOTO_ID} AS pid FROM ${Schema.TABLE_IGNORED} " +
                    "WHERE ${Schema.COLUMN_CHECK} = ?",
            listOf(check.storedValue)
        ).mapNotNull { it.getLong("pid") }.toSet()
    }

    /** Tells [check] to leave these photos alone, and says how many. */
    fun ignore(check: Check, photoIds: List<Long>): Result<Int> = runCatching {
        if (photoIds.isEmpty()) return@runCatching 0

        database.transaction {
            val now = System.currentTimeMillis()
            for (photoId in photoIds) {
                database.execute(
                    "INSERT OR REPLACE INTO ${Schema.TABLE_IGNORED} " +
                            "(${Schema.COLUMN_PHOTO_ID}, ${Schema.COLUMN_CHECK}, " +
                            "${Schema.COLUMN_RECORDED_AT}) VALUES (?, ?, ?)",
                    listOf(photoId, check.storedValue, now)
                )
            }
            photoIds.size
        }
    }

    /**
     * Takes back every "leave it alone" given to [check].
     *
     * Without this an answer given once could never be revisited, and a
     * decision to look away would quietly become permanent.
     */
    fun clear(check: Check): Result<Int> = runCatching {
        database.transaction {
            database.execute(
                "DELETE FROM ${Schema.TABLE_IGNORED} WHERE ${Schema.COLUMN_CHECK} = ?",
                listOf(check.storedValue)
            )
        }
    }

    /** How many photos each check has been told to skip. */
    fun counts(): Result<Map<Check, Int>> = runCatching {
        val byValue = Check.entries.associateBy { it.storedValue }

        database.query(
            "SELECT ${Schema.COLUMN_CHECK} AS kind, COUNT(*) AS total " +
                    "FROM ${Schema.TABLE_IGNORED} GROUP BY ${Schema.COLUMN_CHECK}"
        ).mapNotNull { row ->
            val check = byValue[row.getString("kind")] ?: return@mapNotNull null
            check to (row.getInt("total") ?: 0)
        }.toMap()
    }
}
