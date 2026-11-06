package com.finance.tracker.data

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.LogTarget
import net.zetetic.database.Logger
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

internal class AndroidLedgerDriverOpenCloseFailure(opening: Throwable, closing: Throwable) :
    IllegalStateException("ledger_database_close_uncertain", opening) {
    init { addSuppressed(closing) }
}

private class LedgerInitializationFailure(val original: Throwable) : SQLiteException("ledger_database_initialization_failed") {
    init { initCause(original) }
}

/** No SQL or exception is printed. Observers belong only to this thread's current SDK operation. */
private object LedgerCipherLogs : LogTarget {
    private val observer = ThreadLocal<((String?, Throwable) -> Unit)?>()
    override fun isLoggable(tag: String?, priority: Int) = false
    override fun log(priority: Int, tag: String?, message: String?, throwable: Throwable?) {
        if (priority >= Logger.ERROR && throwable != null && throwable !is LedgerInitializationFailure) {
            observer.get()?.invoke(tag, throwable)
        }
    }
    fun install() { Logger.setTarget(this) }
    fun <T> observe(report: (String?, Throwable) -> Unit, block: () -> T): T {
        install()
        val prior = observer.get()
        observer.set(report)
        try { return block() }
        finally { if (prior == null) observer.remove() else observer.set(prior) }
    }
}

/** Caller owns the private directory, range lease, and serialized cache/transaction lifecycle. */
internal fun openAndroidLedgerSqlDriver(
    directory: File,
    key: ByteArray,
    afterKey: (SQLiteConnection) -> Unit = {},
    closeDatabase: (SQLiteDatabase) -> Unit = { it.close() },
): SqlDriver {
    require(key.size == 32) { "ledger_key_invalid" }
    require(directory.isAbsolute && '\u0000' !in directory.path) { "ledger_directory_invalid" }
    LedgerCipherLogs.install()
    System.loadLibrary("sqlcipher")
    return AndroidLedgerSqlOwner(key.copyOf(), closeDatabase).open(directory, afterKey)
}

private class AndroidLedgerSqlOwner(
    private val key: ByteArray,
    private val closeDatabase: (SQLiteDatabase) -> Unit,
) {
    private val monitor = Any()
    private val connections = mutableListOf<SQLiteConnection>()
    private var database: SQLiteDatabase? = null
    private var native: SqlDriver? = null
    private var closing = false
    private var databaseCloseStarted = false
    private var closeFailure: IllegalStateException? = null
    private var initializationFailure: LedgerInitializationFailure? = null

    private fun record(failure: Throwable) {
        if (failure === closeFailure || failure === closeFailure?.cause) return
        val known = closeFailure
        if (known == null) closeFailure = IllegalStateException("ledger_database_close_uncertain", failure)
        else known.addSuppressed(failure)
        synchronized(uncertainOwners) { uncertainOwners.add(this) }
    }

    fun open(directory: File, afterKey: (SQLiteConnection) -> Unit): SqlDriver {
        try {
            return LedgerCipherLogs.observe({ _, failure -> record(failure) }) {
                val raw = SQLiteDatabase.openDatabase(File(directory, "ledger.db").path, key, null,
                    SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    DatabaseErrorHandler { _, failure -> throw failure }, object : SQLiteDatabaseHook {
                        override fun preKey(connection: SQLiteConnection) { connections.add(connection) }
                        override fun postKey(connection: SQLiteConnection) {
                            try {
                                check(connection.executeForString("PRAGMA cipher_version", null, null) == "4.20.0 community") {
                                    "ledger_cipher_version_invalid"
                                }
                                connection.executeForLong("SELECT COUNT(*) FROM sqlite_schema", null, null)
                                connection.execute("PRAGMA temp_store=MEMORY", null, null)
                                check(connection.executeForLong("PRAGMA temp_store", null, null) == 2L) {
                                    "ledger_temporary_storage_invalid"
                                }
                                afterKey(connection)
                            } catch (failure: Throwable) {
                                val translated = LedgerInitializationFailure(failure)
                                initializationFailure = translated
                                throw translated
                            }
                        }
                    })
                database = raw
                raw.rawQuery("PRAGMA journal_mode=DELETE", null as Array<String>?).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0) == "delete") { "ledger_journal_mode_invalid" }
                }
                val managed = object : SupportSQLiteDatabase by raw {
                    override fun close() = closeDatabaseOnce()
                    override fun endTransaction() = operation { raw.endTransaction() }
                    override fun setTransactionSuccessful() = operation { raw.setTransactionSuccessful() }
                }
                val delegate = AndroidSqliteDriver(managed)
                native = delegate
                closeFailure?.let { throw it }
                object : SqlDriver by delegate {
                    override fun close() = this@AndroidLedgerSqlOwner.close()
                    override fun execute(identifier: Int?, sql: String, parameters: Int,
                        binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
                        operation { delegate.execute(identifier, sql, parameters, binders) }
                    override fun <R> executeQuery(identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>,
                        parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<R> =
                        operation { delegate.executeQuery(identifier, sql, mapper, parameters, binders) }
                    override fun newTransaction(): QueryResult<Transacter.Transaction> = operation { delegate.newTransaction() }
                    override fun currentTransaction(): Transacter.Transaction? = operation { delegate.currentTransaction() }
                }
            }
        } catch (failure: Throwable) {
            val original = initializationFailure?.original ?: failure
            if (database != null) {
                try { close() } catch (cleanup: Throwable) { record(cleanup) }
            } else if (failure !== initializationFailure) {
                // Public open never delivered an owner and its expected cleanup path was not confirmed.
                record(failure)
            }
            val uncertain = closeFailure
            if (uncertain != null) throw AndroidLedgerDriverOpenCloseFailure(original, uncertain)
            key.fill(0)
            connections.clear()
            throw original
        }
    }

    private fun <T> operation(block: () -> T): T = synchronized(monitor) {
        check(!closing) { "ledger_database_closed" }
        closeFailure?.let { throw it }
        initializationFailure = null
        try {
            val result = LedgerCipherLogs.observe({ tag, failure ->
                if (tag == "SQLiteConnectionPool") record(failure)
            }, block)
            closeFailure?.let { throw it }
            result
        } catch (failure: Throwable) {
            val initialized = initializationFailure
            if (initialized != null && failure !== initialized) record(failure)
            closeFailure?.let { throw it }
            throw (initialized?.original ?: failure)
        }
    }

    private fun closeDatabaseOnce() {
        if (databaseCloseStarted) return
        databaseCloseStarted = true
        closeDatabase(checkNotNull(database))
    }

    private fun close() = synchronized(monitor) {
        if (closing) {
            closeFailure?.let { throw it }
            return@synchronized
        }
        closing = true
        LedgerCipherLogs.observe({ _, failure -> record(failure) }) {
            try { native?.close() } catch (failure: Throwable) { record(failure) }
            if (!databaseCloseStarted) {
                try { closeDatabaseOnce() } catch (failure: Throwable) { record(failure) }
            }
        }
        if (database?.isOpen == true) record(IllegalStateException("ledger_database_remains_open"))
        closeFailure?.let { throw it }
        key.fill(0)
        connections.clear()
    }

    private companion object {
        // Prevent SDK finalizers from retrying closure of an unconfirmed native resource.
        val uncertainOwners = mutableSetOf<AndroidLedgerSqlOwner>()
    }
}
