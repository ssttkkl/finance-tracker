@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.DatabaseManager
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.interop.Logger
import co.touchlab.sqliter.stringForQuery
import com.finance.tracker.data.cipher.sqlite3_key
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSRecursiveLock

/** Failed open with unconfirmed resource cleanup; its file lease must remain held. */
internal class AppleLedgerDriverOpenCloseFailure(opening: Throwable, closing: Throwable) :
    IllegalStateException("ledger_database_close_uncertain", opening) {
    init { addSuppressed(closing) }
}

private fun failedOpen(failure: Throwable, close: () -> Unit): Nothing {
    try { close() }
    catch (cleanup: Throwable) { throw AppleLedgerDriverOpenCloseFailure(failure, cleanup) }
    throw failure
}

/** The caller owns the scope directory, file lease and serialized cache lifecycle. */
internal fun openAppleLedgerSqlDriver(
    directory: String,
    key: ByteArray,
    createManager: (DatabaseConfiguration) -> DatabaseManager = ::createDatabaseManager,
): SqlDriver {
    require(key.size == 32) { "A ledger encryption key must contain 32 bytes" }
    val privateKey = key.copyOf()
    val manager = try {
        LedgerCipherDatabaseManager(directory, privateKey, createManager)
    } catch (failure: Throwable) {
        privateKey.fill(0)
        throw failure
    }
    val native = try {
        NativeSqliteDriver(manager)
    } catch (failure: Throwable) {
        failedOpen(failure) { manager.closeConnections() }
    }
    val owned = object : SqlDriver by native {
        override fun close() {
            manager.stopOpening()
            try {
                native.close()
            } catch (error: Throwable) {
                manager.recordCloseFailure(error)
            }
            manager.closeConnections()
        }
    }
    try {
        // Open before handing ownership to the cache, including wrong-key validation.
        native.executeQuery(null, "SELECT count(*) FROM sqlite_master", { cursor ->
            check(cursor.next().value)
            QueryResult.Value(Unit)
        }, 0)
        return owned
    } catch (failure: Throwable) {
        failedOpen(failure) { owned.close() }
    }
}

private class LedgerCipherDatabaseManager(
    directory: String,
    private val key: ByteArray,
    createManager: (DatabaseConfiguration) -> DatabaseManager,
) : DatabaseManager {
    private val lock = NSRecursiveLock()
    private val connections = mutableListOf<DatabaseConnection>()
    private var opening: DatabaseConnection? = null
    private var stopping = false
    private var uncertainClose: Throwable? = null

    override val configuration = DatabaseConfiguration(
        name = "ledger.db",
        version = 1,
        create = {},
        journalMode = JournalMode.DELETE,
        extendedConfig = DatabaseConfiguration.Extended(basePath = directory),
        loggingConfig = DatabaseConfiguration.Logging(logger = SilentLedgerSqlLogger),
        lifecycleConfig = DatabaseConfiguration.Lifecycle(onCreateConnection = { connection ->
            opening = connection
            connections.add(connection)
            val result = key.usePinned { sqlite3_key(connection.getDbPointer(), it.addressOf(0), key.size) }
            check(result == 0) { "SQLCipher rejected ledger key initialization ($result)" }
            check(connection.stringForQuery("PRAGMA cipher_version") == "4.20.0 community") {
                "Unexpected ledger encryption engine"
            }
            connection.rawExecSql("PRAGMA temp_store=MEMORY")
            check(connection.stringForQuery("PRAGMA temp_store") == "2") {
                "Ledger temporary storage must remain in memory"
            }
            // This reads the encrypted header before SQLiter's journal/migration setup.
            connection.stringForQuery("SELECT count(*) FROM sqlite_master")
        }),
    )
    private val delegate = createManager(configuration)

    override fun createMultiThreadedConnection(): DatabaseConnection = create { delegate.createMultiThreadedConnection() }

    override fun createSingleThreadedConnection(): DatabaseConnection = create { delegate.createSingleThreadedConnection() }

    private fun create(block: () -> DatabaseConnection): DatabaseConnection = locked {
        check(!stopping) { "Ledger database is closing" }
        check(opening == null)
        try {
            block().also { connection ->
                check(connection.stringForQuery("PRAGMA journal_mode") == "delete") {
                    "Unexpected ledger journal mode"
                }
            }
        } catch (failure: Throwable) {
            // SQLiter can throw before the pool receives the newly opened handle.
            opening?.let { connection ->
                try {
                    if (!connection.closed) connection.close()
                } catch (cleanup: Throwable) {
                    recordCloseFailure(cleanup)
                    failure.addSuppressed(cleanup)
                }
            }
            throw failure
        } finally {
            opening = null
        }
    }

    fun stopOpening() = locked { stopping = true }

    fun recordCloseFailure(failure: Throwable) = locked {
        stopping = true
        val prior = uncertainClose
        if (prior == null) uncertainClose = failure
        else if (prior !== failure) prior.addSuppressed(failure)
    }

    fun closeConnections() = locked {
        stopping = true
        for (connection in connections) {
            try {
                if (!connection.closed) connection.close()
            } catch (error: Throwable) {
                recordCloseFailure(error)
            }
        }
        if (connections.any { !it.closed }) {
            recordCloseFailure(IllegalStateException("Ledger database connections remain open"))
        }
        // SQLiter sets closed before its C close call. An exception cannot be
        // cleared by a later no-op close, even when that flag has become true.
        uncertainClose?.let { throw it }
        key.fill(0)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

private object SilentLedgerSqlLogger : Logger {
    override val vActive = false
    override val eActive = false
    override fun trace(message: String) = Unit
    override fun vWrite(message: String) = Unit
    override fun eWrite(message: String, exception: Throwable?) = Unit
}
