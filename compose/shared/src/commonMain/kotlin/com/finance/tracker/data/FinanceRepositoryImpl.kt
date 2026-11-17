package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.data.mapping.toStructuredObject
import kotlinx.coroutines.CancellationException

class KtorFinanceRepository(private val apiClient: FinanceApiClient) : FinanceRepository {
    private var operations: CashBusinessOperations? = null
    internal constructor(apiClient: FinanceApiClient, operations: CashBusinessOperations) : this(apiClient) { this.operations = operations }

    private suspend fun <T> cash(write: Boolean = false, committedVersion: (T) -> String? = { null }, action: suspend (FinanceApiClient) -> T): T = guarded {
        val bound = operations
        if (bound == null) action(apiClient) else bound.run(write) { api, refresh ->
            action(api).also { if (write) refresh(committedVersion(it)) }
        }
    }

    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?, projectionVersion: Long?, limit: Int): CashPage = cash { api ->
        api.fetchCashPage(filters.toDto(), cursor, startIndex, projectionVersion, limit).toDomain()
    }

    override suspend fun fetchCashAccounts(): List<Account> = cash { api -> api.fetchCashAccounts().map { it.toDomain() } }

    override suspend fun fetchLedgerOptions(): LedgerOptions = cash { api -> api.fetchLedgerOptions().toDomain() }

    override suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult {
        val frozen = ids.toList()
        return cash(write = true, committedVersion = { it.projectionVersion.toString() }) { api ->
            api.classifyCashProjections(frozen, projectionVersion, categoryId).toDomain()
        }
    }

    override suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact {
        val frozen = ids.toList()
        return cash { api ->
            api.fetchCashProjectionDeleteImpact(frozen, projectionVersion).toDomain()
        }
    }

    override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult {
        val frozen = ids.toList()
        return cash(write = true, committedVersion = { it.projectionVersion.toString() }) { api ->
            api.deleteCashProjections(frozen, projectionVersion).toDomain()
        }
    }

    override suspend fun fetchCashCategories(): CashCategoryDirectory = cash { api -> api.fetchCashCategories().toDomain() }

    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = cash(write = true) { api ->
        api.createCashCategory(name, parentId, description, expectedRevision).toWriteResult()
    }

    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = cash(write = true) { api ->
        api.updateCashCategory(id, name, parentId, description, expectedRevision).toWriteResult()
    }

    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategoryWriteResult = cash(write = true) { api ->
        api.reorderCashCategory(id, direction, expectedRevision).toWriteResult()
    }

    override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact = cash { api ->
        api.fetchCashCategoryDeletionImpact(id).toDomain()
    }

    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult = cash(write = true) { api ->
        api.deleteCashCategory(id, impact.toDto()).toDomain()
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
