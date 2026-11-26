package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
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

class CashLedgerOperations(private val snapshot: LedgerSnapshotDto, private val calendar: CashLedgerCalendar) {
    private val query = LocalCashLedgerQuery(snapshot, calendar::monthAt)

    fun page(
        filters: CashFiltersDto = CashFiltersDto(),
        timeZoneId: String? = null,
        cursor: String? = null,
        startIndex: Int? = null,
        expectedLedgerSeq: Long? = null,
        limit: Int = 20,
    ): CashPageDto =
        query.page(normalizeLocalCashLedgerQueryInput(filters, timeZoneId, calendar::bounds),
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
