package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.serialization.json.Json

/** SQL storage only. Its owner must supply an encrypted driver and erase the file/key on logout. */
internal class SqlLedgerSnapshotStore private constructor(private val driver: SqlDriver) {
    companion object {
        suspend fun open(driver: SqlDriver): SqlLedgerSnapshotStore {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS ledger_cache_snapshots (
                    api_origin TEXT NOT NULL,
                    principal TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    snapshot_json TEXT NOT NULL,
                    PRIMARY KEY (api_origin, principal, workspace_id)
                )
            """.trimIndent(), 0).await()
            return SqlLedgerSnapshotStore(driver)
        }
    }

    suspend fun read(scope: LedgerCacheScope): LedgerSnapshotDto? {
        val text = driver.executeQuery(null, """
            SELECT snapshot_json FROM ledger_cache_snapshots
            WHERE api_origin = ? AND principal = ? AND workspace_id = ?
        """.trimIndent(), { cursor ->
            QueryResult.Value(if (cursor.next().value) checkNotNull(cursor.getString(0)) else null)
        }, 3) { bindScope(scope) }.await() ?: return null
        return Json.decodeFromString<LedgerSnapshotDto>(text).also { validate(scope, it) }
    }

    suspend fun replace(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
        validate(scope, snapshot)
        // One TEXT value keeps the facts, ledger sequence and rule version in the same atomic write.
        // IDs, decimal lexemes and open JSON never pass through the Web driver's Double bindings.
        val text = Json.encodeToString(snapshot)
        driver.execute(null, """
            INSERT INTO ledger_cache_snapshots (api_origin, principal, workspace_id, snapshot_json)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (api_origin, principal, workspace_id)
            DO UPDATE SET snapshot_json = excluded.snapshot_json
        """.trimIndent(), 4) { bindScope(scope); bindString(3, text) }.await()
    }

    /** Removes SQL facts only; not the file, free pages, encryption key or pending user intents. */
    suspend fun remove(scope: LedgerCacheScope) {
        driver.execute(null, """
            DELETE FROM ledger_cache_snapshots
            WHERE api_origin = ? AND principal = ? AND workspace_id = ?
        """.trimIndent(), 3) { bindScope(scope) }.await()
    }

    private fun validate(scope: LedgerCacheScope, snapshot: LedgerSnapshotDto) {
        require(snapshot.workspaceId == scope.workspaceId) { "ledger_snapshot_workspace_mismatch" }
        require(snapshot.ledgerSeq.toLongOrNull()?.let { it >= 0 } == true) { "ledger_version_invalid" }
    }

    private fun SqlPreparedStatement.bindScope(scope: LedgerCacheScope) {
        bindString(0, scope.apiOrigin); bindString(1, scope.principal); bindString(2, scope.workspaceId)
    }
}
