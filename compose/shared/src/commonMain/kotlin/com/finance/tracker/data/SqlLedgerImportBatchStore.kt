package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** SQL only. The cache supplies the encrypted driver, scope, serialization and close fence. */
internal class SqlLedgerImportBatchStore private constructor(private val driver: SqlDriver) {
    companion object {
        suspend fun open(driver: SqlDriver): SqlLedgerImportBatchStore {
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
            return SqlLedgerImportBatchStore(driver)
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
        return stored(text).files.map(LocalCashImportFile::decode).also(::validateFiles)
    }

    suspend fun save(scope: LedgerCacheScope, batchId: String, files: List<LocalCashImportFile>) {
        validateId(batchId)
        validateFiles(files)
        // Keep each frozen encoding as text: no second JSON-number interpretation by Web SQL/JSON.
        val text = Json.encodeToString(files.map { it.encode() })
        val existing = readText(scope, batchId)
        if (existing != null) {
            check(stored(existing).files == files.map { it.encode() }) { "cash_import_files_changed" }
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
        val previous = readText(scope, batchId) ?: return
        check(stored(previous).submission == null) { "cash_import_submission_pending" }
        delete(scope, batchId, previous)
    }

    suspend fun readSubmission(scope: LedgerCacheScope, batchId: String): LocalCashImportSubmission? {
        validateId(batchId)
        val text = readText(scope, batchId) ?: return null
        return command(batchId, stored(text))
    }

    suspend fun readReview(scope: LedgerCacheScope, batchId: String): LocalCashImportReview? {
        validateId(batchId)
        val text = readText(scope, batchId) ?: return null
        return stored(text).review?.let(LocalCashImportReview::decode)
    }

    suspend fun saveReview(scope: LedgerCacheScope, batchId: String, input: LocalCashImportReview) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = stored(previous)
        check(batch.submission == null) { "cash_import_submission_pending" }
        if (batch.review == input.encode()) return
        update(scope, batchId, previous, batch.copy(review = input.encode()))
    }

    suspend fun prepareSubmission(scope: LedgerCacheScope, batchId: String, input: LocalCashImportSubmission) {
        validateId(batchId)
        require(input.idempotencyKey == batchId && input.receiptBody == null) { "cash_import_submission_identity_invalid" }
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = stored(previous)
        val existing = command(batchId, batch)
        if (existing != null) {
            check(existing.requestBody == input.requestBody) { "cash_import_submission_changed" }
            return
        }
        update(scope, batchId, previous, batch.copy(submission = input.encode()))
    }

    suspend fun recordReceipt(scope: LedgerCacheScope, batchId: String, receipt: String) {
        validateId(batchId)
        val confirmed = cashWriteSetJson.decodeFromString<com.finance.tracker.business.contracts.CashMutationReceiptDto>(receipt)
        validateCashWriteSetReceipt(confirmed)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = stored(previous)
        val original = checkNotNull(command(batchId, batch)) { "cash_import_submission_missing" }
        val committed = original.withReceipt(confirmed)
        if (original.receiptBody != null) return
        update(scope, batchId, previous, batch.copy(submission = committed.encode()))
    }

    suspend fun completeSubmission(scope: LedgerCacheScope, batchId: String, fullSnapshotLedgerSeq: String?) {
        validateId(batchId)
        val previous = readText(scope, batchId) ?: return
        val receipt = checkNotNull(command(batchId, stored(previous))?.receipt()) { "cash_import_receipt_missing" }
        val required = checkNotNull(receipt.ledgerSeq).toLong()
        check(fullSnapshotLedgerSeq?.toLongOrNull()?.let { it >= required } == true) { "cash_import_snapshot_pending" }
        delete(scope, batchId, previous)
    }

    private suspend fun delete(scope: LedgerCacheScope, batchId: String, previous: String) {
        driver.execute(null, """
            DELETE FROM ledger_cache_import_files
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ? AND files_json = ?
        """.trimIndent(), 5) { bindScope(scope); bindString(3, batchId); bindString(4, previous) }.await()
        // Web DML has no portable row count. The cache serializes this write and its read-back.
        check(readText(scope, batchId) == null) { "cash_import_submission_changed" }
    }

    private suspend fun update(scope: LedgerCacheScope, batchId: String, previous: String, value: StoredImportBatch) {
        val text = Json.encodeToString(value)
        driver.execute(null, """
            UPDATE ledger_cache_import_files SET files_json = ?
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ? AND files_json = ?
        """.trimIndent(), 6) {
            bindString(0, text); bindString(1, scope.apiOrigin); bindString(2, scope.principal)
            bindString(3, scope.workspaceId); bindString(4, batchId); bindString(5, previous)
        }.await()
        check(readText(scope, batchId) == text) { "cash_import_submission_changed" }
    }

    private fun stored(text: String): StoredImportBatch {
        val batch = if (Json.parseToJsonElement(text) is JsonArray) StoredImportBatch(1, Json.decodeFromString(text))
            else Json.decodeFromString<StoredImportBatch>(text)
        check(batch.version == 1 && batch.files.isNotEmpty()) { "cash_import_batch_invalid" }
        return batch
    }

    private fun command(batchId: String, batch: StoredImportBatch): LocalCashImportSubmission? =
        batch.submission?.let(LocalCashImportSubmission::decode)?.also {
            check(it.idempotencyKey == batchId) { "cash_import_submission_identity_invalid" }
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

@Serializable
private data class StoredImportBatch(val version: Int, val files: List<String>, val submission: String? = null, val review: String? = null)
