package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CashLedgerDateBounds
import com.finance.tracker.business.domain.buildLocalCashCategoryDirectory
import kotlin.time.Instant
import com.finance.tracker.business.domain.LocalCashLedgerQuery
import com.finance.tracker.business.domain.normalizeLocalCashLedgerQueryInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CashLedgerSummary(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("ledger_seq") val ledgerSeq: String,
    val filters: Map<String, String?>,
    val months: List<CashMonthlySummaryDto>,
    @SerialName("pending_item_count") val pendingItemCount: Long,
)

class CashLedgerOperations(
    private val snapshot: LedgerSnapshotDto,
    monthAt: (Instant, String) -> String,
    private val dateBounds: ((String?, String?, String?) -> CashLedgerDateBounds)?,
    sample: (String) -> Unit,
) {
    constructor(snapshot: LedgerSnapshotDto, monthAt: (Instant, String) -> String,
        dateBounds: ((String?, String?, String?) -> CashLedgerDateBounds)? = null) : this(snapshot, monthAt, dateBounds, {})
    constructor(snapshot: LedgerSnapshotDto, calendar: CashLedgerCalendar) : this(snapshot, calendar::monthAt, calendar::bounds)
    constructor(snapshot: LedgerSnapshotDto, calendar: CashLedgerCalendar, sample: (String) -> Unit) :
        this(snapshot, calendar::monthAt, calendar::bounds, sample)
    private val query = LocalCashLedgerQuery(snapshot, monthAt, sample)

    fun page(
        filters: CashFiltersDto = CashFiltersDto(),
        timeZoneId: String? = null,
        cursor: String? = null,
        startIndex: Int? = null,
        expectedLedgerSeq: Long? = null,
        limit: Int = 20,
    ): CashPageDto = page(filters, checkNotNull(dateBounds).invoke(filters.dateFrom, filters.dateTo, timeZoneId),
        cursor, startIndex, expectedLedgerSeq, limit)

    fun page(
        filters: CashFiltersDto, bounds: CashLedgerDateBounds, cursor: String?,
        startIndex: Int?, expectedLedgerSeq: Long?, limit: Int,
    ): CashPageDto = query.page(normalizeLocalCashLedgerQueryInput(filters, bounds.timeZoneId) { _, _, _ -> bounds },
        cursor, startIndex, expectedLedgerSeq, limit)

    fun summary(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null): CashLedgerSummary {
        val page = page(filters, timeZoneId, limit = 1)
        return CashLedgerSummary(snapshot.workspaceId, snapshot.ledgerSeq, page.filters, page.monthlySummaries, page.pendingItemCount)
    }

    fun currentGroup(projectionId: String) = query.currentGroup(projectionId)
    fun record(transactionId: String, canWrite: Boolean) = query.record(transactionId, canWrite)
    fun evidence(projectionId: String) = query.evidence(projectionId)
    fun projectionRefs(transactionIds: List<String>) = query.projectionRefs(transactionIds)
}

fun cashCategoryDirectory(snapshot: LedgerSnapshotDto) =
    buildLocalCashCategoryDirectory(snapshot.facts.cashCategories, snapshot.facts.categoryDirectoryRevision)
