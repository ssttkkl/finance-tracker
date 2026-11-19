package com.finance.tracker.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.decodeStoredImportBatch
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.contracts.CashMutationResultDto
import com.finance.tracker.business.contracts.StoredImportBatch
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.serialization.json.Json

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
            driver.execute(null, """
                CREATE TABLE IF NOT EXISTS ledger_cache_import_results (
                    api_origin TEXT NOT NULL,
                    principal TEXT NOT NULL,
                    workspace_id TEXT NOT NULL,
                    receipt_id TEXT NOT NULL,
                    batch_id TEXT NOT NULL,
                    submission_key TEXT NOT NULL,
                    result_json TEXT NOT NULL,
                    PRIMARY KEY (api_origin, principal, workspace_id, receipt_id),
                    UNIQUE (api_origin, principal, workspace_id, batch_id),
                    UNIQUE (api_origin, principal, workspace_id, submission_key)
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
        return readEncoded(scope, batchId)?.map(LocalCashImportFile::decode)?.also { validateFiles(it, allowEmpty = true) }
    }

    suspend fun readEncoded(scope: LedgerCacheScope, batchId: String): List<String>? {
        validateId(batchId)
        return readText(scope, batchId)?.let { readStoredImportBatch(it).files }
    }

    suspend fun save(scope: LedgerCacheScope, batchId: String, files: List<LocalCashImportFile>) {
        validateId(batchId)
        validateFiles(files)
        check(readResultByBatchId(scope, batchId) == null) { "cash_import_batch_completed" }
        // Keep each frozen encoding as text: no second JSON-number interpretation by Web SQL/JSON.
        val text = Json.encodeToString(files.map { it.encode() })
        val existing = readText(scope, batchId)
        if (existing != null) {
            check(readStoredImportBatch(existing).files == files.map { it.encode() }) { "cash_import_files_changed" }
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

    /** Independent appends merge into the latest list while holding the owning cache's SQL mutex. */
    suspend fun append(scope: LedgerCacheScope, batchId: String, files: List<LocalCashImportFile>) {
        validateFiles(files)
        mutateFiles(scope, batchId) { current ->
            val existing = current.associateBy { it.fileRef }
            for (file in files) existing[file.fileRef]?.let {
                check(it.encode() == file.encode()) { "cash_import_file_changed" }
            }
            current + files.filter { it.fileRef !in existing }
        }
    }

    suspend fun replaceFile(scope: LedgerCacheScope, batchId: String, expected: LocalCashImportFile, replacement: LocalCashImportFile) {
        require(expected.fileRef == replacement.fileRef) { "cash_import_file_ref_changed" }
        mutateFiles(scope, batchId) { current ->
            val original = checkNotNull(current.find { it.fileRef == expected.fileRef }) { "cash_import_file_missing" }
            if (original.encode() == replacement.encode()) current
            else {
                check(original.encode() == expected.encode()) { "cash_import_file_changed" }
                current.map { if (it.fileRef == expected.fileRef) replacement else it }
            }
        }
    }

    suspend fun removeFile(scope: LedgerCacheScope, batchId: String, expected: LocalCashImportFile) {
        mutateFiles(scope, batchId) { current ->
            val original = current.find { it.fileRef == expected.fileRef }
            if (original == null) current
            else {
                check(original.encode() == expected.encode()) { "cash_import_file_changed" }
                current.filter { it.fileRef != expected.fileRef }
            }
        }
    }

    private suspend fun mutateFiles(scope: LedgerCacheScope, batchId: String,
        change: (List<LocalCashImportFile>) -> List<LocalCashImportFile>) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = readStoredImportBatch(previous)
        check(batch.submission == null) { "cash_import_submission_pending" }
        val current = batch.files.map(LocalCashImportFile::decode)
        validateFiles(current, allowEmpty = true)
        val files = change(current)
        validateFiles(files, allowEmpty = true)
        val encoded = files.map { it.encode() }
        if (encoded == batch.files) return
        val remaining = files.associateBy { it.fileRef }
        val obsolete = current.filter { remaining[it.fileRef]?.encode() != it.encode() }.map { it.fileRef }.toSet()
        val review = batch.review?.let { LocalCashImportReview.decode(it).afterFilesChanged(obsolete).encode() }
        update(scope, batchId, previous, batch.copy(files = encoded, review = review))
    }

    suspend fun remove(scope: LedgerCacheScope, batchId: String) {
        validateId(batchId)
        val previous = readText(scope, batchId) ?: return
        check(readStoredImportBatch(previous).submission == null) { "cash_import_submission_pending" }
        delete(scope, batchId, previous)
    }

    suspend fun readSubmission(scope: LedgerCacheScope, batchId: String): LocalCashImportSubmission? {
        validateId(batchId)
        val text = readText(scope, batchId) ?: return null
        return command(batchId, readStoredImportBatch(text))
    }

    suspend fun readReview(scope: LedgerCacheScope, batchId: String): LocalCashImportReview? =
        readEncodedReview(scope, batchId)?.let(LocalCashImportReview::decode)

    suspend fun readEncodedReview(scope: LedgerCacheScope, batchId: String): String? {
        validateId(batchId)
        return readText(scope, batchId)?.let { readStoredImportBatch(it).review }
    }

    suspend fun saveReview(scope: LedgerCacheScope, batchId: String, input: LocalCashImportReview) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = readStoredImportBatch(previous)
        check(batch.submission == null) { "cash_import_submission_pending" }
        if (batch.review == input.encode()) return
        update(scope, batchId, previous, batch.copy(review = input.encode()))
    }

    suspend fun prepareSubmission(scope: LedgerCacheScope, batchId: String, input: LocalCashImportSubmission) {
        validateId(batchId)
        check(readResultByBatchId(scope, batchId) == null) { "cash_import_batch_completed" }
        require(input.idempotencyKey == batchId && input.receiptBody == null && !input.baseRejected) { "cash_import_submission_identity_invalid" }
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = readStoredImportBatch(previous)
        check(batch.files.isNotEmpty()) { "cash_import_files_empty" }
        val existing = command(batchId, batch)
        if (existing != null) {
            check(existing.requestBody == input.requestBody && existing.resultInput?.encode() == input.resultInput?.encode()) { "cash_import_submission_changed" }
            return
        }
        update(scope, batchId, previous, batch.copy(submission = encodeStoredImportSubmission(input.forAttempt(attemptKey(batchId, batch.attempt)))))
    }

    suspend fun recordBaseRejection(scope: LedgerCacheScope, batchId: String, expected: LocalCashImportSubmission) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = readStoredImportBatch(previous)
        val original = checkNotNull(command(batchId, batch)) { "cash_import_submission_missing" }
        check(expected.receiptBody == null && original.idempotencyKey == expected.idempotencyKey && original.requestBody == expected.requestBody && original.resultInput?.encode() == expected.resultInput?.encode()) {
            "cash_import_submission_changed"
        }
        val rejected = original.withBaseRejection()
        if (original.baseRejected) return
        update(scope, batchId, previous, batch.copy(submission = encodeStoredImportSubmission(rejected)))
    }

    /** Caller has refreshed beyond the old base and subsequently verified absence of its receipt. */
    suspend fun retireRejectedSubmission(scope: LedgerCacheScope, batchId: String, expected: LocalCashImportSubmission,
        newerBase: String, replacement: LocalCashImportSubmission?) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        val batch = readStoredImportBatch(previous)
        val original = checkNotNull(command(batchId, batch)) { "cash_import_submission_missing" }
        check(original.baseRejected && original.receiptBody == null && expected.receiptBody == null &&
            original.idempotencyKey == expected.idempotencyKey && original.requestBody == expected.requestBody && original.resultInput?.encode() == expected.resultInput?.encode()) { "cash_import_submission_changed" }
        fun base(text: String) = checkNotNull(text.toLongOrNull()?.takeIf { it >= 0 && it.toString() == text }) { "cash_import_base_invalid" }
        check(base(newerBase) > base(original.baseLedgerSeq)) { "cash_import_base_not_stale" }
        check(batch.attempt < Int.MAX_VALUE) { "cash_import_attempt_exhausted" }
        val attempt = batch.attempt + 1
        val next = replacement?.let {
            check(it.idempotencyKey == batchId && it.receiptBody == null && !it.baseRejected && it.baseLedgerSeq == newerBase && it.resultInput?.encode() == original.resultInput?.encode()) { "cash_import_submission_identity_invalid" }
            encodeStoredImportSubmission(it.forAttempt(attemptKey(batchId, attempt)))
        }
        update(scope, batchId, previous, batch.copy(attempt = attempt, submission = next))
    }

    suspend fun recordReceipt(scope: LedgerCacheScope, batchId: String, receipt: String) =
        recordReceipt(scope, batchId, cashWriteSetJson.decodeFromString<CashMutationReceiptDto>(receipt))

    suspend fun recordReceipt(scope: LedgerCacheScope, batchId: String, receipt: CashMutationReceiptDto) {
        validateId(batchId)
        val previous = checkNotNull(readText(scope, batchId)) { "cash_import_files_missing" }
        suspend fun prepare(inWorker: Boolean): String? {
            val batch = if (inWorker) readStoredImportBatch(previous) else decodeStoredImportBatch(previous)
            val original = checkNotNull(command(batchId, batch) { body ->
                if (inWorker) readStoredCashImportSubmission(body) else LocalCashImportSubmission.decode(body)
            }) { "cash_import_submission_missing" }
            val committed = original.withReceipt(receipt)
            if (original.receiptBody != null) return null
            val next = batch.copy(submission = if (inWorker) encodeStoredImportSubmission(committed) else committed.encode())
            return if (inWorker) encodeStoredImportBatch(next) else Json.encodeToString(next)
        }
        val text = try { prepare(true) }
        catch (failure: DomainFailure) {
            if (failure.code != "cash_import_worker_failed") throw failure
            // Persist known success even when the compute Worker cannot prepare the encrypted batch.
            try { prepare(false) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (writeFailure: Exception) { writeFailure.addSuppressed(failure); throw writeFailure }
        } ?: return
        updateText(scope, batchId, previous, text)
    }

    suspend fun completeSubmission(scope: LedgerCacheScope, batchId: String, fullSnapshotLedgerSeq: String?,
        archived: LocalCashImportResult? = null) {
        validateId(batchId)
        val previous = readText(scope, batchId) ?: return
        val submission = checkNotNull(command(batchId, readStoredImportBatch(previous))) { "cash_import_submission_missing" }
        val receipt = checkNotNull(submission.receipt()) { "cash_import_receipt_missing" }
        val required = checkNotNull(receipt.ledgerSeq).toLong()
        check(fullSnapshotLedgerSeq?.toLongOrNull()?.let { it >= required } == true) { "cash_import_snapshot_pending" }
        if (submission.resultInput != null) {
            val result = archived ?: readResultByBatchId(scope, batchId)
            check(result != null && result.batchId == batchId && result.matchesSubmission(submission) &&
                confirmsResult(scope, result, result.encode())) { "cash_import_result_pending" }
        }
        delete(scope, batchId, previous)
    }

    suspend fun archiveResult(scope: LedgerCacheScope, batchId: String, serverItems: List<CashMutationResultDto>? = null): LocalCashImportResult? =
        archiveResult(scope, batchId, serverItems != null) { assembleCashImportArchive(batchId, it, serverItems) }

    suspend fun archiveResultPages(scope: LedgerCacheScope, batchId: String, pages: List<String>): LocalCashImportResult? =
        archiveResult(scope, batchId, true) { assembleCashImportArchivePages(batchId, it, pages) }

    private suspend fun archiveResult(scope: LedgerCacheScope, batchId: String, hasServerItems: Boolean,
        assemble: suspend (LocalCashImportSubmission) -> LocalCashImportResult?): LocalCashImportResult? {
        validateId(batchId)
        val submission = checkNotNull(readSubmission(scope, batchId)) { "cash_import_submission_missing" }
        val previous = readResultByBatchId(scope, batchId)
        if (previous != null && !hasServerItems) {
            check(previous.matchesSubmission(submission) && confirmsResult(scope, previous, previous.encode())) {
                "cash_import_result_changed"
            }
            return previous
        }
        val result = assemble(submission) ?: return null
        check(result.batchId == batchId && result.matchesSubmission(submission)) { "cash_import_result_changed" }
        val text = result.encode()
        if (previous != null && !previous.hasServerItems && hasServerItems) {
            check(previous.matchesSubmission(submission)) { "cash_import_result_changed" }
            driver.execute(null, """
                UPDATE ledger_cache_import_results SET result_json = ?
                WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ? AND result_json = ?
            """.trimIndent(), 6) {
                bindString(0, text); bindString(1, scope.apiOrigin); bindString(2, scope.principal)
                bindString(3, scope.workspaceId); bindString(4, batchId); bindString(5, previous.encode())
            }.await()
        }
        driver.execute(null, """
            INSERT INTO ledger_cache_import_results (api_origin, principal, workspace_id, receipt_id, batch_id, submission_key, result_json)
            VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """.trimIndent(), 7) {
            bindScope(scope); bindString(3, result.receiptId)
            bindString(4, batchId); bindString(5, result.submissionKey); bindString(6, text)
        }.await()
        check(confirmsResult(scope, result, text)) { "cash_import_result_changed" }
        return result
    }

    private suspend fun confirmsResult(scope: LedgerCacheScope, result: LocalCashImportResult, text: String): Boolean {
        val stored = driver.executeQuery(null, """
            SELECT result_json FROM ledger_cache_import_results
            WHERE api_origin = ? AND principal = ? AND workspace_id = ?
                AND receipt_id = ? AND batch_id = ? AND submission_key = ?
        """.trimIndent(), { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) }, 6) {
            bindScope(scope)
            bindString(3, result.receiptId)
            bindString(4, result.batchId)
            bindString(5, result.submissionKey)
        }.await() ?: return false
        return stored == text || inspectCashImportArchive(stored).encode() == text
    }

    suspend fun readResult(scope: LedgerCacheScope, receiptId: String) = readResultAt(scope, "receipt_id", receiptId)
    suspend fun readResultByBatchId(scope: LedgerCacheScope, batchId: String) = readResultAt(scope, "batch_id", batchId)
    suspend fun readResultByKey(scope: LedgerCacheScope, key: String) = readResultAt(scope, "submission_key", key)

    private suspend fun readResultAt(scope: LedgerCacheScope, column: String, value: String): LocalCashImportResult? {
        val row = resultRow(scope, column, value) ?: return null
        return inspectCashImportArchive(row.body).also { check(row.matches(it)) { "cash_import_result_invalid" } }
    }

    private data class ResultRow(val body: String, val receiptId: String, val batchId: String, val submissionKey: String) {
        fun matches(result: LocalCashImportResult): Boolean =
            receiptId == result.receiptId && batchId == result.batchId && submissionKey == result.submissionKey
    }

    private suspend fun resultRow(scope: LedgerCacheScope, column: String, value: String): ResultRow? {
        check(column in setOf("receipt_id", "batch_id", "submission_key"))
        return driver.executeQuery(null, """
            SELECT result_json, receipt_id, batch_id, submission_key FROM ledger_cache_import_results
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND $column = ?
        """.trimIndent(), { cursor -> QueryResult.Value(if (cursor.next().value) {
            ResultRow(checkNotNull(cursor.getString(0)), checkNotNull(cursor.getString(1)),
                checkNotNull(cursor.getString(2)), checkNotNull(cursor.getString(3)))
        } else null) }, 4) { bindScope(scope); bindString(3, value) }.await()
    }

    private suspend fun delete(scope: LedgerCacheScope, batchId: String, previous: String) {
        driver.execute(null, """
            DELETE FROM ledger_cache_import_files
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ? AND files_json = ?
        """.trimIndent(), 5) { bindScope(scope); bindString(3, batchId); bindString(4, previous) }.await()
        // Web DML has no portable row count. The cache serializes this write and its read-back.
        check(readText(scope, batchId) == null) { "cash_import_submission_changed" }
    }

    private suspend fun update(scope: LedgerCacheScope, batchId: String, previous: String, value: StoredImportBatch) =
        updateText(scope, batchId, previous, encodeStoredImportBatch(value))

    private suspend fun updateText(scope: LedgerCacheScope, batchId: String, previous: String, text: String) {
        driver.execute(null, """
            UPDATE ledger_cache_import_files SET files_json = ?
            WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ? AND files_json = ?
        """.trimIndent(), 6) {
            bindString(0, text); bindString(1, scope.apiOrigin); bindString(2, scope.principal)
            bindString(3, scope.workspaceId); bindString(4, batchId); bindString(5, previous)
        }.await()
        check(readText(scope, batchId) == text) { "cash_import_submission_changed" }
    }

    private suspend fun command(batchId: String, batch: StoredImportBatch,
        read: suspend (String) -> LocalCashImportSubmission = ::readStoredCashImportSubmission): LocalCashImportSubmission? =
        batch.submission?.let { read(it) }?.also {
            check(it.idempotencyKey == attemptKey(batchId, batch.attempt)) { "cash_import_submission_identity_invalid" }
        }

    private fun attemptKey(batchId: String, attempt: Int) = if (attempt == 0) batchId else "$batchId:attempt:$attempt"

    private suspend fun readText(scope: LedgerCacheScope, batchId: String): String? = driver.executeQuery(null, """
        SELECT files_json FROM ledger_cache_import_files
        WHERE api_origin = ? AND principal = ? AND workspace_id = ? AND batch_id = ?
    """.trimIndent(), { cursor -> QueryResult.Value(if (cursor.next().value) checkNotNull(cursor.getString(0)) else null) },
        4) { bindScope(scope); bindString(3, batchId) }.await()

    private fun validateId(batchId: String) {
        require(batchId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "cash_import_batch_id_invalid" }
    }

    private fun validateFiles(files: List<LocalCashImportFile>, allowEmpty: Boolean = false) {
        require(allowEmpty || files.isNotEmpty()) { "cash_import_files_empty" }
        require(files.size <= 20) { "cash_import_files_too_many" }
        require(files.map { it.fileRef }.toSet().size == files.size) { "cash_import_file_ref_duplicate" }
    }

    private fun SqlPreparedStatement.bindScope(scope: LedgerCacheScope) {
        bindString(0, scope.apiOrigin); bindString(1, scope.principal); bindString(2, scope.workspaceId)
    }
}
