package com.finance.tracker.domain

data class Account(
    val id: Long,
    val name: String,
    val type: String,
    val active: Boolean = true,
    val currencies: List<String>? = null,
)

data class SimpleOption(
    val value: String,
    val label: String,
)

data class RecordTypeOption(
    val value: String,
    val label: String,
    val subtypes: List<SimpleOption> = emptyList(),
)

data class LedgerOptions(
    val recordTypes: List<RecordTypeOption> = emptyList(),
    val relationTypes: List<SimpleOption> = emptyList(),
)

data class CashCategoryPathItem(
    val id: String,
    val name: String,
)

data class CashCategory(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val description: String? = null,
    val path: List<CashCategoryPathItem> = emptyList(),
    val depth: Int = 1,
    val sortOrder: Int = 0,
    val revision: Int = 0,
)

data class CashCategoryDirectory(
    val revision: Int,
    val items: List<CashCategory>,
)

data class AcceptedRelationSummary(
    val kind: String,
    val count: Int = 0,
)

data class CashTransfer(
    val fromAccount: Account,
    val fromAmount: String,
    val fromCurrency: String,
    val toAccount: Account,
    val toAmount: String,
    val toCurrency: String,
)

data class CashProjection(
    val projectionId: String,
    val occurredAt: String?,
    val account: Account? = null,
    val counterparty: String = "",
    val category: CashCategory? = null,
    val note: String = "",
    val amount: String?,
    val currency: String?,
    val economicType: String?,
    val businessLabel: String? = null,
    val composition: List<String> = emptyList(),
    val memberCount: Int = 1,
    val acceptedRelationSummary: List<AcceptedRelationSummary> = emptyList(),
    val sourceType: String? = null,
    val sourceTypes: List<String> = emptyList(),
    val recordId: String,
    val visible: Boolean = true,
    val hiddenReason: String? = null,
    val transfer: CashTransfer? = null,
    val state: String = "posted",
    val statisticsEligible: Boolean = true,
    val attentionCount: Long = 0,
    val confirmationStatus: String = "confirmed",
    val projectionVersion: Long? = null,
    val entryId: String? = null,
    val transactionId: String,
    val recordType: String? = null,
    val recordSubtype: String? = null,
    val attentionCodes: List<String> = emptyList(),
)

data class CashEconomicTypeFilterOption(
    val economicType: String,
    val businessLabels: List<String> = emptyList(),
)

data class CashFilterOptions(
    val categories: List<CashCategory> = emptyList(),
    val currencies: List<String> = emptyList(),
    val economicTypes: List<CashEconomicTypeFilterOption> = emptyList(),
)

data class CashMonthlyCurrencySummary(
    val currency: String,
    val income: String,
    val expense: String,
)

data class CashMonthlySummary(
    val month: String,
    val currencies: List<CashMonthlyCurrencySummary> = emptyList(),
)

data class CashMonthPosition(
    val month: String,
    val startIndex: Int,
    val endIndex: Int,
)

data class CashPage(
    val projectionVersion: Long,
    val items: List<CashProjection> = emptyList(),
    val nextCursor: String? = null,
    val pageSize: Int = 0,
    val filters: Map<String, String?> = emptyMap(),
    val filterOptions: CashFilterOptions = CashFilterOptions(),
    val monthlySummaries: List<CashMonthlySummary> = emptyList(),
    val pendingItemCount: Long = 0,
    val monthPositions: List<CashMonthPosition> = emptyList(),
    val startIndex: Int? = null,
    val totalCount: Int? = null,
    val itemsBefore: Int? = null,
    val itemsAfter: Int? = null,
)

data class CashProjectionDeleteImpact(
    val projectionCount: Int,
    val transactionCount: Int,
    val relationGroupCount: Int,
)

data class CashProjectionDeleteResult(
    val projectionCount: Int,
    val transactionCount: Int,
    val relationGroupCount: Int,
    val deleted: Boolean,
    val projectionVersion: Long,
)

data class CashComponentDetail(
    val id: String,
    val cashTransactionId: String? = null,
    val account: Account? = null,
    val accountName: String? = null,
    val accountId: Long? = null,
    val accountType: String? = null,
    val amount: String,
    val currency: String,
    val ordinal: Int,
    val label: String? = null,
)

