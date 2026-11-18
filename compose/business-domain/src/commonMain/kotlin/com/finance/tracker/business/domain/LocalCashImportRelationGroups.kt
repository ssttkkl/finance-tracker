package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

internal fun localCashImportOriginalExpense(value: Map<String, Any?>): Boolean = cashImportOriginalExpense(value,
    { LedgerArithmeticValue.fromCashAmount(it).signum < 0 }, { direction, status ->
        cashWechatRefundOriginExpense(direction, status) { text ->
            try { LedgerArithmeticValue.fromCanonical(text) } catch (_: IllegalArgumentException) { null }
            catch (_: ArithmeticException) { null }
        }
    })

/** Build groups from already-computed proposals and real local preview state; no matching, IO or writes. */
fun buildLocalCashImportRelationGroups(snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>,
    proposals: List<RelationProposal>): CashImportRelationGroupResult = localCashImportGroups.build(snapshot, proposals)

/** Apply decided groups to local preview state; this mutates the preview and group results, never stored facts. */
fun applyLocalCashImportRelationGroups(snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>,
    groups: MutableList<MutableMap<String, Any?>>): List<Map<String, Any?>> = localCashImportDecisions.applyGroups(snapshot, groups)

/** Restore standalone public fields for selected splits from the same complete source snapshot. */
fun restoreLocalCashImportSplitValues(source: LedgerSnapshotDto,
    snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>, groups: List<MutableMap<String, Any?>>) {
    val closures by lazy {
        val links = CashRelationGroups()
        for (edge in source.facts.transactionRelations) if (edge.active && edge.state == "confirmed") {
            val secondary = edge.secondaryTransactionId ?: continue
            links.join(edge.primaryTransactionId, secondary)
        }
        links.parents.keys.groupBy(links::root).values.flatMap { members ->
            val all = members.map(String::toLong).toSet()
            members.map { it.toLong() to all }
        }.toMap()
    }
    val existing by lazy { source.facts.cashTransactions.map { it.id.toLong() }.toSet() }
    localCashImportDecisions.restoreSplitValues(snapshot, groups,
        { source.facts.cashEntryMembers.filter { it.transactionId.toLong() in snapshot.context.transactionIds }
            .associateBy { it.transactionId.toLong() } },
        { member ->
            @Suppress("UNCHECKED_CAST")
            (decodeLocalCashValue(member.standaloneValues.jsonObject) as Map<String, Any?>)
        },
        { ids ->
            if (ids.any { it <= 0 }) relationFailure("relation_data_invalid")
            ids.filter { it in existing }.flatMap { closures[it] ?: setOf(it) }.sorted().toSet()
        })
}

private val localCashImportDecisions = CashImportRelationDecisionRules<CashImportPreviewInteger, Instant>(
    ::localCashPreviewInteger, ::copyLocalCashValue, ::localCashImportDigest)

private val localCashImportGroups = CashImportRelationGroupRules<CashImportPreviewInteger, Instant>(
    ::localCashPreviewInteger, { kind, values -> CashImportPreviewInteger.parse(localCashImportVirtualId(kind, values)) },
    ::localCashImportDigest, { CashImportPreviewInteger.parse(it) < CashImportPreviewInteger.parse("9223372036854775808") },
    { LedgerArithmeticValue.fromCanonical(it.toString()).toString() },
    { LedgerArithmeticValue.fromCashAmount(it).signum < 0 }, ::localCashImportOriginalExpense)
