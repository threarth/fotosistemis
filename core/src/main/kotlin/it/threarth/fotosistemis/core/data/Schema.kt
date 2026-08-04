package it.threarth.fotosistemis.core.data

import it.threarth.fotosistemis.core.model.Destination
import it.threarth.fotosistemis.core.port.Database

/**
 * Tables holding the photo inventory, destinations, review decisions, tags
 * and path history.
 *
 * Plain SQL rather than an ORM: the schema is a handful of tables, and an ORM
 * would tie the shared code to one platform's library.
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
     * v5 gave photos an identity of their own, independent of the platform.
     */
    const val VERSION = 5

    const val TABLE_PHOTOS = "photos"
    const val TABLE_DESTINATIONS = "destinations"
    const val TABLE_PHOTO_STATE = "photo_state"
    const val TABLE_TAGS = "tags"
    const val TABLE_PHOTO_TAGS = "photo_tags"
    const val TABLE_PHOTO_PATHS = "photo_paths"
    const val TABLE_SYNC_STATE = "sync_state"

    const val COLUMN_ID = "id"
    const val COLUMN_LABEL = "label"
    const val COLUMN_RELATIVE_PATH = "relative_path"
    const val COLUMN_YEAR_SUBFOLDER = "year_subfolder"
    const val COLUMN_SORT_ORDER = "sort_order"

    /** Our own identity for a photo. Assigned once, never reassigned. */
    const val COLUMN_PHOTO_ID = "photo_id"

    /**
     * Identifier the platform gave the file.
     *
     * A shortcut for finding a photo quickly, rewritten whenever it turns
     * out to be stale. Never an identity: the platform reassigns it when a
     * card is remounted or the media index is rebuilt, and rows keyed by it
     * would be orphaned without warning.
     */
    const val COLUMN_MEDIA_ID = "media_id"

    const val COLUMN_VOLUME_NAME = "volume_name"
    const val COLUMN_DISPLAY_NAME = "display_name"
    const val COLUMN_SIZE_BYTES = "size_bytes"
    const val COLUMN_DATE_TAKEN = "date_taken"
    const val COLUMN_DATE_SOURCE = "date_source"
    const val COLUMN_MEDIA_TYPE = "media_type"
    const val COLUMN_WIDTH = "width"
    const val COLUMN_HEIGHT = "height"
    const val COLUMN_DURATION_MILLIS = "duration_millis"

    /** Reserved for telling apart photos the cheap columns cannot separate. */
    const val COLUMN_CONTENT_HASH = "content_hash"

    const val COLUMN_FIRST_SEEN_AT = "first_seen_at"
    const val COLUMN_LAST_SEEN_AT = "last_seen_at"

    /** Set when a scan stops finding the file. Null while it is present. */
    const val COLUMN_MISSING_SINCE = "missing_since"

    const val COLUMN_STATUS = "status"
    const val COLUMN_DESTINATION_ID = "destination_id"
    const val COLUMN_UPDATED_AT = "updated_at"

    const val COLUMN_NAME = "name"
    const val COLUMN_TAG_ID = "tag_id"

    const val COLUMN_PATH = "path"
    const val COLUMN_KIND = "kind"
    const val COLUMN_RECORDED_AT = "recorded_at"
    const val COLUMN_LAST_FULL_SCAN_AT = "last_full_scan_at"

    /** Column dropped in v4; named only so the migration can find it. */
    private const val OBSOLETE_YEAR_FOLDER_PATTERN = "year_folder_pattern"

    /** Folders offered on a fresh install. All of them are editable. */
    private val SEED_DESTINATIONS = listOf(
        "Famiglia" to "Pictures/Famiglia",
        "Amici" to "Pictures/Amici",
        "Hobby" to "Pictures/Hobby",
        "Lavoro" to "Pictures/Lavoro"
    )

    /**
     * Everything known about one photo.
     *
     * The characteristic columns are not decoration: they are how a photo is
     * recognised when its platform id no longer resolves, and they are what
     * makes finding duplicates a query rather than a scan.
     *
     * media_type and duration_millis are filled for images today and left
     * ready for video, so adding it later needs no migration.
     */
    private const val CREATE_PHOTOS = """
        CREATE TABLE IF NOT EXISTS $TABLE_PHOTOS (
            $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COLUMN_MEDIA_ID INTEGER,
            $COLUMN_VOLUME_NAME TEXT NOT NULL DEFAULT '',
            $COLUMN_DISPLAY_NAME TEXT NOT NULL DEFAULT '',
            $COLUMN_RELATIVE_PATH TEXT NOT NULL DEFAULT '',
            $COLUMN_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
            $COLUMN_DATE_TAKEN INTEGER NOT NULL DEFAULT 0,
            $COLUMN_DATE_SOURCE TEXT,
            $COLUMN_MEDIA_TYPE TEXT NOT NULL DEFAULT 'image',
            $COLUMN_WIDTH INTEGER,
            $COLUMN_HEIGHT INTEGER,
            $COLUMN_DURATION_MILLIS INTEGER,
            $COLUMN_CONTENT_HASH TEXT,
            $COLUMN_FIRST_SEEN_AT INTEGER NOT NULL DEFAULT 0,
            $COLUMN_LAST_SEEN_AT INTEGER NOT NULL DEFAULT 0,
            $COLUMN_MISSING_SINCE INTEGER
        )
    """

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
            $COLUMN_PHOTO_ID INTEGER PRIMARY KEY,
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
            $COLUMN_PHOTO_ID INTEGER NOT NULL,
            $COLUMN_TAG_ID INTEGER NOT NULL,
            PRIMARY KEY ($COLUMN_PHOTO_ID, $COLUMN_TAG_ID)
        )
    """

    /**
     * Every location a photo has occupied, oldest first. Lets the app answer
     * "where did this come from" after any number of moves.
     */
    private const val CREATE_PHOTO_PATHS = """
        CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_PATHS (
            $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COLUMN_PHOTO_ID INTEGER NOT NULL,
            $COLUMN_PATH TEXT NOT NULL,
            $COLUMN_KIND TEXT NOT NULL,
            $COLUMN_RECORDED_AT INTEGER NOT NULL
        )
    """

    /**
     * When each volume was last reconciled in full.
     *
     * Unused while every start reconciles, and there so that spacing scans
     * out on a large library is a condition rather than a migration.
     */
    private const val CREATE_SYNC_STATE = """
        CREATE TABLE IF NOT EXISTS $TABLE_SYNC_STATE (
            $COLUMN_VOLUME_NAME TEXT PRIMARY KEY,
            $COLUMN_LAST_FULL_SCAN_AT INTEGER NOT NULL DEFAULT 0
        )
    """

    private val CREATE_TABLES = listOf(
        CREATE_PHOTOS, CREATE_DESTINATIONS, CREATE_PHOTO_STATE, CREATE_TAGS,
        CREATE_PHOTO_TAGS, CREATE_PHOTO_PATHS, CREATE_SYNC_STATE
    )

    private val CREATE_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS idx_photos_media ON $TABLE_PHOTOS ($COLUMN_MEDIA_ID)",
        "CREATE INDEX IF NOT EXISTS idx_photos_path ON $TABLE_PHOTOS ($COLUMN_RELATIVE_PATH)",
        "CREATE INDEX IF NOT EXISTS idx_photos_taken ON $TABLE_PHOTOS ($COLUMN_DATE_TAKEN)",
        // Recognising a photo whose platform id went stale, and finding
        // duplicates, are the same lookup.
        "CREATE INDEX IF NOT EXISTS idx_photos_print ON $TABLE_PHOTOS " +
                "($COLUMN_SIZE_BYTES, $COLUMN_DATE_TAKEN)",
        "CREATE INDEX IF NOT EXISTS idx_state_status ON $TABLE_PHOTO_STATE ($COLUMN_STATUS)",
        "CREATE INDEX IF NOT EXISTS idx_tags_photo ON $TABLE_PHOTO_TAGS ($COLUMN_PHOTO_ID)",
        "CREATE INDEX IF NOT EXISTS idx_tags_tag ON $TABLE_PHOTO_TAGS ($COLUMN_TAG_ID)",
        "CREATE INDEX IF NOT EXISTS idx_paths_photo ON $TABLE_PHOTO_PATHS ($COLUMN_PHOTO_ID)"
    )

    /** Builds the schema from nothing, and stocks the starter folders. */
    fun create(database: Database) {
        createTables(database)
        seedDestinations(database)
    }

    /**
     * Brings an older database up to date.
     *
     * Every statement is written to be re-runnable, and nothing is dropped
     * without being copied first: these rows are the only record of work the
     * user has already done.
     */
    fun migrate(database: Database, oldVersion: Int) {
        if (oldVersion < 5) migrateToVersion5(database)
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
     * Moves decisions, tags and path history off the platform id and onto an
     * identity of our own.
     *
     * A row in photos is created for every platform id the old tables refer
     * to, carrying whatever was recorded about it. The rest of each photo's
     * characteristics cannot come from the database and is filled by the
     * first reconciliation, which matches on the platform id while it still
     * resolves. Waiting for an event that invalidates those ids would lose
     * the connection for good.
     */
    private fun migrateToVersion5(database: Database) {
        if (!hasTable(database, TABLE_PHOTO_STATE)) return
        if (!hasColumn(database, TABLE_PHOTO_STATE, COLUMN_MEDIA_ID)) return

        database.execute(CREATE_PHOTOS)
        val now = System.currentTimeMillis()

        // Photos known from decisions carry a name and a size; those known
        // only from tags or history carry nothing but their platform id.
        database.execute(
            "INSERT INTO $TABLE_PHOTOS ($COLUMN_MEDIA_ID, $COLUMN_DISPLAY_NAME, " +
                    "$COLUMN_SIZE_BYTES, $COLUMN_FIRST_SEEN_AT, $COLUMN_LAST_SEEN_AT) " +
                    "SELECT $COLUMN_MEDIA_ID, $COLUMN_DISPLAY_NAME, $COLUMN_SIZE_BYTES, ?, ? " +
                    "FROM $TABLE_PHOTO_STATE",
            listOf(now, now)
        )
        for (table in listOf(TABLE_PHOTO_TAGS, TABLE_PHOTO_PATHS)) {
            if (!hasTable(database, table)) continue
            database.execute(
                "INSERT INTO $TABLE_PHOTOS ($COLUMN_MEDIA_ID, $COLUMN_FIRST_SEEN_AT, " +
                        "$COLUMN_LAST_SEEN_AT) SELECT DISTINCT $COLUMN_MEDIA_ID, ?, ? " +
                        "FROM $table WHERE $COLUMN_MEDIA_ID NOT IN " +
                        "(SELECT $COLUMN_MEDIA_ID FROM $TABLE_PHOTOS)",
                listOf(now, now)
            )
        }

        rekeyToPhotoId(database, TABLE_PHOTO_STATE, CREATE_PHOTO_STATE,
            listOf(COLUMN_STATUS, COLUMN_DESTINATION_ID, COLUMN_UPDATED_AT))
        rekeyToPhotoId(database, TABLE_PHOTO_TAGS, CREATE_PHOTO_TAGS, listOf(COLUMN_TAG_ID))
        rekeyToPhotoId(database, TABLE_PHOTO_PATHS, CREATE_PHOTO_PATHS,
            listOf(COLUMN_PATH, COLUMN_KIND, COLUMN_RECORDED_AT))
    }

    /** Rebuilds one table with photo_id in place of media_id. */
    private fun rekeyToPhotoId(
        database: Database,
        table: String,
        createStatement: String,
        carriedColumns: List<String>
    ) {
        if (!hasTable(database, table)) return
        if (!hasColumn(database, table, COLUMN_MEDIA_ID)) return

        val carried = carriedColumns.joinToString(", ")
        database.execute("ALTER TABLE $table RENAME TO ${table}_old")
        database.execute(createStatement)
        database.execute(
            "INSERT INTO $table ($COLUMN_PHOTO_ID, $carried) " +
                    "SELECT p.$COLUMN_ID, ${carriedColumns.joinToString(", ") { "o.$it" }} " +
                    "FROM ${table}_old o " +
                    "JOIN $TABLE_PHOTOS p ON p.$COLUMN_MEDIA_ID = o.$COLUMN_MEDIA_ID"
        )
        database.execute("DROP TABLE ${table}_old")
    }

    /**
     * ALTER TABLE ADD COLUMN throws when the column is already there, so it
     * must be guarded for a migration to stay re-runnable.
     */
    private fun hasColumn(database: Database, table: String, column: String): Boolean =
        database.query("PRAGMA table_info($table)").any { it.getString("name") == column }

    private fun hasTable(database: Database, table: String): Boolean =
        database.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
            listOf(table)
        ).isNotEmpty()

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
