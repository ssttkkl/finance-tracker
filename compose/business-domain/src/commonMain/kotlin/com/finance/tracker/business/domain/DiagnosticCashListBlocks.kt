package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashFilterOptionsDto
import com.finance.tracker.business.contracts.CashMonthPositionDto
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CashProjectionDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

@Serializable
internal data class DiagnosticCashTime(val seconds: Long, val nanos: Int) {
    constructor(time: Instant) : this(time.epochSeconds, time.nanosecondsOfSecond)
    fun instant(): Instant = Instant.fromEpochSeconds(seconds, nanos.toLong())
}

@Serializable
internal data class DiagnosticCashListRow(
    val id: String,
    val sortAt: DiagnosticCashTime,
    val occurred: DiagnosticCashTime?,
    val state: String,
    val pending: Int,
    val statisticsEligible: Boolean,
    val amount: String?,
    val currency: String?,
    val economicType: String?,
    val businessLabel: String?,
    val composition: List<String>,
    val category: String?,
    val categoryPath: List<String>,
    val counterparty: String,
    val note: String,
    val accounts: Set<String>,
    val members: List<String>,
) {
    fun accepts(input: LocalCashLedgerQueryInput): Boolean {
        val filters = input.normalizedFilters
        if (input.transactionIds != null && members.none { it in input.transactionIds }) return false
        if (filters["state"] != "all" && state != filters["state"]) return false
        filters["confirmation_status"]?.let { if ((pending > 0) != (it == "pending")) return false }
        val projectedAmount by lazy { amount?.let(LedgerArithmeticValue::fromCanonical) }
        if (filters["hide_zero_amount"] == "True" && projectedAmount?.signum == 0) return false
        val occurredAt by lazy { occurred?.instant() }
        if (input.start != null && (occurredAt == null || occurredAt!! < input.start)) return false
        if (input.end != null && (occurredAt == null || occurredAt!! >= input.end)) return false
        filters["account_id"]?.let { if (it !in accounts) return false }
        filters["currency"]?.let { if (currency != it) return false }
        filters["category_id"]?.let { if (it !in categoryPath) return false }
            ?: run { if (filters["uncategorized"] == "True" && category != null) return false }
        filters["economic_type"]?.let { if (economicType != it) return false }
        filters["business_label"]?.let { if (businessLabel != it) return false }
        if (!cashLedgerKeywordMatches(counterparty, note, filters["counterparty"])) return false
        if (input.minimum != null || input.maximum != null) {
            val value = projectedAmount ?: return false
            if (input.minimum != null && value < input.minimum || input.maximum != null && value > input.maximum) return false
        }
        return when (filters["composition"]) {
            "single" -> composition.isEmpty()
            "payment_mirror" -> "payment_mirror" in composition
            "refund_offset" -> "refund_offset" in composition
            "combined" -> composition.size >= 2
            else -> true
        }
    }
}

@Serializable
data class DiagnosticCashListDirectory(
    val workspace: String,
    val ledgerSeq: String,
    val options: CashFilterOptionsDto,
    val categoryIds: Set<String>,
    val blocks: Int,
)

private data class CashListLocation(val block: Int, val offset: Int, val id: String, val sortAt: Instant)

fun readDiagnosticCashList(
    directory: DiagnosticCashListDirectory,
    read: (Int, Boolean) -> String,
    monthAt: (Instant, String) -> String,
    input: LocalCashLedgerQueryInput,
    cursor: String?,
    startIndex: Int?,
    expectedLedgerSeq: Long?,
    limit: Int,
): CashPageDto {
    val version = directory.ledgerSeq.toLong()
    if (limit !in 1..50) throw CashProjectionDomainFailure("invalid_filter")
    if (startIndex != null && (startIndex < 0 || cursor != null) ||
        expectedLedgerSeq != null && (startIndex == null || expectedLedgerSeq < 0)) throw CashProjectionDomainFailure("invalid_cursor")
    if (expectedLedgerSeq != null && expectedLedgerSeq != version) throw CashProjectionDomainFailure("read_snapshot_changed")
    val scope = CashLedgerCursorScope(directory.workspace, version, input.normalizedFilters)
    val position = cursor?.takeIf(String::isNotEmpty)?.let(scope::decode)
    input.normalizedFilters["category_id"]?.let { if (it !in directory.categoryIds) throw CashProjectionDomainFailure("invalid_filter") }
    val summaries = if (expectedLedgerSeq == null)
        CashProjectionMonthlyRules<Instant>(monthAt = { monthAt(it, input.normalizedFilters.getValue("timezone")!!) })
    else null
    val selected = ArrayList<CashListLocation>(limit)
    var filtered = 0
    var pending = 0L
    var hasNext = false
    repeat(directory.blocks) { block ->
        val rows = Json.decodeFromString<List<DiagnosticCashListRow>>(read(block, false))
        for ((offset, row) in rows.withIndex()) {
            if (!row.accepts(input)) continue
            summaries?.addAmount(row.occurred?.instant(), row.economicType, row.currency,
                row.statisticsEligible, row.pending.toLong()) {
                LedgerArithmeticValue.fromCanonical(requireNotNull(row.amount))
            }
            if (summaries == null && row.pending > 0) pending++
            val index = filtered++
            if (hasNext) continue
            val order = row.sortAt.instant()
            val inPage = if (startIndex != null) index >= startIndex else position == null || position.isAfter(order, row.id)
            if (!inPage) continue
            if (selected.size < limit) selected.add(CashListLocation(block, offset, row.id, order)) else hasNext = true
        }
    }
    val items = selected.groupBy { it.block }.flatMap { (block, locations) ->
        val details = Json.decodeFromString<List<CashProjectionDto>>(read(block, true))
        locations.map { location -> details[location.offset].also { check(it.projectionId == location.id) } }
    }
    val monthly = summaries?.result()
    return CashPageDto(version, items = items,
        nextCursor = if (startIndex == null && hasNext) selected.last().let { scope.encode(it.sortAt, it.id) } else null,
        pageSize = limit, filters = input.filters, filterOptions = directory.options,
        monthlySummaries = monthly?.summaries?.map { Json.decodeFromJsonElement<CashMonthlySummaryDto>(it) }.orEmpty(),
        pendingItemCount = monthly?.pending ?: pending,
        monthPositions = if (startIndex != null) monthly?.positions?.map { Json.decodeFromJsonElement<CashMonthPositionDto>(it) }.orEmpty() else emptyList(),
        startIndex = startIndex, totalCount = if (startIndex != null) filtered else null,
        itemsBefore = startIndex?.coerceAtMost(filtered),
        itemsAfter = startIndex?.let { (filtered - it - items.size).coerceAtLeast(0) })
}
