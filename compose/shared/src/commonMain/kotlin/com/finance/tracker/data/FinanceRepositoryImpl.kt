package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.data.mapping.toStructuredObject
import kotlinx.coroutines.CancellationException

class KtorFinanceRepository(private val apiClient: FinanceApiClient) : FinanceRepository {
    override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?, projectionVersion: Long?, limit: Int): CashPage = guarded {
        apiClient.fetchCashPage(filters.toDto(), cursor, startIndex, projectionVersion, limit).toDomain()
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

    override suspend fun fetchCashCategories(): CashCategoryDirectory = guarded { apiClient.fetchCashCategories().toDomain() }

    override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = guarded {
        apiClient.createCashCategory(name, parentId, description, expectedRevision).toWriteResult()
    }

    override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = guarded {
        apiClient.updateCashCategory(id, name, parentId, description, expectedRevision).toWriteResult()
    }

    override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategoryWriteResult = guarded {
        apiClient.reorderCashCategory(id, direction, expectedRevision).toWriteResult()
    }

    override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact = guarded {
        apiClient.fetchCashCategoryDeletionImpact(id).toDomain()
    }

    override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult = guarded {
        apiClient.deleteCashCategory(id, impact.toDto()).toDomain()
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
