package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Complete one exclusively owned preview using already-computed proposals, never performing discovery or writes. */
fun completeLocalCashImportPreviewPlan(source: LedgerSnapshotDto, prepared: PreparedCashImportPreview<Long, Instant>,
    snapshot: CashImportPreviewSnapshot<CashImportPreviewInteger, Instant>, proposals: List<RelationProposal>,
    files: List<ParsedImportFile>, input: Map<String, Any?>, previous: Map<String, Any?>?,
    sourceCandidateSets: Map<String, List<String>> = emptyMap()): MutableMap<String, Any?> {
    @Suppress("UNCHECKED_CAST")
    fun choices(key: String) = (input[key] as? List<Map<String, Any?>>).orEmpty()
    val rules = CashImportPreviewPlanRules<CashImportPreviewInteger, Instant>(
        ::buildLocalCashImportRelationGroups, ::applyLocalCashImportRelationChoices,
        { preview, groups -> restoreLocalCashImportSplitValues(source, preview, groups) },
        ::applyLocalCashImportRelationGroups, ::presentLocalCashImportPreview,
        ::localCashImportDigest, ::copyLocalCashValue)
    val result = rules.present(snapshot, proposals, files, prepared.accounts, prepared.candidateIds,
        prepared.retainedParentIds, sourceCandidateSets, cashImportPreviewDiscoverySeeds(snapshot), choices("relation_choices"), previous)
    applyLocalCashImportEntryChoices(result, choices("entry_choices"), previous, source.facts.cashCategories.map { it.id }.toSet())
    result["operations"] = snapshot.operations
    result["row_decisions"] = snapshot.rows
    return result
}
