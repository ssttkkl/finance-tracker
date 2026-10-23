package com.finance.tracker.data

import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException

class KtorCashImportLifecycleRepository(private val api: FinanceApiClient) : CashImportLifecycleRepository {
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch = guarded {
        val inputs = files.map { CashImportUploadInput(it.clientFileRef, it.file, it.password, it.sourceHint, it.sourceScope?.let { scope -> CashImportScopeInput(scope.label) }) }
        val progress: suspend (CashImportSessionDto) -> Unit = { onContext(it.toDomain()) }
        val value = if (context == null) {
            if (replacingFileRef != null) invalidCashInput()
            api.createCashImportSession(inputs, progress)
        } else api.addCashImportFiles(context.sessionId, inputs, context.revision ?: invalidCashInput(), replacingFileRef, progress)
        ImportUploadBatch(value.session?.toDomain(), value.localFailures.map { ImportLocalFileFailure(it.clientFileRef, it.code) },
            value.interruption?.let { ImportUploadInterruption(it.code, it.status, it.receiptId, it.unprocessedFileRefs) })
    }
    override suspend fun context(sessionId: String): ImportContext = guarded { api.readCashImportSession(sessionId).toDomain() }
    override suspend fun setSourceScope(sessionId: String, fileRef: String, revision: Long, label: String?): ImportContext = guarded { api.setCashImportFileScope(sessionId, fileRef, revision, label).toDomain() }
    override suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext = guarded { api.removeCashImportFile(sessionId, fileRef, revision).toDomain() }
    override suspend fun cancel(sessionId: String): ImportContext = guarded { api.cancelCashImportSession(sessionId).toDomain() }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan = guarded { api.previewCashImport(sessionId, choices.toDto(revision, previousPlanId)).toDomain() }
    override suspend fun plan(planId: String): ImportPlan = guarded { api.readCashImportPlan(planId).toDomain() }
    override suspend fun items(planId: String, cursor: String?, limit: Int, confirmationStatus: String): ImportItemPage = guarded { api.readCashImportItems(planId, cursor, limit, confirmationStatus).toDomain() }
    override suspend fun item(planId: String, itemId: String): ImportItemDetail = guarded { api.readCashImportItem(planId, itemId).toDomain() }
    override suspend fun processing(planId: String, cursor: String?, limit: Int): ImportProcessingPage = guarded { api.readCashImportProcessing(planId, cursor, limit).toDomain() }
    override suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt = guarded { api.commitCashImport(planId, revision, idempotencyKey).toDomain() }
    override suspend fun receipt(receiptId: String): ImportReceipt = guarded { api.readCashMutationReceipt(receiptId).toDomain() }
    override suspend fun receiptByKey(key: String): ImportReceipt = guarded { api.readCashMutationReceiptByKey(key).toDomain() }
    override suspend fun results(receiptId: String, kind: ImportResultKind, after: String, limit: Int): ImportResultPage = guarded { api.readCashMutationResults(receiptId, CashMutationResultKind.valueOf(kind.name), after, limit).toDomain() }
    override suspend fun resolve(planId: String, idempotencyKey: String): ImportResolveResult = guarded { api.resolveCashMutation(planId, idempotencyKey).toDomain() }
    override suspend fun retry(receiptId: String, idempotencyKey: String, rowRefs: List<String>?): ImportContext = guarded { api.retryCashImport(receiptId, idempotencyKey, rowRefs).toDomain() }
    override suspend fun accounts(): List<ImportAccountListItem> = guarded { api.listCashImportAccounts().map(ImportAccountListItemDto::toDomain) }
    override suspend fun account(accountId: String): ImportAccount = guarded { api.readImportAccount(accountId).toDomain() }
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount = guarded { api.createImportAccount(name, type, currencies, idempotencyKey).toDomain() }
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount = guarded { api.updateImportAccount(accountId, revision, patch.toJsonObject(), idempotencyKey).toDomain() }
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount = guarded { api.deleteImportAccount(accountId, revision, idempotencyKey).toDomain() }
    private suspend fun <T> guarded(action: suspend () -> T): T = try { action() }
    catch (failure: CancellationException) { throw failure }
    catch (failure: Exception) { throw failure.toDomainFailure() }
}
