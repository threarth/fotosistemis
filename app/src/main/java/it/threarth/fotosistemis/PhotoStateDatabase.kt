package it.threarth.fotosistemis

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local store of review decisions.
 *
 * Uses the framework SQLite classes rather than Room: the schema is a single
 * table and adding an ORM would pull in an annotation processor and several
 * artifacts for no benefit.
 *
 * Migrations are versioned and idempotent. onUpgrade must never drop the
 * table: these rows are the only record of work the user has already done,
 * and they cannot be reconstructed from the photos themselves.
 */
class PhotoStateDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "fotosistemis.db"
        const val DATABASE_VERSION = 1

        const val TABLE_PHOTO_STATE = "photo_state"

        /** MediaStore _ID. Stable across moves inside the same volume. */
        const val COLUMN_MEDIA_ID = "media_id"

        /** Kept for diagnostics and for recovering rows if an _ID ever changes. */
        const val COLUMN_DISPLAY_NAME = "display_name"
        const val COLUMN_SIZE_BYTES = "size_bytes"

        const val COLUMN_STATUS = "status"
        const val COLUMN_TAG = "tag"
        const val COLUMN_UPDATED_AT = "updated_at"

        private const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_PHOTO_STATE (
                $COLUMN_MEDIA_ID INTEGER PRIMARY KEY,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_SIZE_BYTES INTEGER NOT NULL,
                $COLUMN_STATUS TEXT NOT NULL,
                $COLUMN_TAG TEXT,
                $COLUMN_UPDATED_AT INTEGER NOT NULL
            )
        """

        private const val CREATE_STATUS_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_photo_state_status
            ON $TABLE_PHOTO_STATE ($COLUMN_STATUS)
        """
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_STATUS_INDEX)
    }

    /**
     * No schema change exists yet. Statements are written to be re-runnable so
     * that a future migration can call them without special cases.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(CREATE_STATUS_INDEX)
    }
}
