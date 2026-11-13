package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException

class KtorCashImportLifecycleRepository(private val api: FinanceApiClient,
    private val captureRefresh: suspend () -> (suspend (String?) -> Unit) = { {} }) : CashImportLifecycleRepository {
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch = guarded {
        val inputs = files.map { CashImportUploadInput(it.clientFileRef, it.file, it.password, it.sourceHint) }
        val progress: suspend (CashImportSessionDto) -> Unit = { onContext(it.toDomain()) }
        val value = if (context == null) {
            if (replacingFileRef != null) invalidCashInput()
            api.createCashImportSession(inputs, progress)
        } else api.addCashImportFiles(context.sessionId, inputs, context.revision ?: invalidCashInput(), replacingFileRef, progress)
        ImportUploadBatch(value.session?.toDomain(), value.localFailures.map { ImportLocalFileFailure(it.clientFileRef, it.code) },
            value.interruption?.let { ImportUploadInterruption(it.code, it.status, it.receiptId, it.unprocessedFileRefs, it.diagnostic) })
    }
    override suspend fun context(sessionId: String): ImportContext = guarded { api.readCashImportSession(sessionId).toDomain() }
    override suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext = guarded { api.removeCashImportFile(sessionId, fileRef, revision).toDomain() }
    override suspend fun cancel(sessionId: String): ImportContext = guarded { api.cancelCashImportSession(sessionId).toDomain() }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan = guarded { api.previewCashImport(sessionId, choices.toDto(revision, previousPlanId)).toDomain() }
    override suspend fun plan(planId: String): ImportPlan = guarded { api.readCashImportPlan(planId).toDomain() }
    override suspend fun items(planId: String, cursor: String?, limit: Int, confirmationStatus: String, startIndex: Int?): ImportItemPage = guarded { api.readCashImportItems(planId, cursor, limit, confirmationStatus, startIndex).toDomain() }
    override suspend fun item(planId: String, itemId: String): ImportItemDetail = guarded { api.readCashImportItem(planId, itemId).toDomain() }
    override suspend fun processing(planId: String, cursor: String?, limit: Int): ImportProcessingPage = guarded { api.readCashImportProcessing(planId, cursor, limit).toDomain() }
    override suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt = guarded {
        val refresh = captureRefresh()
        val receipt = api.commitCashImport(planId, revision, idempotencyKey).toDomain()
        if (receipt.committed) refresh(receipt.ledgerSeq)
        receipt
    }
    override suspend fun receipt(receiptId: String): ImportReceipt = guarded {
        val refresh = captureRefresh()
        api.readCashMutationReceipt(receiptId).toDomain().also { if (it.committed) refresh(it.ledgerSeq) }
    }
    override suspend fun receiptByKey(key: String): ImportReceipt = guarded {
        val refresh = captureRefresh()
        api.readCashMutationReceiptByKey(key).toDomain().also { if (it.committed) refresh(it.ledgerSeq) }
    }
    override suspend fun results(receiptId: String, kind: ImportResultKind, after: String, limit: Int): ImportResultPage = guarded { api.readCashMutationResults(receiptId, CashMutationResultKind.valueOf(kind.name), after, limit).toDomain() }
    override suspend fun resolve(planId: String, idempotencyKey: String): ImportResolveResult = guarded {
        val refresh = captureRefresh()
        val result = api.resolveCashMutation(planId, idempotencyKey).toDomain()
        if (result.status == "committed" && result.receipt?.committed == true) refresh(result.receipt.ledgerSeq)
        result
    }
    override suspend fun accounts(): List<ImportAccountListItem> = guarded { api.listCashImportAccounts().map(ImportAccountListItemDto::toDomain) }
    override suspend fun account(accountId: String): ImportAccount = guarded { api.readImportAccount(accountId).toDomain() }
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount = guarded {
        val refresh = captureRefresh()
        api.createImportAccount(name, type, currencies, idempotencyKey).toDomain().also { refresh(null) }
    }
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount = guarded {
        val refresh = captureRefresh()
        api.updateImportAccount(accountId, revision, patch.toJsonObject(), idempotencyKey).toDomain().also { refresh(null) }
    }
    override suspend fun ensureAccountCurrencies(requirements: List<ImportCurrencyRequirement>): List<ImportCurrencyRequirementResult> = guarded {
        val refresh = captureRefresh()
        api.ensureImportAccountCurrencies(requirements.map {
            ImportCurrencyRequirementDto(it.idempotencyKey, it.accountId, it.revision, it.currencies)
        }).map { it.toDomain() }.also { refresh(null) }
    }
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount = guarded {
        val refresh = captureRefresh()
        api.deleteImportAccount(accountId, revision, idempotencyKey).toDomain().also { refresh(null) }
    }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (failure: CancellationException) { throw failure }
    catch (failure: Exception) {
        val mapped = failure.toDomainFailure()
        if (mapped.category == FailureCategory.UNKNOWN) throw failure
        throw mapped
    }
}
