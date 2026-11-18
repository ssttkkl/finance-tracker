package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.WORKSPACES
import java.sql.Connection

internal class LedgerBaseChanged(val expected: Long, val current: Long) : RuntimeException("ledger.base_changed")

/** One version advance per workspace and physical transaction. */
internal class LedgerTransactionVersions(private val connection: Connection, private val sqlite: Boolean) {
    private data class SavepointState(val key: Any, val versions: Map<String, Long>)
    private val changed = mutableMapOf<String, Long>()
    private val savepoints = mutableListOf<SavepointState>()

    fun clear() { changed.clear(); savepoints.clear() }
    fun savepoint(key: Any) { savepoints += SavepointState(key, changed.toMap()) }
    private fun position(key: Any): Int = savepoints.indexOfLast { it.key == key }.also {
        check(it >= 0) { "ledger.savepoint_missing" }
    }
    fun rollbackTo(key: Any) {
        val position = position(key)
        changed.clear()
        changed.putAll(savepoints[position].versions)
        savepoints.subList(position + 1, savepoints.size).clear()
    }
    fun release(key: Any) {
        val position = position(key)
        savepoints.subList(position, savepoints.size).clear()
    }
    private fun lockedVersion(workspace: String): Long? {
        val query = connection.jooq(sqlite).select(WORKSPACES.LEDGER_SEQ).from(WORKSPACES).where(WORKSPACES.ID.eq(workspace))
        return (if (sqlite) query else query.forUpdate()).fetchOne(WORKSPACES.LEDGER_SEQ)
    }
    fun requireBase(workspace: String, expected: Long) {
        require(expected >= 0) { "ledger.base_invalid" }
        val current = lockedVersion(workspace) ?: throw AccessFailure(true)
        check(current >= 0) { "ledger.version_invalid" }
        if (current != expected) throw LedgerBaseChanged(expected, current)
    }
    fun mark(workspace: String): Long {
        changed[workspace]?.let { return it }
        val sql = connection.jooq(sqlite)
        val current = checkNotNull(lockedVersion(workspace)) { "ledger.workspace_missing" }
        check(current >= 0) { "ledger.version_invalid" }
        val next = Math.addExact(current, 1L)
        check(sql.update(WORKSPACES).set(WORKSPACES.LEDGER_SEQ, next)
            .where(WORKSPACES.ID.eq(workspace)).and(WORKSPACES.LEDGER_SEQ.eq(current)).execute() == 1) { "ledger.version_changed_inside_transaction" }
        changed[workspace] = next
        return next
    }
}

internal fun Connection.markLedgerChanged(workspace: String): Long =
    (this as? CashSqlTransactionBoundary ?: error("ledger.managed_transaction_required")).cashMarkLedgerChanged(workspace)

/** Call after authorization and receipt replay, inside the write transaction that persists the facts. */
internal fun Connection.requireLedgerBase(workspace: String, expected: Long) =
    (this as? CashSqlTransactionBoundary ?: error("ledger.managed_transaction_required")).cashRequireLedgerBase(workspace, expected)
