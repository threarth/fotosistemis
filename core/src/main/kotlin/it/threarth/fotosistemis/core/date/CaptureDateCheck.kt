package it.threarth.fotosistemis.core.date

/**
 * Where a photograph's date is written, and whether the copies agree.
 *
 * A date is not kept in one place. It is inside the file, in the EXIF, which
 * is what travels to a computer or to Google Photos. It is on the file, as
 * its modification time, which is what this phone's gallery reads when the
 * EXIF says nothing. And it is in the archive's own index, which is what the
 * app and every other app query.
 *
 * They come apart. Writing EXIF into a file the archive has already indexed
 * leaves the index untouched, because a photo's metadata is read once, when
 * it is first seen. Copying a file gives it today's timestamp. Each of those
 * mends one carrier and leaves the others saying something else.
 *
 * So they are checked together, against the one date the app is sure of, and
 * a photograph is only right when all three agree.
 */
object CaptureDateCheck {

    /**
     * How far apart two dates may be and still count as the same moment.
     *
     * EXIF and file timestamps have second precision while the index has
     * milliseconds, so exact equality would report a disagreement that does
     * not exist. Two seconds is wide enough for that and far too narrow to
     * hide a real one, which is always a matter of days.
     */
    const val TOLERANCE_MILLIS = 2_000L

    /** What each carrier says, or null where it says nothing at all. */
    data class Carriers(
        val exifMillis: Long?,
        val fileMillis: Long?,
        val indexMillis: Long?
    )

    /** One carrier's standing against the date the app holds. */
    enum class State {

        /** Says the right thing. */
        AGREES,

        /** Says nothing. */
        SILENT,

        /** Says something else. */
        DISAGREES
    }

    /** The verdict on one photograph. */
    data class Verdict(
        val expectedMillis: Long,
        val exif: State,
        val file: State,
        val index: State
    ) {

        /** True when every carrier agrees, which is the only settled state. */
        val settled: Boolean
            get() = exif == State.AGREES && file == State.AGREES && index == State.AGREES

        /** The carriers that need writing, named for a report. */
        val wrong: List<String>
            get() = buildList {
                if (exif != State.AGREES) add(CARRIER_EXIF)
                if (file != State.AGREES) add(CARRIER_FILE)
                if (index != State.AGREES) add(CARRIER_INDEX)
            }
    }

    const val CARRIER_EXIF = "EXIF"
    const val CARRIER_FILE = "data del file"
    const val CARRIER_INDEX = "archivio"

    /** Judges every carrier against [expectedMillis]. */
    fun check(expectedMillis: Long, carriers: Carriers): Verdict = Verdict(
        expectedMillis = expectedMillis,
        exif = stateOf(expectedMillis, carriers.exifMillis),
        file = stateOf(expectedMillis, carriers.fileMillis),
        index = stateOf(expectedMillis, carriers.indexMillis)
    )

    private fun stateOf(expectedMillis: Long, actualMillis: Long?): State = when {
        actualMillis == null -> State.SILENT
        kotlin.math.abs(actualMillis - expectedMillis) <= TOLERANCE_MILLIS -> State.AGREES
        else -> State.DISAGREES
    }
}
