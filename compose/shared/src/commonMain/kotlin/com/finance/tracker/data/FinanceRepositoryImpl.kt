package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.data.mapping.toStructuredObject
import kotlinx.coroutines.CancellationException

class KtorFinanceRepository(private val apiClient: FinanceApiClient) : FinanceRepository {
    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?): CashPage = guarded {
        apiClient.fetchCashPage(filters.toDto(), cursor).toDomain()
    }

    override suspend fun fetchCashAccounts(): List<Account> = guarded { apiClient.fetchCashAccounts().map { it.toDomain() } }

    override suspend fun fetchEvidence(id: String): Evidence = guarded { apiClient.fetchEvidence(id).toDomain() }

    override suspend fun fetchLedgerOptions(): LedgerOptions = guarded { apiClient.fetchLedgerOptions().toDomain() }

    override suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult = guarded {
        apiClient.classifyCashProjections(ids, projectionVersion, categoryId).toDomain()
    }

    override suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact = guarded {
        apiClient.fetchCashProjectionDeleteImpact(ids, projectionVersion).toDomain()
    }

    override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult = guarded {
        apiClient.deleteCashProjections(ids, projectionVersion).toDomain()
    }

    override suspend fun fetchCashRecord(id: String): CashRecordDetail = guarded { apiClient.fetchCashRecord(id).toDomain() }

    override suspend fun fetchCashRecords(
        query: String?,
        excludeId: String?,
        dateFrom: String?,
        dateTo: String?,
        cursor: String?,
        limit: Int,
    ): CashRecordPage = guarded {
        apiClient.fetchCashRecords(query, excludeId, dateFrom, dateTo, cursor, limit).toDomain()
    }

    override suspend fun createCashRecord(write: CashRecordWrite): CashRecordDetail = guarded {
        apiClient.createCashRecord(write.toStructuredObject().toJsonObject()).toDomain()
    }

    override suspend fun updateCashRecord(id: String, write: CashRecordWrite): CashRecordDetail = guarded {
        apiClient.updateCashRecord(id, write.toStructuredObject().toJsonObject()).toDomain()
    }

    override suspend fun deleteCashRecord(id: String, mode: String): CashRecordDeleteResult = guarded {
        apiClient.deleteCashRecord(id, mode).toDomain()
    }

    override suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetail = guarded {
        apiClient.createCashRelation(primaryFactId, secondaryFactId, kind).toDomain()
    }

    override suspend fun updateCashRelation(id: String, kind: String): CashRecordDetail = guarded {
        apiClient.updateCashRelation(id, kind).toDomain()
    }

    override suspend fun cancelCashRelation(id: String) {
        guarded { apiClient.cancelCashRelation(id) }
    }

    override suspend fun dissolveCashRelations(factId: String): CashRecordDetail = guarded {
        apiClient.dissolveCashRelations(factId).toDomain()
    }

    override suspend fun fetchCashCategories(): CashCategoryDirectory = guarded { apiClient.fetchCashCategories().toDomain() }

    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory = guarded {
        apiClient.createCashCategory(name, parentId, description, expectedRevision).toDomain()
    }

    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory = guarded {
        apiClient.updateCashCategory(id, name, parentId, description, expectedRevision).toDomain()
    }

    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategory = guarded {
        apiClient.reorderCashCategory(id, direction, expectedRevision).toDomain()
    }

    override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact = guarded {
        apiClient.fetchCashCategoryDeletionImpact(id).toDomain()
    }

    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult = guarded {
        apiClient.deleteCashCategory(id, impact.toDto()).toDomain()
    }

    override suspend fun detectCashImport(file: FileSource, currency: String?, password: String?): ImportDetection = guarded {
        apiClient.detectCashImport(file, currency, password).toDomain()
    }

    override suspend fun scanCashImport(
        files: List<FileSource>,
        currency: String?,
        passwords: Map<String, String>,
        importToken: String?,
    ): ImportScan = guarded {
        apiClient.scanCashImport(files, currency, passwords, importToken).toDomain()
    }

    override suspend fun previewCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        mapping: List<ImportMappingDecision>?,
        batch: Boolean,
    ): ImportPreview = guarded {
        apiClient.previewCashImport(importToken, source, currency, passwords, mapping?.map { it.toDto() }, batch).toDomain()
    }

    override suspend fun commitCashImport(
        importToken: String,
        source: String,
        currency: String?,
        passwords: Map<String, String>,
        previewDigest: String?,
        previewRelationDigest: String?,
        previewChannel: String?,
        relations: List<StructuredObject>?,
        mapping: List<ImportMappingDecision>?,
        idempotencyKey: String?,
        batch: Boolean,
    ): ImportCommitResult = guarded {
        apiClient.commitCashImport(
            importToken = importToken,
            source = source,
            currency = currency,
            passwords = passwords,
            previewDigest = previewDigest,
            previewRelationDigest = previewRelationDigest,
            previewChannel = previewChannel,
            relations = relations?.map(StructuredObject::toJsonObject),
            mapping = mapping?.map { it.toDto() },
            idempotencyKey = idempotencyKey,
            batch = batch,
        ).toDomain()
    }

    override suspend fun fetchInvestmentAccounts(): List<Account> = guarded { apiClient.fetchInvestmentAccounts().map { it.toDomain() } }

    override suspend fun fetchInvestmentPage(filters: InvestmentFilters, cursor: String?): InvestmentPage = guarded {
        apiClient.fetchInvestmentPage(filters.toDto(), cursor).toDomain()
    }

    override suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidence = guarded {
        apiClient.fetchInvestmentEvidence(eventId).toDomain()
    }

    override suspend fun fetchInvestmentPortfolio(displayCurrency: String?, period: String, phase: String): Portfolio = guarded {
        apiClient.fetchInvestmentPortfolio(displayCurrency, period, phase).toDomain()
    }

    override suspend fun refreshInvestmentPortfolio(displayCurrency: String?, period: String) {
        guarded { apiClient.refreshInvestmentPortfolio(displayCurrency, period) }
    }

    override suspend fun streamInvestmentPortfolio(
        displayCurrency: String?,
        period: String,
        onPortfolio: (Portfolio) -> Unit,
        onRefreshError: () -> Unit,
    ) {
        guarded {
            apiClient.streamInvestmentPortfolio(
                displayCurrency,
                period,
                onPortfolio = { onPortfolio(it.toDomain()) },
                onRefreshError = onRefreshError,
            )
        }
    }

    private suspend fun <T> guarded(operation: suspend () -> T): T = try {
        operation()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        throw cause.toDomainFailure()
    }
}
