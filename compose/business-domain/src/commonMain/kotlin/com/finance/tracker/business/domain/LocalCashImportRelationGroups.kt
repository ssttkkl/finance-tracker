package com.finance.tracker.business.domain

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

private val localCashImportDecisions = CashImportRelationDecisionRules<CashImportPreviewInteger, Instant>(
    ::localCashPreviewInteger, ::copyLocalCashValue, ::localCashImportDigest)

private val localCashImportGroups = CashImportRelationGroupRules<CashImportPreviewInteger, Instant>(
    ::localCashPreviewInteger, { kind, values -> CashImportPreviewInteger.parse(localCashImportVirtualId(kind, values)) },
    ::localCashImportDigest, { CashImportPreviewInteger.parse(it) < CashImportPreviewInteger.parse("9223372036854775808") },
    { LedgerArithmeticValue.fromCanonical(it.toString()).toString() },
    { LedgerArithmeticValue.fromCashAmount(it).signum < 0 }, ::localCashImportOriginalExpense)
