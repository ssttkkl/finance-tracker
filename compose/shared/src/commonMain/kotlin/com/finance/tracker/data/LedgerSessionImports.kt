package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Import operations on one original owner; this handle never acquires or closes a cache. */
internal class LedgerSessionImports(
    private val owner: LedgerSessionCacheOwner,
    private val checkCurrent: suspend (requiresFacts: Boolean) -> Unit,
    private val recover: suspend (suspend () -> LocalCashImportSubmissionResult) -> LocalCashImportSubmissionResult = { it() },
    private val onFailure: suspend (Throwable) -> Nothing = { throw it },
) {
    internal fun guardedBy(check: suspend (Boolean) -> Unit,
        recover: suspend (suspend () -> LocalCashImportSubmissionResult) -> LocalCashImportSubmissionResult,
        onFailure: suspend (Throwable) -> Nothing = this.onFailure): LedgerSessionImports =
        LedgerSessionImports(owner, { requiresFacts -> check(requiresFacts); checkCurrent(requiresFacts) }, recover, onFailure)

    suspend fun importFileBatches() = use { owner.importFileBatches() }
    suspend fun checkActive() = use { owner.checkImportAccess() }
    suspend fun importSubmission(batchId: String) = use { owner.importSubmission(batchId) }
    suspend fun importFiles(batchId: String) = use { owner.importFiles(batchId) }
    suspend fun saveImportFiles(batchId: String, files: List<LocalCashImportFile>) {
        val frozen = files.toList()
        use { owner.saveImportFiles(batchId, frozen) }
    }
    suspend fun captureImportFiles(batchId: String, inputs: List<CashImportUploadInput>): List<LocalCashImportFile> {
        val frozen = inputs.toList()
        return use { owner.captureImportFiles(batchId, frozen) }
    }
    suspend fun appendImportFiles(batchId: String, inputs: List<CashImportUploadInput>): List<LocalCashImportFile> {
        val frozen = inputs.toList()
        return use { owner.appendImportFiles(batchId, frozen) }
    }
    suspend fun replaceImportFile(batchId: String, expected: LocalCashImportFile, input: CashImportUploadInput) =
        use { owner.replaceImportFile(batchId, expected, input) }
    suspend fun removeImportFile(batchId: String, expected: LocalCashImportFile) = use { owner.removeImportFile(batchId, expected) }
    suspend fun parseImportFile(input: CashImportUploadInput) = use { owner.parseImportFile(input) }
    suspend fun savePreparedImportFile(batchId: String, file: LocalCashImportFile, expected: LocalCashImportFile? = null) =
        use { owner.savePreparedImportFile(batchId, file, expected) }
    suspend fun importPreparation(batchId: String) = use(requiresFacts = true) { owner.importPreparation(batchId) }
    suspend fun removeImportFiles(batchId: String) = use { owner.removeImportFiles(batchId) }
    suspend fun importFunding(batchId: String) = use(requiresFacts = true) { owner.importFunding(batchId) }
    suspend fun importReview(batchId: String) = use { owner.importReview(batchId) }
    suspend fun saveImportReview(batchId: String, input: LocalCashImportReview) = use { owner.saveImportReview(batchId, input) }
    suspend fun calculateImport(batchId: String, input: JsonObject, previous: LocalCashImportReview? = null): LocalCashImportCalculation {
        val frozen = Json.parseToJsonElement(input.toString()).jsonObject
        return use(requiresFacts = true) { owner.calculateImport(batchId, frozen, previous) }
    }
    suspend fun prepareImportSubmission(batchId: String, request: CashWriteSetRequestDto) {
        val frozen = LocalCashImportSubmission.freeze(batchId, request)
        use { owner.prepareImportSubmission(batchId, frozen.request()) }
    }
    suspend fun resumeLocalCashImport(batchId: String) = use { recover { owner.resumeLocalCashImport(batchId) } }

    private suspend fun <T> use(requiresFacts: Boolean = false, operation: suspend () -> T): T = try {
        currentCoroutineContext().ensureActive()
        checkCurrent(requiresFacts)
        val result = operation()
        checkCurrent(requiresFacts)
        currentCoroutineContext().ensureActive()
        result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        onFailure(failure)
    }
}
