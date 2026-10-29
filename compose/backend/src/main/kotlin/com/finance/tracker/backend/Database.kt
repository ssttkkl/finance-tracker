package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

class StorageFailure(val code: String) : RuntimeException(code)

internal fun storageErrorCode(failure: SQLException): String {
    if (failure.sqlState != null) return when (failure.sqlState) {
        "55P03" -> "storage.busy"
        "25006" -> "storage.readonly"
        else -> "storage.connect"
    }
    if (failure is org.sqlite.SQLiteException || failure.errorCode != 0) return when (failure.errorCode and 0xff) {
        5, 6 -> "storage.busy"
        8 -> "storage.readonly"
        else -> "storage.connect"
    }
    val message = failure.message.orEmpty().lowercase()
    return when {
        "locked" in message || "busy" in message -> "storage.busy"
        "readonly" in message || "read-only" in message -> "storage.readonly"
        else -> "storage.connect"
    }
}

/** Server-only SQL boundary. Existing databases are required; schema is never created here. */
class Database(private val jdbcUrl: String, private val user: String? = null, private val password: String? = null,
    private val decorateConnection: (Connection)->Connection = {it}) {
    val sqlite = jdbcUrl.startsWith("jdbc:sqlite:")

    init {
        if (!sqlite && !jdbcUrl.startsWith("jdbc:postgresql:")) throw StorageFailure("storage.config")
    }

    fun <T> read(block: (Connection) -> T): T = transaction(false, block)

    fun <T> write(block: (Connection) -> T): T = transaction(true, block)

    private fun <T> transaction(write: Boolean, block: (Connection) -> T): T {
        try {
            val url = if (sqlite) {
                val path = Path.of(jdbcUrl.removePrefix("jdbc:sqlite:"))
                if (!path.isAbsolute || !Files.isRegularFile(path)) throw StorageFailure("storage.connect")
                "jdbc:sqlite:file:${path.toUri().rawPath}?mode=rw"
            } else jdbcUrl
            val properties = java.util.Properties().apply {
                user?.let { setProperty("user", it) }
                password?.let { setProperty("password", it) }
                if (!sqlite) { setProperty("connectTimeout", "10"); setProperty("socketTimeout", "30") }
            }
            DriverManager.getConnection(url, properties).use { rawConnection ->
                if (sqlite) rawConnection.createStatement().use {
                    it.execute("PRAGMA foreign_keys=ON")
                    it.execute("PRAGMA busy_timeout=5000")
                }
                val connection=managedCashSqlConnection(decorateConnection(rawConnection),sqlite)
                val explicitSqlite = sqlite && write
                if (explicitSqlite) connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                else connection.autoCommit = false
                fun finish(commit: Boolean) {
                    if (explicitSqlite) connection.createStatement().use { it.execute(if (commit) "COMMIT" else "ROLLBACK") }
                    else if (commit) connection.commit() else connection.rollback()
                }
                try {
                    val result = block(connection)
                    finish(write)
                    return result
                } catch (failure: Throwable) {
                    try { finish(false) } catch (rollback: SQLException) { failure.addSuppressed(rollback) }
                    throw failure
                }
            }
        } catch (failure: SQLException) {
            throw StorageFailure(storageErrorCode(failure))
        } catch (failure: org.jooq.exception.DataAccessException) {
            val sql = failure.sqlCause() ?: throw failure
            throw StorageFailure(storageErrorCode(sql))
        }
    }

    fun validateSchema() = read { connection ->
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version_num FROM alembic_version").use { rows ->
                    if (!rows.next() || rows.getString(1) != "20261028_51" || rows.next())
                        throw StorageFailure("storage.schema")
                }
            }
        } catch (_: SQLException) { throw StorageFailure("storage.schema") }
    }
}
