package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS

internal typealias PreparedCashImportPreview = com.finance.tracker.business.domain.PreparedCashImportPreview<java.math.BigInteger, java.time.Instant>

/** Native authorization and workspace-scoped SQL reads for the shared preview preparation rule. */
internal class CashImportPreviewPreparation(private val context: CashSqlContext) {
    private val rules = com.finance.tracker.business.domain.CashImportPreviewPreparationRules<java.math.BigInteger, java.time.Instant>(
        context::requireActive, ::readAccounts,
        { operations, sourceRows -> CashImportCandidateRepository(context).candidateIds(operations, sourceRows) },
        { seeds, groups -> CashRelationContextService(context).load(seeds, groups) })

    private fun readAccounts(): Map<String, Map<String, Any?>> {
        val accounts = linkedMapOf<String, Map<String, Any?>>()
        val table = ACCOUNTS
        context.sql.select(table.ID, table.NAME, table.TYPE, table.REVISION).from(table)
            .where(table.WORKSPACE_ID.eq(context.workspace)).fetch().forEach { row ->
                accounts[row[table.ID]!!.toString()] = linkedMapOf(
                    "name" to row[table.NAME], "type" to row[table.TYPE], "revision" to row[table.REVISION])
            }
        return accounts
    }

    fun prepare(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence>,
        entryChoices: List<Map<String, Any?>> = emptyList(), previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet()): PreparedCashImportPreview =
        rules.prepare(operations, sourceRows, entryChoices, previousPlan, additionalSeedIds)

    fun prepareOperations(operations: List<Map<String, Any?>>, sourceRows: Map<String, SourceOccurrence> = emptyMap(),
        entryChoices: List<Map<String, Any?>> = emptyList(), previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet(), decisionGroupIds: List<String> = emptyList()): PreparedCashImportPreview =
        rules.prepareOperations(operations, sourceRows, entryChoices, previousPlan, additionalSeedIds, decisionGroupIds)
}
