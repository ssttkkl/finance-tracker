package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Bind preview preparation reads to one complete snapshot and the caller's active-scope guard. */
class LocalCashImportPreviewPreparation(source: LedgerSnapshotDto, requireActive: () -> Unit) {
    private val rules = CashImportPreviewPreparationRules<Long, Instant>(requireActive,
        { source.facts.accounts.associate { it.id to linkedMapOf("name" to it.name, "type" to it.type, "revision" to it.revision.toLong()) } },
        { operations, rows -> readLocalCashImportCandidateIds(source, prepareLocalCashImportCandidateQuery(operations, rows)) },
        { seeds, groups -> readLocalCashRelationContext(source, seeds, groups) })

    fun prepare(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence>,
        entryChoices: List<Map<String, Any?>> = emptyList(), previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet()): PreparedCashImportPreview<Long, Instant> =
        rules.prepare(operations, sourceRows, entryChoices, previousPlan, additionalSeedIds)

    fun prepareOperations(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence> = emptyMap(),
        entryChoices: List<Map<String, Any?>> = emptyList(), previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet(), decisionGroupIds: List<String> = emptyList()): PreparedCashImportPreview<Long, Instant> =
        rules.prepareOperations(operations, sourceRows, entryChoices, previousPlan, additionalSeedIds, decisionGroupIds)
}
