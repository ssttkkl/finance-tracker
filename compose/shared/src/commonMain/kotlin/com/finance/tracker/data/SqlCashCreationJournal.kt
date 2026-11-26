package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.finance.tracker.domain.CashCreationRecovery
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.serialization.json.Json

internal class SqlCashCreationJournal private constructor(private val driver: SqlDriver) {
    suspend fun read(scope: LedgerCacheScope, tokenKey: String): CashCreationRecovery? =
        readText(scope, tokenKey)?.let { Json.decodeFromString<CashCreationRecovery>(it) }

    private suspend fun readText(scope: LedgerCacheScope, tokenKey: String): String? =
        driver.executeQuery(null, """
            SELECT command_json FROM ledger_cash_creation_commands
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND token_digest = ?
        """.trimIndent(), { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
        }, 4) {
            bindString(0, scope.apiOrigin)
            bindString(1, scope.principal)
            bindString(2, scope.workspaceId)
            bindString(3, tokenKey)
        }.await()

    suspend fun swap(
        scope: LedgerCacheScope, tokenKey: String, previous: CashCreationRecovery?, next: CashCreationRecovery?,
    ): Boolean {
        val encoded = next?.let { Json.encodeToString(it) }
        val old = previous?.let { Json.encodeToString(it) }
        if (old == null) {
            checkNotNull(encoded)
            driver.execute(null, """
                INSERT OR IGNORE INTO ledger_cash_creation_commands
                (api_origin, principal, workspace_id, token_digest, command_json) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(), 5) {
                bindString(0, scope.apiOrigin)
                bindString(1, scope.principal)
                bindString(2, scope.workspaceId)
                bindString(3, tokenKey)
                bindString(4, encoded)
            }.await()
            return readText(scope, tokenKey) == encoded
        }
        if (encoded == null) {
            driver.execute(null, """
                DELETE FROM ledger_cash_creation_commands
                WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND token_digest = ? AND command_json = ?
            """.trimIndent(), 5) {
                bindString(0, scope.apiOrigin)
                bindString(1, scope.principal)
                bindString(2, scope.workspaceId)
                bindString(3, tokenKey)
                bindString(4, old)
            }.await()
            return readText(scope, tokenKey) == null
        }
        driver.execute(null, """
            UPDATE ledger_cash_creation_commands SET command_json = ?
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND token_digest = ? AND command_json = ?
        """.trimIndent(), 6) {
            bindString(0, encoded)
            bindString(1, scope.apiOrigin)
            bindString(2, scope.principal)
            bindString(3, scope.workspaceId)
            bindString(4, tokenKey)
            bindString(5, old)
        }.await()
        return readText(scope, tokenKey) == encoded
    }

    companion object {
        suspend fun open(driver: SqlDriver): SqlCashCreationJournal {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS ledger_cash_creation_commands (
                    api_origin TEXT NOT NULL,
                    principal TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    token_digest TEXT NOT NULL,
                    command_json TEXT NOT NULL,
                    PRIMARY KEY (api_origin, principal, workspace_id, token_digest)
                )
            """.trimIndent(), 0).await()
            return SqlCashCreationJournal(driver)
        }
    }
}
