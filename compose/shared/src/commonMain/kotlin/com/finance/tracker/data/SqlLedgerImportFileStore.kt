package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.serialization.json.Json

/** SQL only. The cache supplies the encrypted driver, scope, serialization and close fence. */
internal class SqlLedgerImportFileStore private constructor(private val driver: SqlDriver) {
    companion object {
        suspend fun open(driver: SqlDriver): SqlLedgerImportFileStore {
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS ledger_cache_import_files (
                    api_origin TEXT NOT NULL,
                    principal TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    batch_id TEXT NOT NULL,
                    files_json TEXT NOT NULL,
                    PRIMARY KEY (api_origin, principal, workspace_id, batch_id)
                )
            """.trimIndent(), 0).await()
            return SqlLedgerImportFileStore(driver)
        }
    }

    suspend fun list(scope: LedgerCacheScope): List<String> = driver.executeQuery(null, """
        SELECT batch_id FROM ledger_cache_import_files
        WHERE api_origin = ? AND principal = ? AND workspace_id = ? ORDER BY batch_id
    """.trimIndent(), { cursor -> QueryResult.Value(buildList {
        while (cursor.next().value) add(checkNotNull(cursor.getString(0)))
    }) }, 3) { bindScope(scope) }.await()

    suspend fun read(scope: LedgerCacheScope, batchId: String): List<LocalCashImportFile>? {
        validateId(batchId)
        val text = readText(scope, batchId) ?: return null
        return Json.decodeFromString<List<String>>(text).map(LocalCashImportFile::decode).also(::validateFiles)
    }

    suspend fun save(scope: LedgerCacheScope, batchId: String, files: List<LocalCashImportFile>) {
        validateId(batchId)
        validateFiles(files)
        // Keep each frozen encoding as text: no second JSON-number interpretation by Web SQL/JSON.
        val text = Json.encodeToString(files.map { it.encode() })
        val existing = readText(scope, batchId)
        if (existing != null) {
            check(existing == text) { "cash_import_files_changed" }
            return
        }
        // One insert publishes the entire ordered batch. A competing insert cannot replace it.
        driver.execute(null, """
            INSERT INTO ledger_cache_import_files (api_origin, principal, workspace_id, batch_id, files_json)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (api_origin, principal, workspace_id, batch_id) DO NOTHING
        """.trimIndent(), 5) { bindScope(scope); bindString(3, batchId); bindString(4, text) }.await()
        check(readText(scope, batchId) == text) { "cash_import_files_changed" }
    }

    suspend fun remove(scope: LedgerCacheScope, batchId: String) {
        validateId(batchId)
        driver.execute(null, """
            DELETE FROM ledger_cache_import_files
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ?
        """.trimIndent(), 4) { bindScope(scope); bindString(3, batchId) }.await()
    }

    private suspend fun readText(scope: LedgerCacheScope, batchId: String): String? = driver.executeQuery(null, """
        SELECT files_json FROM ledger_cache_import_files
        WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ?
    """.trimIndent(), { cursor -> QueryResult.Value(if (cursor.next().value) checkNotNull(cursor.getString(0)) else null) },
        4) { bindScope(scope); bindString(3, batchId) }.await()

    private fun validateId(batchId: String) {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "cash_import_batch_id_invalid" }
    }

    private fun validateFiles(files: List<LocalCashImportFile>) {
        require(files.isNotEmpty()) { "cash_import_files_empty" }
        require(files.map { it.fileRef }.toSet().size == files.size) { "cash_import_file_ref_duplicate" }
    }

    private fun SqlPreparedStatement.bindScope(scope: LedgerCacheScope) {
        bindString(0, scope.apiOrigin); bindString(1, scope.principal); bindString(2, scope.workspaceId)
    }
}
