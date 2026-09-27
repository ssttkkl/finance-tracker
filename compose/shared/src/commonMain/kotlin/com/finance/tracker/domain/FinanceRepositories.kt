package com.finance.tracker.domain


interface FileSource {
    val name: String
    val mediaType: String?
    val size: Long?
    suspend fun read(): ByteArray
}

interface CashLedgerRepository {
    suspend fun fetchCashPage(filters: CashFilters, cursor: String? = null): CashPage
    suspend fun fetchCashAccounts(): List<Account>
    suspend fun fetchEvidence(id: String): Evidence
    suspend fun fetchLedgerOptions(): LedgerOptions
    suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult
    suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact
    suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult
    suspend fun fetchCashRecord(id: String): CashRecordDetail
    suspend fun fetchCashRecords(
        query: String? = null,
        excludeId: String? = null,
        dateFrom: String? = null,
        dateTo: String? = null,
        cursor: String? = null,
        limit: Int = 20,
    ): CashRecordPage
    suspend fun createCashRecord(write: CashRecordWrite): CashRecordDetail
    suspend fun updateCashRecord(id: String, write: CashRecordWrite): CashRecordDetail
    suspend fun deleteCashRecord(id: String, mode: String): CashRecordDeleteResult
    suspend fun createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetail
    suspend fun updateCashRelation(id: String, kind: String): CashRecordDetail
    suspend fun cancelCashRelation(id: String)
    suspend fun dissolveCashRelations(factId: String): CashRecordDetail
}

interface CashCategoryRepository {
    suspend fun fetchCashCategories(): CashCategoryDirectory
    suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory
    suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategory
    suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategory
    suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact
    suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult
}

interface CashImportRepository {
    suspend fun detectCashImport(file: FileSource, currency: String? = null, password: String? = null): ImportDetection
    suspend fun scanCashImport(
        files: List<FileSource>,
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        importToken: String? = null,
    ): ImportScan
    suspend fun previewCashImport(
        importToken: String,
        source: String = "",
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        mapping: List<ImportMappingDecision>? = null,
        batch: Boolean = false,
    ): ImportPreview
    suspend fun commitCashImport(
        importToken: String,
        source: String = "",
        currency: String? = null,
        passwords: Map<String, String> = emptyMap(),
        previewDigest: String? = null,
        previewRelationDigest: String? = null,
        previewChannel: String? = null,
        relations: List<StructuredObject>? = null,
        mapping: List<ImportMappingDecision>? = null,
        idempotencyKey: String? = null,
        batch: Boolean = false,
    ): ImportCommitResult
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

interface FinanceRepository : CashLedgerRepository, CashCategoryRepository, CashImportRepository, InvestmentRepository
