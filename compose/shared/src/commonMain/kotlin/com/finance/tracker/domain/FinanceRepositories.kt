package com.finance.tracker.domain


interface FileSource {
    val name: String
    val mediaType: String?
    val size: Long?
    suspend fun read(): ByteArray
}

interface CashLedgerRepository {
    suspend fun fetchCashPage(
        filters: CashFilters,
        cursor: String? = null,
        startIndex: Int? = null,
        projectionVersion: Long? = null,
        limit: Int = 20,
    ): CashPage
    suspend fun fetchCashAccounts(): List<Account>
    suspend fun fetchEvidence(id: String): Evidence
    suspend fun fetchLedgerOptions(): LedgerOptions
    suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult
    suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact
    suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult

}

interface CashCategoryRepository {
    suspend fun fetchCashCategories(): CashCategoryDirectory
    suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory
    suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory
    suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategory
    suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact
    suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult
}

interface InvestmentRepository {
    suspend fun fetchInvestmentAccounts(): List<Account>
    suspend fun fetchInvestmentPage(filters: InvestmentFilters, cursor: String? = null): InvestmentPage
    suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidence
    suspend fun fetchInvestmentPortfolio(
        displayCurrency: String? = null,
        period: String = "24h",
        phase: String = "valuation",
    ): Portfolio
    suspend fun refreshInvestmentPortfolio(displayCurrency: String? = null, period: String = "24h")
    suspend fun streamInvestmentPortfolio(
        displayCurrency: String? = null,
        period: String = "24h",
        onPortfolio: (Portfolio) -> Unit,
        onRefreshError: () -> Unit,
    )
}

interface FinanceRepository : CashLedgerRepository, CashCategoryRepository, InvestmentRepository
