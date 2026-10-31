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
    val runtimeNotices: List<String> = if (sqlite) sqlitePermissionNotices() else emptyList()

    init {
        if (!sqlite && !jdbcUrl.startsWith("jdbc:postgresql:")) throw StorageFailure("storage.config")
    }

    private fun sqlitePermissionNotices(): List<String> {
        val path = Path.of(jdbcUrl.removePrefix("jdbc:sqlite:"))
        for (candidate in listOf(path, Path.of("$path-wal"), Path.of("$path-shm"))) {
            if (!Files.exists(candidate)) continue
            val mode = try { Files.getAttribute(candidate, "unix:mode") as Int }
                catch (_: UnsupportedOperationException) { return emptyList() }
                catch (_: java.io.IOException) { throw StorageFailure("storage.connect") }
            if (mode and 0x3f != 0) return listOf(
                "SQLite database or sidecar permissions may allow other users to read it; restrict it to the owner.")
        }
        return emptyList()
    }

    fun <T> read(block: (Connection) -> T): T = transaction(false, block)

    fun <T> write(block: (Connection) -> T): T = transaction(true, block)

    internal fun <T> writeGuarded(beforeCommit: () -> Unit, block: (Connection) -> T): T = transaction(true, block, beforeCommit)

    internal fun <T> writeNonblocking(block: (Connection) -> T): T = transaction(true, block, nonblocking = true)

    private fun <T> transaction(write: Boolean, block: (Connection) -> T, beforeCommit: () -> Unit = {}, nonblocking: Boolean = false): T {
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
                    it.execute(if (nonblocking) "PRAGMA busy_timeout=0" else "PRAGMA busy_timeout=5000")
                    it.execute("PRAGMA journal_mode=WAL")
                    // WAL + NORMAL keeps transaction atomicity while avoiding a full fsync per mutation.
                    it.execute("PRAGMA synchronous=NORMAL")
                }
                val connection=managedCashSqlConnection(decorateConnection(rawConnection),sqlite)
                val explicitSqlite = sqlite && write
                if (explicitSqlite) connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                else connection.autoCommit = false
                if (nonblocking && !sqlite) connection.createStatement().use { it.execute("SET LOCAL lock_timeout = '1ms'") }
                fun finish(commit: Boolean) {
                    if (explicitSqlite) connection.createStatement().use { it.execute(if (commit) "COMMIT" else "ROLLBACK") }
                    else if (commit) connection.commit() else connection.rollback()
                }
                try {
                    val result = block(connection)
                    if (write) beforeCommit()
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
            val tables = mutableSetOf<String>()
            connection.createStatement().use { statement ->
                val query = if (sqlite) "SELECT name FROM sqlite_master WHERE type='table'"
                    else "SELECT tablename FROM pg_tables WHERE schemaname='public'"
                statement.executeQuery(query).use { rows -> while (rows.next()) tables += rows.getString(1) }
            }
            if (requiredTables.any { it !in tables } || "alembic_version" !in tables)
                throw StorageFailure("storage.schema")
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version_num FROM alembic_version").use { rows ->
                    if (!rows.next() || rows.getString(1) != "20261028_51" || rows.next())
                        throw StorageFailure("storage.schema")
                }
            }
        } catch (failure: StorageFailure) { throw failure }
        catch (_: SQLException) { throw StorageFailure("storage.schema") }
    }

    private companion object {
        val requiredTables = setOf(
            "workspaces", "accounts", "cash_transactions", "cash_transaction_components", "investment_events",
            "ledger_snapshots", "transaction_relations", "account_aliases", "sync_cursors",
            "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members",
            "cash_projection_relations", "cash_investment_funding_relations", "users", "workspace_memberships",
            "user_sessions", "workspace_invitations", "cash_categories", "cash_category_states",
            "statement_account_mappings", "cash_source_bindings", "cash_source_empty_pages",
            "cash_transaction_issues", "cash_issue_candidates", "cash_mutation_receipts",
            "cash_mutation_results", "cash_relation_candidates",
        )
    }
}
