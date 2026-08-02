package it.threarth.fotosistemis.core.port

/**
 * The little of SQLite this app needs, stated without naming a driver.
 *
 * Android supplies its own SQLite classes and a desktop build would use
 * JDBC; neither can be named here. Statements are plain SQL with bound
 * arguments, which both understand, and which keeps values out of the
 * statement text.
 */
interface Database {

    /** One row, read by column name. */
    interface Row {
        fun getString(column: String): String?
        fun getLong(column: String): Long?
        fun getInt(column: String): Int?
    }

    /** Runs a query and returns every row. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>

    /** Runs a statement, returning how many rows it touched. */
    fun execute(sql: String, args: List<Any?> = emptyList()): Int

    /** Inserts a row and returns its new id. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    /**
     * Runs [block] in a transaction, rolling back if it throws.
     *
     * Every write goes through one, so a failure halfway through a batch
     * leaves no partial record of decisions the user never finished making.
     */
    fun <T> transaction(block: () -> T): T
}
