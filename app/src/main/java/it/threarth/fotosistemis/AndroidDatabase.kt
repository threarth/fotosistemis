package it.threarth.fotosistemis

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import it.threarth.fotosistemis.core.data.Schema
import it.threarth.fotosistemis.core.port.Database

/**
 * Runs the shared schema on Android's own SQLite.
 *
 * The shared code states what it needs as plain SQL; this class is the only
 * place that knows which driver executes it.
 *
 * Bugfix: every screen builds its own instance, and each used to open its
 * own connection to the same file. SQLite lets one connection write at a
 * time, so a screen arriving while a scan held the file waited a couple of
 * seconds and then failed with "database is locked". All instances now share
 * one helper: a second writer queues behind the first instead of failing,
 * and readers, thanks to write-ahead logging, do not wait at all.
 */
class AndroidDatabase(context: Context) : Database {

    private companion object {
        const val DATABASE_NAME = "fotosistemis.db"

        /** The one helper of the process; guarded by [sharedHelper]. */
        private var helper: Helper? = null

        /** Opens the helper the first time it is asked for, and reuses it after. */
        @Synchronized
        fun sharedHelper(context: Context): Helper =
            helper ?: Helper(context.applicationContext).also {
                it.setWriteAheadLoggingEnabled(true)
                helper = it
            }
    }

    /**
     * Creating and migrating happen through this class rather than inside
     * the helper, so the shared schema code drives both.
     */
    private class Helper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, Schema.VERSION) {

        override fun onCreate(db: SQLiteDatabase) = Schema.create(Delegate(db))

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
            Schema.migrate(Delegate(db), oldVersion)
    }

    /**
     * Wraps the database handed to a migration callback.
     *
     * During onCreate and onUpgrade the helper is still opening, so asking it
     * for a connection again would deadlock: the callback's own handle has to
     * be used instead.
     */
    private class Delegate(private val db: SQLiteDatabase) : Database {
        override fun query(sql: String, args: List<Any?>) = readRows(db, sql, args)
        override fun execute(sql: String, args: List<Any?>) = runStatement(db, sql, args)
        override fun insert(sql: String, args: List<Any?>) = runInsert(db, sql, args)
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val helper = sharedHelper(context)

    override fun query(sql: String, args: List<Any?>): List<Database.Row> =
        readRows(helper.readableDatabase, sql, args)

    override fun execute(sql: String, args: List<Any?>): Int =
        runStatement(helper.writableDatabase, sql, args)

    override fun insert(sql: String, args: List<Any?>): Long =
        runInsert(helper.writableDatabase, sql, args)

    override fun <T> transaction(block: () -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val outcome = block()
            db.setTransactionSuccessful()
            outcome
        } finally {
            db.endTransaction()
        }
    }
}

/**
 * Copies a cursor into plain rows.
 *
 * Draining it at once keeps cursors from outliving the call: a caller
 * holding one open would pin a database connection it knows nothing
 * about.
 */
private fun readRows(
    db: SQLiteDatabase,
    sql: String,
    args: List<Any?>
): List<Database.Row> {
    db.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { cursor ->
        val rows = ArrayList<Database.Row>(cursor.count)
        while (cursor.moveToNext()) rows.add(readRow(cursor))
        return rows
    }
}

private fun readRow(cursor: Cursor): Database.Row {
    val values = HashMap<String, String?>(cursor.columnCount)
    for (index in 0 until cursor.columnCount) {
        values[cursor.getColumnName(index)] =
            if (cursor.isNull(index)) null else cursor.getString(index)
    }
    return MapRow(values)
}

/** A row already read out of its cursor. */
private class MapRow(private val values: Map<String, String?>) : Database.Row {
    override fun getString(column: String): String? = values[column]
    override fun getLong(column: String): Long? = values[column]?.toLongOrNull()
    override fun getInt(column: String): Int? = values[column]?.toIntOrNull()
}

private fun runStatement(db: SQLiteDatabase, sql: String, args: List<Any?>): Int {
    db.compileStatement(sql).use { statement ->
        bind(statement, args)
        return if (sql.trimStart().startsWith("SELECT", ignoreCase = true)) 0
        else statement.executeUpdateDelete()
    }
}

private fun runInsert(db: SQLiteDatabase, sql: String, args: List<Any?>): Long {
    db.compileStatement(sql).use { statement ->
        bind(statement, args)
        return statement.executeInsert()
    }
}

/** Binds by type, so numbers are not silently stored as text. */
private fun bind(statement: SQLiteStatement, args: List<Any?>) {
    args.forEachIndexed { index, value ->
        val position = index + 1
        when (value) {
            null -> statement.bindNull(position)
            is Long -> statement.bindLong(position, value)
            is Int -> statement.bindLong(position, value.toLong())
            is Boolean -> statement.bindLong(position, if (value) 1L else 0L)
            is ByteArray -> statement.bindBlob(position, value)
            else -> statement.bindString(position, value.toString())
        }
    }
}
