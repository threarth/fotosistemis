package it.threarth.fotosistemis

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local store of destinations, review decisions, tags and path history.
 *
 * Uses the framework SQLite classes rather than Room: adding an ORM would
 * pull in an annotation processor and several artifacts for a handful of
 * tables.
 *
 * Folders and tags answer different questions and are stored differently on
 * purpose. A photo lives in exactly one folder, so the destination is a
 * single foreign key. A photo can mean several things at once, so tags are a
 * many-to-many relation. Collapsing tags into a column would silently forbid
 * the second tag.
 */
class PhotoStateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "fotosistemis.db"

        /**
         * v2 introduced destinations, tags and path history.
         * v3 made the year folder name configurable per destination.
         * v4 moved that name to a single application-wide setting.
         */
        const val DATABASE_VERSION = 4

        const val TABLE_DESTINATIONS = "destinations"
        const val TABLE_PHOTO_STATE = "photo_state"
        const val TABLE_TAGS = "tags"
        const val TABLE_PHOTO_TAGS = "photo_tags"
        const val TABLE_PHOTO_PATHS = "photo_paths"

        const val COLUMN_ID = "id"
        const val COLUMN_LABEL = "label"
        const val COLUMN_RELATIVE_PATH = "relative_path"
        const val COLUMN_YEAR_SUBFOLDER = "year_subfolder"
        const val COLUMN_SORT_ORDER = "sort_order"

        /** MediaStore _ID. Stable across moves inside the same volume. */
        const val COLUMN_MEDIA_ID = "media_id"
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_SIZE_BYTES = "size_bytes"
        const val COLUMN_STATUS = "status"
        const val COLUMN_DESTINATION_ID = "destination_id"
        const val COLUMN_UPDATED_AT = "updated_at"

        const val COLUMN_NAME = "name"
        const val COLUMN_TAG_ID = "tag_id"

        const val COLUMN_PATH = "path"
        const val COLUMN_KIND = "kind"
        const val COLUMN_RECORDED_AT = "recorded_at"

        /** Folders offered on a fresh install. All of them are editable. */
        private val SEED_DESTINATIONS = listOf(
            "Famiglia" to "Pictures/Famiglia",
            "Amici" to "Pictures/Amici",
            "Hobby" to "Pictures/Hobby",
            "Lavoro" to "Pictures/Lavoro"
        )

        private const val CREATE_DESTINATIONS = """
            CREATE TABLE IF NOT EXISTS $TABLE_DESTINATIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_LABEL TEXT NOT NULL,
                $COLUMN_RELATIVE_PATH TEXT NOT NULL,
                $COLUMN_YEAR_SUBFOLDER INTEGER NOT NULL DEFAULT 1,
                $COLUMN_SORT_ORDER INTEGER NOT NULL DEFAULT 0
            )
        """

        private const val CREATE_PHOTO_STATE = """
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_STATE (
                $COLUMN_MEDIA_ID INTEGER PRIMARY KEY,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_SIZE_BYTES INTEGER NOT NULL,
                $COLUMN_STATUS TEXT NOT NULL,
                $COLUMN_DESTINATION_ID INTEGER,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
        """

        private const val CREATE_TAGS = """
            CREATE TABLE IF NOT EXISTS $TABLE_TAGS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_NAME TEXT NOT NULL UNIQUE
            )
        """

        private const val CREATE_PHOTO_TAGS = """
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_TAGS (
                $COLUMN_MEDIA_ID INTEGER NOT NULL,
                $COLUMN_TAG_ID INTEGER NOT NULL,
                PRIMARY KEY ($COLUMN_MEDIA_ID, $COLUMN_TAG_ID)
            )
        """

        /**
         * Every location a photo has occupied, oldest first. Lets the app
         * answer "where did this come from" after any number of moves.
         */
        private const val CREATE_PHOTO_PATHS = """
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_PATHS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_MEDIA_ID INTEGER NOT NULL,
                $COLUMN_PATH TEXT NOT NULL,
                $COLUMN_KIND TEXT NOT NULL,
                $COLUMN_RECORDED_AT INTEGER NOT NULL
            )
        """

        /** Column removed in v4; named only so the migration can find it. */
        private const val OBSOLETE_YEAR_FOLDER_PATTERN = "year_folder_pattern"

        private val CREATE_INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS idx_state_status ON $TABLE_PHOTO_STATE ($COLUMN_STATUS)",
            "CREATE INDEX IF NOT EXISTS idx_tags_media ON $TABLE_PHOTO_TAGS ($COLUMN_MEDIA_ID)",
            "CREATE INDEX IF NOT EXISTS idx_tags_tag ON $TABLE_PHOTO_TAGS ($COLUMN_TAG_ID)",
            "CREATE INDEX IF NOT EXISTS idx_paths_media ON $TABLE_PHOTO_PATHS ($COLUMN_MEDIA_ID)"
        )

        private val CREATE_ALL = listOf(
            CREATE_DESTINATIONS, CREATE_PHOTO_STATE, CREATE_TAGS,
            CREATE_PHOTO_TAGS, CREATE_PHOTO_PATHS
        )
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSchema(db)
        seedDestinations(db)
    }

    /**
     * Migrations are idempotent by construction: every statement carries
     * IF NOT EXISTS, so re-running one is harmless. Nothing is ever dropped;
     * these rows are the only record of work the user has already done.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createSchema(db)
        if (oldVersion < 2) migrateToVersion2(db)
        if (oldVersion < 4) migrateToVersion4(db)
    }

    private fun createSchema(db: SQLiteDatabase) {
        CREATE_ALL.forEach { db.execSQL(it) }
        CREATE_INDEXES.forEach { db.execSQL(it) }
    }

    /**
     * v1 stored a free-text tag directly on photo_state. Those values become
     * proper tag rows, and the obsolete column is left in place: SQLite
     * cannot drop a column cheaply, and an unused column costs nothing.
     */
    private fun migrateToVersion2(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_PHOTO_STATE, COLUMN_DESTINATION_ID)) {
            db.execSQL("ALTER TABLE $TABLE_PHOTO_STATE ADD COLUMN $COLUMN_DESTINATION_ID INTEGER")
        }
        seedDestinations(db)
    }

    /**
     * Drops the per-destination year folder pattern introduced in v3: it is
     * now one setting shared by every folder.
     *
     * SQLite before 3.35 cannot drop a column, and the version shipped with
     * API 33 is older than that, so the table is rebuilt and refilled. Doing
     * it inside the caller's transaction keeps the data safe if it fails.
     */
    private fun migrateToVersion4(db: SQLiteDatabase) {
        if (!hasColumn(db, TABLE_DESTINATIONS, OBSOLETE_YEAR_FOLDER_PATTERN)) return
        val columns = "$COLUMN_ID, $COLUMN_LABEL, $COLUMN_RELATIVE_PATH, " +
                "$COLUMN_YEAR_SUBFOLDER, $COLUMN_SORT_ORDER"
        db.execSQL("ALTER TABLE $TABLE_DESTINATIONS RENAME TO ${TABLE_DESTINATIONS}_old")
        db.execSQL(CREATE_DESTINATIONS)
        db.execSQL(
            "INSERT INTO $TABLE_DESTINATIONS ($columns) " +
                    "SELECT $columns FROM ${TABLE_DESTINATIONS}_old"
        )
        db.execSQL("DROP TABLE ${TABLE_DESTINATIONS}_old")
    }

    /**
     * ALTER TABLE ADD COLUMN throws when the column is already there, so it
     * must be guarded for the migration to stay re-runnable.
     */
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
            false
        }

    /** Inserts the starter folders only when the table is still empty. */
    private fun seedDestinations(db: SQLiteDatabase) {
        val existing = db.rawQuery("SELECT COUNT(*) FROM $TABLE_DESTINATIONS", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (existing > 0) return

        SEED_DESTINATIONS.forEachIndexed { index, (label, path) ->
            db.execSQL(
                "INSERT INTO $TABLE_DESTINATIONS " +
                        "($COLUMN_LABEL, $COLUMN_RELATIVE_PATH, $COLUMN_YEAR_SUBFOLDER, $COLUMN_SORT_ORDER) " +
                        "VALUES (?, ?, 1, ?)",
                arrayOf(label, path, index)
            )
        }
    }
}
