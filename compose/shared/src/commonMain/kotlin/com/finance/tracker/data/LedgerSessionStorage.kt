package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerFactCache
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Local identity history, not a new server authorization. Its owner also owns the encrypted facts. */
internal interface LedgerStorage : LedgerFactCache {
    /** Fence this owner, close its connection and release its lease without erasing facts or keys. */
    suspend fun close()
    suspend fun readOfflineSession(scope: LedgerCacheScope, token: String): SessionDto?
    suspend fun saveOfflineSession(scope: LedgerCacheScope, token: String, session: SessionDto)
}

internal fun ledgerOfflinePrincipal(email: String): String {
    require(email.isNotBlank()) { "ledger_session_identity_invalid" }
    return sha256Hex(("ledger-offline-principal-v1:" + email).encodeToByteArray(throwOnInvalidSequence = true))
}

private fun tokenDigest(token: String): String {
    require(token.isNotBlank()) { "ledger_session_token_invalid" }
    return sha256Hex(("ledger-offline-token-v1:" + token).encodeToByteArray(throwOnInvalidSequence = true))
}

private fun validateSession(scope: LedgerCacheScope, session: SessionDto) {
    check(ledgerOfflinePrincipal(session.user.email) == scope.principal &&
        session.activeWorkspaceId == scope.workspaceId &&
        session.workspaces.count { it.id == scope.workspaceId } == 1) { "ledger_session_scope_invalid" }
}

@Serializable
private data class StoredLedgerSession(val tokenDigest: String, val session: SessionDto)

/** The caller supplies encryption, committed-fact eligibility, serialization and the close fence. */
internal class SqlLedgerSessionStore private constructor(private val driver: SqlDriver) {
    companion object {
        suspend fun open(driver: SqlDriver): SqlLedgerSessionStore {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS ledger_cache_sessions (
                    api_origin TEXT NOT NULL,
                    principal TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    session_json TEXT NOT NULL,
                    PRIMARY KEY (api_origin, principal, workspace_id)
                )
            """.trimIndent(), 0).await()
            return SqlLedgerSessionStore(driver)
        }
    }

    suspend fun read(scope: LedgerCacheScope, token: String): SessionDto? {
        val digest = tokenDigest(token)
        val text = driver.executeQuery(null, """
            SELECT session_json FROM ledger_cache_sessions
            WHERE api_origin = ? AND principal = ? AND workspace_id = ?
        """.trimIndent(), { cursor ->
            QueryResult.Value(if (cursor.next().value) checkNotNull(cursor.getString(0)) else null)
        }, 3) { bindScope(scope) }.await() ?: return null
        val stored = Json.decodeFromString<StoredLedgerSession>(text)
        check(stored.tokenDigest.length == 64 && stored.tokenDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ledger_session_token_invalid"
        }
        validateSession(scope, stored.session)
        return stored.session.takeIf { stored.tokenDigest == digest }
    }

    suspend fun save(scope: LedgerCacheScope, token: String, session: SessionDto) {
        validateSession(scope, session)
        val text = Json.encodeToString(StoredLedgerSession(tokenDigest(token), session))
        driver.execute(null, """
            INSERT INTO ledger_cache_sessions (api_origin, principal, workspace_id, session_json)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (api_origin, principal, workspace_id)
            DO UPDATE SET session_json = excluded.session_json
        """.trimIndent(), 4) { bindScope(scope); bindString(3, text) }.await()
    }

    private fun SqlPreparedStatement.bindScope(scope: LedgerCacheScope) {
        bindString(0, scope.apiOrigin); bindString(1, scope.principal); bindString(2, scope.workspaceId)
    }
}
