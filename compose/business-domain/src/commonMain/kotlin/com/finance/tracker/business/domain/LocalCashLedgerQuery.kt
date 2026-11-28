package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.time.Instant

/** Only the input adapter constructs this after normalizing the complete filter map and zone. */
class LocalCashLedgerQueryInput internal constructor(filters: Map<String, String?>,
    val start: Instant? = null, val end: Instant? = null, internal val transactionIds: Set<String>? = null) {
    internal val normalizedFilters: Map<String, String?> = filters.toMap()
    val filters: Map<String, String?> get() = normalizedFilters.toMap()
    internal val minimum = normalizedFilters["amount_min"]?.let(LedgerArithmeticValue::fromCanonical)
    internal val maximum = normalizedFilters["amount_max"]?.let(LedgerArithmeticValue::fromCanonical)
}

/** One committed snapshot; filtering, counts and pagination cannot observe different captures. */
class LocalCashLedgerQuery(snapshot: LedgerSnapshotDto, private val monthAt: (Instant, String) -> String,
    private val sample: (String, Int?) -> Unit) {
    constructor(snapshot: LedgerSnapshotDto, monthAt: (Instant, String) -> String) : this(snapshot, monthAt, { _, _ -> })
    private val workspace = snapshot.workspaceId
    private val version = snapshot.ledgerSeq.toLong()
    private val renderer = LocalCashProjectionViews(snapshot, sample).also { sample("ledger_graph_ready", null) }
    private val details by lazy { LocalCashEntryRead(snapshot, renderer) }
    private val records by lazy { LocalCashRecordRead(snapshot, renderer) }
    private val projectionByTransactionId by lazy {
        buildMap {
            for (entry in renderer.entries) for (id in entry.projection.memberIds) {
                check(put(id.toString(), entry.projectionId) == null) { "cash_projection_member_repeated" }
            }
        }
    }
    private val options = renderer.options().also { sample("ledger_options_ready", null) }
    private val categories = snapshot.facts.cashCategories.associateBy { it.id }
    private val componentAccounts by lazy {
        buildMap<String, MutableSet<String>> {
            for (part in snapshot.facts.cashTransactionComponents) {
                val account = part.accountId ?: continue
                getOrPut(part.cashTransactionId) { mutableSetOf() }.add(account)
            }
        }
    }
    private val rows by lazy {
        sample("ledger_sort_start", null)
        renderer.entries.filter { it.projection.visible || it.projection.attentionCount > 0 }
            .sortedWith { first, second ->
                second.projection.sortAt.compareTo(first.projection.sortAt).takeIf { it != 0 }
                    ?: pythonStringOrder(second.projectionId, first.projectionId)
            }.also { sample("ledger_sort_ready", null) }
    }

    /** Current stable entry locations, including members which are not the display representative. */
    fun projectionRefs(transactionIds: List<String>): List<CashMutationProjectionRefDto> =
        transactionIds.distinct().mapNotNull { id -> projectionByTransactionId[id]?.let { CashMutationProjectionRefDto(id, it) } }

    fun currentGroup(projectionId: String): CashCurrentMembersDto = details.currentGroup(projectionId)

    fun evidence(projectionId: String): JsonObject = details.evidence(projectionId)

    fun record(transactionId: String, canWrite: Boolean): JsonObject = records.read(transactionId, canWrite)

    private fun accepts(row: LocalCashLedgerEntry, input: LocalCashLedgerQueryInput): Boolean {
        val filters = input.normalizedFilters
        val projection = row.projection
        val root = projection.primaryRecord
        if (input.transactionIds != null && projection.memberIds.none { it.toString() in input.transactionIds }) return false
        if (filters["state"] != "all" && projection.state != filters["state"]) return false
        val confirmation = filters["confirmation_status"]
        if (confirmation != null && (projection.attentionCount > 0) != (confirmation == "pending")) return false
        val amount = projection.netAmount
        if (filters["hide_zero_amount"] == "True" && amount?.representation?.coefficient == "0") return false
        val occurred = projection.occurredAt
        if (input.start != null && (occurred == null || occurred < input.start)) return false
        if (input.end != null && (occurred == null || occurred >= input.end)) return false
        filters["account_id"]?.let { account ->
            if (root.accountId?.toString() != account &&
                projection.memberIds.none { account in componentAccounts[it.toString()].orEmpty() }) return false
        }
        filters["currency"]?.let { if (root.currency != it) return false }
        filters["category_id"]?.let { category ->
            if (renderer.category(row.entry.categoryId)?.path.orEmpty().none { it.id == category }) return false
        } ?: run { if (filters["uncategorized"] == "True" && row.entry.categoryId != null) return false }
        filters["economic_type"]?.let { if (projection.economicType?.value != it) return false }
        filters["business_label"]?.let { if (projection.businessLabel != it) return false }
        if (!cashLedgerKeywordMatches(row.entry.counterparty, row.entry.note, filters["counterparty"])) return false
        if (input.minimum != null || input.maximum != null) {
            val value = amount?.let(LedgerArithmeticValue::fromCashAmount) ?: return false
            if (input.minimum != null && value < input.minimum || input.maximum != null && value > input.maximum) return false
        }
        when (filters["composition"]) {
            "single" -> if (projection.compositions.isNotEmpty()) return false
            "payment_mirror" -> if ("payment_mirror" !in projection.compositions) return false
            "refund_offset" -> if ("refund_offset" !in projection.compositions) return false
            "combined" -> if (projection.compositions.size < 2) return false
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
        val summaries = if (expectedLedgerSeq == null)
            CashProjectionMonthlyRules<Instant>(monthAt = { monthAt(it, input.normalizedFilters.getValue("timezone")!!) })
        else null
        val items = ArrayList<LocalCashLedgerEntry>(limit)
        var filteredCount = 0
        var pendingWithoutSummaries = 0L
        var hasNext = false
        val sortedRows = rows
        sample("ledger_scan_start", null)
        for (row in sortedRows) {
            if (!accepts(row, input)) continue
            val projection = row.projection
            summaries?.addAmount(projection.occurredAt, projection.economicType?.value, projection.primaryRecord.currency,
                projection.statisticsEligible, projection.attentionCount.toLong()) {
                LedgerArithmeticValue.fromCashAmount(requireNotNull(projection.netAmount))
            }
            if (summaries == null && projection.attentionCount > 0) pendingWithoutSummaries++
            val index = filteredCount++
            if (hasNext) continue
            val inPage = if (startIndex != null) index >= startIndex
                else position == null || position.isAfter(projection.sortAt, row.projectionId)
            if (inPage) {
                if (items.size < limit) items.add(row) else hasNext = true
            }
        }
        val monthly = summaries?.result()
        sample("ledger_scan_ready", filteredCount)
        val rendered = items.map(renderer::render)
        sample("ledger_items_ready", rendered.size)
        return CashPageDto(version, items = rendered,
            nextCursor = if (startIndex == null && hasNext) items.last().let { scope.encode(it.projection.sortAt, it.projectionId) } else null,
            pageSize = limit, filters = input.filters, filterOptions = options,
            monthlySummaries = monthly?.summaries?.map { Json.decodeFromJsonElement<CashMonthlySummaryDto>(it) }.orEmpty(),
            pendingItemCount = monthly?.pending ?: pendingWithoutSummaries,
            monthPositions = if (startIndex != null) monthly?.positions?.map { Json.decodeFromJsonElement<CashMonthPositionDto>(it) }.orEmpty() else emptyList(),
            startIndex = startIndex, totalCount = if (startIndex != null) filteredCount else null,
            itemsBefore = startIndex?.coerceAtMost(filteredCount),
            itemsAfter = startIndex?.let { (filteredCount - it - items.size).coerceAtLeast(0) })
    }
}
