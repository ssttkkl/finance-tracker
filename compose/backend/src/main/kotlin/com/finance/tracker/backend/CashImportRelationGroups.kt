package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant

/** Python 导入建议摘要沿用 default=str 的计算值表示。 */
internal fun cashImportDigest(vararg values: Any?): String =
    com.finance.tracker.business.domain.cashImportComputedDigest(values.toList(),
        { if (it is Instant) ParsedImportCodec.timeText(it).replace('T', ' ') else null },
        { sourceDigest(*it.toTypedArray()) })

internal fun cashImportVirtual(kind: String, vararg values: Any?): BigInteger = BigInteger(
    com.finance.tracker.business.domain.cashImportVirtualId(kind, values.toList()) { sourceDigest(*it.toTypedArray()) })

internal fun cashImportEdgeKey(edge: CashRelationGraphEdge): List<Any?> =
    com.finance.tracker.business.domain.cashImportEdgeKey(edge)

internal fun cashImportEdgeMap(edge: CashRelationGraphEdge): Map<String, Any?> =
    com.finance.tracker.business.domain.cashImportEdgeMap(edge)
internal fun cashImportLink(edge: CashRelationGraphEdge, group: String? = null) =
    com.finance.tracker.business.domain.cashImportLink(edge, group)
internal fun cashImportClosures(edges: Collection<CashRelationGraphEdge>): Map<String, Set<String>> =
    com.finance.tracker.business.domain.cashImportClosures(edges)

internal fun cashImportRef(snapshot: CashImportPreviewSnapshot, identifier: String): Map<String, Any?> =
    com.finance.tracker.business.domain.cashImportRef(snapshot, identifier)

internal typealias CashImportRelationGroupResult = com.finance.tracker.business.domain.CashImportRelationGroupResult

internal object CashImportRelationGroups {
    private val rules = com.finance.tracker.business.domain.CashImportRelationGroupRules<BigInteger, Instant>(
        ::strictCashInteger, { kind, values -> cashImportVirtual(kind, *values.toTypedArray()) },
        { values -> cashImportDigest(*values.toTypedArray()) }, { BigInteger(it) < BigInteger.ONE.shiftLeft(63) },
        { CashRelationGeometry.asDecimal(it).toString() }, { it.arithmeticValue().signum() < 0 },
        CashImportRelationDiscovery.Companion::originalExpense)

    fun build(snapshot: CashImportPreviewSnapshot, proposals: List<RelationProposal>): CashImportRelationGroupResult =
        rules.build(snapshot, proposals)
}