data class EvidenceRecord(
    val id: String,
    val occurredAt: String,
    val account: Account? = null,
    val accountName: String? = null,
    val accountId: Long? = null,
    val accountType: String? = null,
    val counterparty: String = "",
    val counterpartyAccount: String? = null,
    val category: CashCategory? = null,
    val categoryId: String? = null,
    val note: String = "",
    val amount: String,
    val currency: String,
    val sourceType: String? = null,
    val recordId: String? = null,
    val recordType: String? = null,
    val recordSubtype: String? = null,
    val cashGranularity: String? = null,
    val components: List<CashComponentDetail>? = null,
    val sourceSnapshot: Map<String, StructuredValue>? = null,
)

data class EvidenceMember(
    val id: String = "",
    val occurredAt: String = "",
    val account: Account? = null,
    val accountName: String? = null,
    val accountId: Long? = null,
    val accountType: String? = null,
    val counterparty: String = "",
    val counterpartyAccount: String? = null,
    val category: CashCategory? = null,
    val categoryId: String? = null,
    val note: String = "",
    val amount: String = "",
    val currency: String = "",
    val sourceType: String? = null,
    val recordId: String? = null,
    val recordType: String? = null,
    val recordSubtype: String? = null,
    val cashGranularity: String? = null,
    val components: List<CashComponentDetail>? = null,
    val roles: List<String> = emptyList(),
)

data class EndpointRelation(
    val id: String,
    val kind: String,
    val primaryRecord: EvidenceRecord? = null,
    val secondaryRecord: EvidenceRecord? = null,
)

data class AcceptedEvidenceRelation(
    val id: String,
    val kind: String,
    val primaryRecord: EvidenceRecord? = null,
    val secondaryRecord: EvidenceRecord? = null,
    val ruleId: String = "",
    val confidence: String = "",
    val evidence: StructuredObject = StructuredObject(emptyMap()),
)

data class InactiveRelationHint(
    val id: String,
    val kind: String,
    val primaryRecord: EvidenceRecord? = null,
    val secondaryRecord: EvidenceRecord? = null,
    val status: String,
)

data class RefundTimelineItem(
    val recordId: String,
    val occurredAt: String,
    val amount: String,
    val currency: String,
    val sourceType: String? = null,
)

data class Evidence(
    val projectionVersion: Long,
    val projection: CashProjection,
    val rootRecord: EvidenceRecord,
    val members: List<EvidenceMember> = emptyList(),
    val acceptedRelations: List<AcceptedEvidenceRelation> = emptyList(),
    val inactiveRelationHints: List<InactiveRelationHint> = emptyList(),
    val refundTimeline: List<RefundTimelineItem> = emptyList(),
)

data class CashRecord(
    val id: String,
    val occurredAt: String,
    val amount: String,
    val currency: String,
    val counterparty: String = "",
    val counterpartyAccount: String = "",
    val note: String = "",
    val category: CashCategory? = null,
    val categoryId: String? = null,
    val recordType: String,
    val recordSubtype: String = "not_applicable",
    val accountName: String = "",
    val accountId: Long? = null,
    val accountType: String = "cash",
    val sourceType: String? = null,
    val cashGranularity: String? = null,
    val components: List<CashComponentDetail>? = null,
    val componentId: String? = null,
)

data class CashRelation(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val primaryRecord: CashRecord? = null,
    val secondaryRecord: CashRecord? = null,
    val primaryComponentId: String? = null,
    val secondaryComponentId: String? = null,
    val appliedAmount: String? = null,
)

data class CashRecordDetail(
    val record: CashRecord,
    val relations: List<CashRelation> = emptyList(),
    val options: LedgerOptions = LedgerOptions(),
)

data class CashRecordPage(
    val items: List<CashRecord> = emptyList(),
    val nextCursor: String? = null,
)

data class CashRecordWrite(
    val occurredAt: String,
    val amount: String,
    val currency: String,
    val counterparty: String = "",
    val counterpartyAccount: String = "",
    val note: String = "",
    val accountName: String,
    val recordType: String,
    val recordSubtype: String = "not_applicable",
    val categoryId: String? = null,
    val projectionVersion: Long? = null,
    val confirmRelationImpact: Boolean? = null,
)

data class CashFilters(
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val accountId: String? = null,
    val counterparty: String? = null,
    val categoryId: String? = null,
    val uncategorized: String? = null,
    val currency: String? = null,
    val amountMin: String? = null,
    val amountMax: String? = null,
    val economicType: String? = null,
    val businessLabel: String? = null,
    val composition: String? = null,
    val state: String? = null,
    val confirmationStatus: String? = null,
    val hideZeroAmount: Boolean = true,
)

