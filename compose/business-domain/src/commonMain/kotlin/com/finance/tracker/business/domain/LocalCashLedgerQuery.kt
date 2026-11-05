package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

/** Only the input adapter constructs this after normalizing the complete filter map and zone. */
class LocalCashLedgerQueryInput internal constructor(filters: Map<String, String?>, val start: Instant? = null, val end: Instant? = null) {
    internal val normalizedFilters: Map<String, String?> = filters.toMap()
    val filters: Map<String, String?> get() = normalizedFilters.toMap()
    internal val minimum = normalizedFilters["amount_min"]?.let(LedgerArithmeticValue::fromCanonical)
    internal val maximum = normalizedFilters["amount_max"]?.let(LedgerArithmeticValue::fromCanonical)
}

/** One committed snapshot; filtering, counts and pagination cannot observe different captures. */
class LocalCashLedgerQuery(snapshot: LedgerSnapshotDto, private val monthAt: (Instant, String) -> String) {
    private class Row(val entry: LocalCashLedgerEntry, val view: CashProjectionDto) {
        val amount = view.amount?.let(LedgerArithmeticValue::fromCanonical)
    }
    private val workspace = snapshot.workspaceId
    private val version = snapshot.ledgerSeq.toLong()
    private val renderer = LocalCashProjectionViews(snapshot)
    private val options = renderer.options()
    private val categories = snapshot.facts.cashCategories.associateBy { it.id }
    private val componentAccounts = snapshot.facts.cashTransactionComponents.groupBy { it.cashTransactionId }
        .mapValues { (_, parts) -> parts.mapNotNull { it.accountId }.toSet() }
    private val rows = renderer.entries.zip(renderer.build()).map { (entry, view) -> Row(entry, view) }
        .filter { it.view.visible || it.view.attentionCount > 0 }
        .sortedWith { first, second ->
            second.entry.projection.sortAt.compareTo(first.entry.projection.sortAt).takeIf { it != 0 }
                ?: pythonStringOrder(second.view.projectionId, first.view.projectionId)
        }

    private fun accepts(row: Row, input: LocalCashLedgerQueryInput): Boolean {
        val filters = input.normalizedFilters
        val view = row.view
        if (filters["state"] != "all" && view.state != filters["state"]) return false
        val confirmation = filters["confirmation_status"]
        if (confirmation != null && (view.attentionCount > 0) != (confirmation == "pending")) return false
        if (filters["hide_zero_amount"] == "True" && row.amount?.signum == 0) return false
        val occurred = row.entry.projection.occurredAt
        if (input.start != null && (occurred == null || occurred < input.start)) return false
        if (input.end != null && (occurred == null || occurred >= input.end)) return false
        filters["account_id"]?.let { account ->
            if (row.entry.projection.primaryRecord.accountId?.toString() != account &&
                row.entry.projection.memberIds.none { account in componentAccounts[it.toString()].orEmpty() }) return false
        }
        filters["currency"]?.let { if (view.currency != it) return false }
        filters["category_id"]?.let { category ->
            if (view.category?.path.orEmpty().none { it.id == category }) return false
        } ?: run { if (filters["uncategorized"] == "True" && row.entry.entry.categoryId != null) return false }
        filters["economic_type"]?.let { if (view.economicType != it) return false }
        filters["business_label"]?.let { if (view.businessLabel != it) return false }
        if (!cashLedgerKeywordMatches(view.counterparty, view.note, filters["counterparty"])) return false
        if (input.minimum != null && (row.amount == null || row.amount < input.minimum)) return false
        if (input.maximum != null && (row.amount == null || row.amount > input.maximum)) return false
        val kinds = view.composition.toSet()
        when (filters["composition"]) {
            "single" -> if (kinds.any { it in setOf("payment_mirror", "refund_offset", "transfer_pair") }) return false
            "payment_mirror" -> if ("payment_mirror" !in kinds) return false
            "refund_offset" -> if ("refund_offset" !in kinds) return false
            "combined" -> if (kinds.count { it in setOf("payment_mirror", "refund_offset", "transfer_pair") } < 2) return false
        }
        return true
    }

    fun page(input: LocalCashLedgerQueryInput, cursor: String? = null, startIndex: Int? = null,
        expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto {
        if (limit !in 1..50) throw CashProjectionDomainFailure("invalid_filter")
        if (startIndex != null && (startIndex < 0 || cursor != null) ||
            expectedLedgerSeq != null && (startIndex == null || expectedLedgerSeq < 0)) throw CashProjectionDomainFailure("invalid_cursor")
        if (expectedLedgerSeq != null && expectedLedgerSeq != version) throw CashProjectionDomainFailure("read_snapshot_changed")
        val scope = CashLedgerCursorScope(workspace, version, input.normalizedFilters)
        val position = cursor?.takeIf(String::isNotEmpty)?.let(scope::decode)
        input.normalizedFilters["category_id"]?.let { if (it !in categories) throw CashProjectionDomainFailure("invalid_filter") }
        val filtered = rows.filter { accepts(it, input) }
        val selected = if (startIndex != null) filtered.drop(startIndex).take(limit)
            else filtered.asSequence().filter { position == null || position.isAfter(it.entry.projection.sortAt, it.view.projectionId) }.take(limit + 1).toList()
        val items = selected.take(limit)
        val monthly = if (expectedLedgerSeq == null) CashProjectionMonthlyRules<Instant>(monthAt = { monthAt(it, input.normalizedFilters.getValue("timezone")!!) }).apply {
            for (row in filtered) add(row.entry.projection.occurredAt, row.view.economicType, row.view.amount, row.view.currency,
                row.view.statisticsEligible, row.view.attentionCount)
        }.result() else null
        return CashPageDto(version, items = items.map { it.view },
            nextCursor = if (startIndex == null && selected.size > limit) items.last().let { scope.encode(it.entry.projection.sortAt, it.view.projectionId) } else null,
            pageSize = limit, filters = input.filters, filterOptions = options,
            monthlySummaries = monthly?.summaries?.map { Json.decodeFromJsonElement<CashMonthlySummaryDto>(it) }.orEmpty(),
            pendingItemCount = monthly?.pending ?: filtered.count { it.view.attentionCount > 0 }.toLong(),
            monthPositions = if (startIndex != null) monthly?.positions?.map { Json.decodeFromJsonElement<CashMonthPositionDto>(it) }.orEmpty() else emptyList(),
            startIndex = startIndex, totalCount = if (startIndex != null) filtered.size else null,
            itemsBefore = startIndex?.coerceAtMost(filtered.size),
            itemsAfter = startIndex?.let { (filtered.size - it - items.size).coerceAtLeast(0) })
    }
}
