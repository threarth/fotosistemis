package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.port.Database

/**
 * Tables holding destinations, review decisions, tags and path history.
 *
 * Plain SQL rather than an ORM: the schema is a handful of tables, and an
 * ORM would tie the shared code to one platform's library.
 *
 * Folders and tags are stored differently on purpose. A photo lives in
 * exactly one folder, so the destination is a single foreign key; a photo can
 * mean several things at once, so tags are a many-to-many relation.
 * Collapsing tags into a column would silently forbid the second tag.
 */
object Schema {

    /**
     * v2 introduced destinations, tags and path history.
     * v3 made the year folder name configurable per destination.
     * v4 moved that name to a single application-wide setting.
     */
    const val VERSION = 4

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

    /** Platform id of the photo. A shortcut, never an identity. */
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

    /** Column dropped in v4; named only so the migration can find it. */
    private const val OBSOLETE_YEAR_FOLDER_PATTERN = "year_folder_pattern"

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
     * Every location a photo has occupied, oldest first. Lets the app answer
     * "where did this come from" after any number of moves.
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

    private val CREATE_TABLES = listOf(
        CREATE_DESTINATIONS, CREATE_PHOTO_STATE, CREATE_TAGS,
        CREATE_PHOTO_TAGS, CREATE_PHOTO_PATHS
    )

    private val CREATE_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS idx_state_status ON $TABLE_PHOTO_STATE ($COLUMN_STATUS)",
        "CREATE INDEX IF NOT EXISTS idx_tags_media ON $TABLE_PHOTO_TAGS ($COLUMN_MEDIA_ID)",
        "CREATE INDEX IF NOT EXISTS idx_tags_tag ON $TABLE_PHOTO_TAGS ($COLUMN_TAG_ID)",
        "CREATE INDEX IF NOT EXISTS idx_paths_media ON $TABLE_PHOTO_PATHS ($COLUMN_MEDIA_ID)"
    )

    /** Builds the schema from nothing, and stocks the starter folders. */
    fun create(database: Database) {
        createTables(database)
        seedDestinations(database)
    }

    /**
     * Brings an older database up to date.
     *
     * Every statement is written to be re-runnable, and nothing is ever
     * dropped without being copied first: these rows are the only record of
     * work the user has already done.
     */
    fun migrate(database: Database, oldVersion: Int) {
        createTables(database)
        if (oldVersion < 2) migrateToVersion2(database)
        if (oldVersion < 4) migrateToVersion4(database)
    }

    private fun createTables(database: Database) {
        CREATE_TABLES.forEach { database.execute(it) }
        CREATE_INDEXES.forEach { database.execute(it) }
    }

    /** v1 kept a free-text tag on photo_state and knew no destinations. */
    private fun migrateToVersion2(database: Database) {
        if (!hasColumn(database, TABLE_PHOTO_STATE, COLUMN_DESTINATION_ID)) {
            database.execute(
                "ALTER TABLE $TABLE_PHOTO_STATE ADD COLUMN $COLUMN_DESTINATION_ID INTEGER"
            )
        }
        seedDestinations(database)
    }

    /**
     * Drops the per-destination year folder pattern added in v3: it is now
     * one setting shared by every folder.
     *
     * SQLite before 3.35 cannot drop a column, and the version shipped with
     * API 33 is older, so the table is rebuilt and refilled.
     */
    private fun migrateToVersion4(database: Database) {
        if (!hasColumn(database, TABLE_DESTINATIONS, OBSOLETE_YEAR_FOLDER_PATTERN)) return
        val columns = "$COLUMN_ID, $COLUMN_LABEL, $COLUMN_RELATIVE_PATH, " +
                "$COLUMN_YEAR_SUBFOLDER, $COLUMN_SORT_ORDER"
        database.execute("ALTER TABLE $TABLE_DESTINATIONS RENAME TO ${TABLE_DESTINATIONS}_old")
        database.execute(CREATE_DESTINATIONS)
        database.execute(
            "INSERT INTO $TABLE_DESTINATIONS ($columns) " +
                    "SELECT $columns FROM ${TABLE_DESTINATIONS}_old"
        )
        database.execute("DROP TABLE ${TABLE_DESTINATIONS}_old")
    }

    /**
     * ALTER TABLE ADD COLUMN throws when the column is already there, so it
     * must be guarded for a migration to stay re-runnable.
     */
    private fun hasColumn(database: Database, table: String, column: String): Boolean =
        database.query("PRAGMA table_info($table)").any { it.getString("name") == column }

    /** Inserts the starter folders only when the table is still empty. */
    private fun seedDestinations(database: Database) {
        val existing = database.query("SELECT COUNT(*) AS total FROM $TABLE_DESTINATIONS")
            .firstOrNull()?.getInt("total") ?: 0
        if (existing > 0) return

        SEED_DESTINATIONS.forEachIndexed { index, (label, path) ->
            database.insert(
                "INSERT INTO $TABLE_DESTINATIONS " +
                        "($COLUMN_LABEL, $COLUMN_RELATIVE_PATH, $COLUMN_YEAR_SUBFOLDER, " +
                        "$COLUMN_SORT_ORDER) VALUES (?, ?, 1, ?)",
                listOf(label, path, index)
            )
        }
    }

    /** Default name for the year subfolder, shared by every destination. */
    const val DEFAULT_YEAR_FOLDER_PATTERN = Destination.DEFAULT_YEAR_FOLDER_PATTERN
}