data class AccountList(
    val items: List<Account> = emptyList(),
)

data class CashCategoryDeleteImpact(
    val categoryId: String,
    val revision: Long,
    val categoryRevision: Long,
    val childCount: Int,
    val directUsageCount: Int,
)

data class CashCategoryDeleteResult(
    val categoryId: String,
    val clearedTransactionCount: Int,
    val revision: Long,
)

data class CashClassificationResult(
    val projectionVersion: Long,
    val projectionCount: Int,
    val updatedTransactionCount: Int,
    val categoryId: String? = null,
)

data class CashRecordDeleteResult(
    val deleted: Boolean,
    val relatedCount: Int,
    val deletedFactIds: List<String> = emptyList(),
)

data class InvestmentAsset(
    val ticker: String? = null,
    val amount: String? = null,
)

data class InvestmentCommission(
    val amount: String? = null,
    val asset: String? = null,
)

data class InvestmentRelation(
    val kind: String,
    val status: String,
    val direction: String = "",
    val ruleId: String = "",
    val cashAccount: Account,
    val cashAmount: String,
    val cashCurrency: String,
    val cashOccurredAt: String,
    val cashCounterparty: String = "",
    val cashNote: String = "",
    val cashSourceType: String? = null,
    val cashRecordId: String,
    val evidence: StructuredObject = StructuredObject(emptyMap()),
)

data class InvestmentEvent(
    val eventId: String,
    val occurredAt: String,
    val account: Account,
    val recordType: String,
    val recordSubtype: String = "",
    val currency: String,
    val note: String = "",
    val fromAsset: InvestmentAsset = InvestmentAsset(),
    val toAsset: InvestmentAsset = InvestmentAsset(),
    val commission: InvestmentCommission = InvestmentCommission(),
    val sourceType: String? = null,
    val recordId: String,
    val relations: List<InvestmentRelation> = emptyList(),
)

data class InvestmentFilters(
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val accountId: String? = null,
    val recordType: String? = null,
    val ticker: String? = null,
)

data class InvestmentPage(
    val dataVersion: Long,
    val items: List<InvestmentEvent> = emptyList(),
    val nextCursor: String? = null,
    val pageSize: Int = 0,
    val filters: Map<String, StructuredValue> = emptyMap(),
)

data class InvestmentEvidence(
    val dataVersion: Long,
    val event: InvestmentEvent,
    val sourceSnapshot: StructuredObject? = null,
    val relations: List<InvestmentRelation> = emptyList(),
)

data class PortfolioPeriodBaseline(
    val account: String,
    val ticker: String,
    val occurredAt: String,
)

data class PortfolioPosition(
    val ticker: String,
    val displayName: String? = null,
    val shares: String,
    val totalCost: String,
    val costCurrency: String,
    val isCash: Boolean = false,
    val currentPrice: String? = null,
    val marketValue: String? = null,
    val profit: String? = null,
    val quoteStatus: String? = null,
    val quoteReason: String? = null,
    val quoteCurrency: String? = null,
    val quoteObservedAt: String? = null,
    val quoteSession: String? = null,
    val displayCurrency: String? = null,
    val displayMarketValue: String? = null,
    val usdMarketValue: String? = null,
    val fxRate: String? = null,
    val fxStatus: String? = null,
    val fxReason: String? = null,
    val periodProfit: String? = null,
    val periodProfitRate: String? = null,
    val periodBaselines: List<PortfolioPeriodBaseline> = emptyList(),
)

data class PortfolioAccount(
    val name: String,
    val currency: String,
    val positions: List<PortfolioPosition> = emptyList(),
)

data class Portfolio(
    val accounts: List<PortfolioAccount> = emptyList(),
    val totalMarketValue: String? = null,
    val totalProfit: String? = null,
    val totalProfitRate: String? = null,
    val periodProfit: String? = null,
    val periodProfitRate: String? = null,
    val periodBaselines: List<PortfolioPeriodBaseline> = emptyList(),
)

sealed interface StructuredValue
data class StructuredObject(val fields: Map<String, StructuredValue>) : StructuredValue
data class StructuredArray(val values: List<StructuredValue>) : StructuredValue
data class StructuredText(val value: String) : StructuredValue
data class StructuredNumber(val value: String) : StructuredValue
data class StructuredBoolean(val value: Boolean) : StructuredValue
data object StructuredNull : StructuredValue
