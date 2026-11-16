package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.CashMutationReceiptDto
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.core.LocalCashProjectionReferences
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
    private val afterAccountWrite: (suspend (String?) -> Unit)? = null,
    private val prepareFacts: (suspend () -> Unit)? = null,
    private val readProjectionRefs: suspend (suspend () -> LocalCashProjectionReferences?) -> LocalCashProjectionReferences? = { it() },
) {
    internal fun guardedBy(check: suspend (Boolean) -> Unit,
        recover: suspend (suspend () -> LocalCashImportSubmissionResult) -> LocalCashImportSubmissionResult,
        onFailure: suspend (Throwable) -> Nothing = this.onFailure,
        afterAccountWrite: (suspend (String?) -> Unit)? = this.afterAccountWrite,
        prepareFacts: (suspend () -> Unit)? = this.prepareFacts,
        readProjectionRefs: suspend (suspend () -> LocalCashProjectionReferences?) -> LocalCashProjectionReferences? = this.readProjectionRefs): LedgerSessionImports =
        LedgerSessionImports(owner, { requiresFacts -> check(requiresFacts); checkCurrent(requiresFacts) }, recover, onFailure, afterAccountWrite, prepareFacts, readProjectionRefs)

    suspend fun requireScope(principal: String, workspaceId: String) = use { owner.checkImportScope(principal, workspaceId) }

    suspend fun accounts() = use(requiresFacts = true) { owner.importAccounts() }
    suspend fun account(accountId: String) = use { owner.importAccount(accountId) }
    suspend fun selectionAccount(accountId: String) = use(requiresFacts = true) { owner.importSelectionAccount(accountId) }
    suspend fun createAccount(name: String, type: String, currencies: List<String>, key: String): ImportAccountDto {
        val frozen = currencies.toList()
        return accountMutation { owner.createImportAccount(name, type, frozen, key) }
    }
    suspend fun updateAccount(id: String, revision: Long, patch: JsonObject, key: String): ImportAccountDto {
        val frozen = Json.parseToJsonElement(patch.toString()).jsonObject
        return accountMutation { owner.updateImportAccount(id, revision, frozen, key) }
    }
    suspend fun deleteAccount(id: String, revision: Long, key: String) = accountMutation { owner.deleteImportAccount(id, revision, key) }
    suspend fun ensureAccountCurrencies(requirements: List<ImportCurrencyRequirementDto>): List<ImportCurrencyRequirementResultDto> {
        val frozen = requirements.map { it.copy(currencies = it.currencies.toList()) }
        return accountMutation { owner.ensureImportAccountCurrencies(frozen) }
    }
    suspend fun accountReceipt(receiptId: String) = accountRecovery { owner.importAccountReceipt(receiptId) }
    suspend fun accountReceiptByKey(key: String) = accountRecovery { owner.importAccountReceiptByKey(key) }

    private suspend fun <T> accountMutation(action: suspend () -> T): T {
        val refresh = checkNotNull(afterAccountWrite) { "cash_import_account_refresh_missing" }
        return use { action().also { refresh(null) } }
    }
    private suspend fun accountRecovery(action: suspend () -> CashMutationReceiptDto): CashMutationReceiptDto {
        val refresh = checkNotNull(afterAccountWrite) { "cash_import_account_refresh_missing" }
        return use { action().also { if (it.committed) refresh(it.ledgerSeq) } }
    }

    suspend fun importFileBatches() = use { owner.importFileBatches() }
    suspend fun checkActive() = use { owner.checkImportAccess() }
    suspend fun prepareFactsForReview() = use { prepareFacts?.invoke(); Unit }
    suspend fun importResult(receiptId: String) = use { owner.importResult(receiptId) }
    suspend fun importResultByBatchId(batchId: String) = use { owner.importResultByBatchId(batchId) }
    suspend fun importResultByKey(key: String) = use { owner.importResultByKey(key) }
    suspend fun importProjectionRefs(transactionIds: List<String>): LocalCashProjectionReferences? {
        val frozen = transactionIds.toList()
        return use { readProjectionRefs { owner.importProjectionRefs(frozen) } }
    }
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
    suspend fun prepareImportSubmission(batchId: String, request: CashWriteSetRequestDto, resultInput: LocalCashImportResultInput? = null) {
        val frozen = LocalCashImportSubmission.freeze(batchId, request, resultInput)
        use { owner.prepareImportSubmission(batchId, frozen.request(), frozen.resultInput) }
    }
    suspend fun resumeLocalCashImport(batchId: String, beforeCompletion: suspend (CashMutationReceiptDto) -> Unit = {}) = use {
        recover {
            owner.resumeLocalCashImport(batchId) { receipt ->
                checkCurrent(false)
                beforeCompletion(receipt)
                checkCurrent(false)
            }
        }
    }

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
