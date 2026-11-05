package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_CATEGORIES
import com.finance.tracker.business.domain.CashImportPreviewPlanRules
import com.finance.tracker.business.domain.cashImportPreviewDiscoverySeeds
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant

/** 在当前事务中构建独占的冻结预览；财务事实只读，发布由会话服务负责。 */
internal open class CashImportBusinessPlanBuilder(private val context: CashSqlContext) {
    private data class PreparedPreview(val preview: PreparedCashImportPreview,
        val rows: List<Map<String, Any?>>, val candidateSets: Map<String, List<String>>)
    private data class RenderedPreview(val result: MutableMap<String, Any?>,
        val operations: List<Map<String, Any?>>, val rows: List<Map<String, Any?>>)
    @Suppress("UNCHECKED_CAST")
    private fun choices(input: Map<String, Any?>, key: String): List<Map<String, Any?>> =
        (input[key] as? List<Map<String, Any?>>).orEmpty()

    protected open fun prepareOperations(files: List<ParsedImportFile>, input: Map<String, Any?>,
        recordIds: Map<String, String>, sourcePlan: PreparedCashSourcePlan?): PreparedCashImportOperations =
        CashImportOperationPlanner(context).prepare(files, choices(input, "account_selections"),
            choices(input, "row_choices"), recordIds, sourcePlan, wireValues = false)

    protected open fun preparePreview(operations: PreparedCashImportOperations, input: Map<String, Any?>,
        previous: Map<String, Any?>?): PreparedCashImportPreview = CashImportPreviewPreparation(context).prepare(
        operations.operations, operations.source.rows.rows, choices(input, "entry_choices"), previous)

    protected open fun discover(snapshot: CashImportPreviewSnapshot, seeds: Set<String>,
        accounts: Map<String, Map<String, Any?>>): List<RelationProposal> =
        CashImportRelationDiscovery(context).discover(snapshot, seeds, accounts)

    protected open fun snapshot(prepared: PreparedCashImportPreview, rows: List<Map<String, Any?>>): CashImportPreviewSnapshot =
        CashImportPreviewSnapshotBuilder(context).build(prepared, rows)

    protected open fun applyEntryChoices(result: MutableMap<String, Any?>, choices: List<Map<String, Any?>>, previous: Map<String, Any?>?) {
        CashImportEntryChoices.apply(result, choices, previous) { id ->
            val table = CASH_CATEGORIES
            context.sql.select(table.ID).from(table).where(table.WORKSPACE_ID.eq(context.workspace).and(table.ID.eq(id))).fetchAny() != null
        }
    }

    fun build(files: List<ParsedImportFile>, input: Map<String, Any?>, previous: Map<String, Any?>?,
        sourcePlan: PreparedCashSourcePlan? = null): Map<String, Any?> {
        context.requireActive()
        return finish(files, prepare(files, input, previous, sourcePlan), input, previous)
    }

    fun buildOperations(operations: List<Map<String, Any?>>, rows: List<Map<String, Any?>>,
        input: Map<String, Any?>, previous: Map<String, Any?>?, files: List<ParsedImportFile> = emptyList(),
        sourceRows: Map<String, SourceOccurrence> = emptyMap(), sourceCandidateSets: Map<String, List<String>> = emptyMap(),
        includeUnchanged: Boolean = false, additionalSeedIds: Set<Long> = emptySet(), decisionGroupIds: List<String> = emptyList(),
        snapshotTransform: ((CashImportPreviewSnapshot) -> Unit)? = null,
        currentRelationBuilder: ((CashImportPreviewSnapshot, Map<String, Map<String, Any?>>) -> List<MutableMap<String, Any?>>)? = null,
    ): Map<String, Any?> {
        context.requireActive()
        val prepared = CashImportPreviewPreparation(context).prepareOperations(operations, sourceRows,
            choices(input, "entry_choices"), previous, additionalSeedIds, decisionGroupIds)
        return finish(files, PreparedPreview(prepared, rows, sourceCandidateSets), input, previous,
            includeUnchanged, snapshotTransform, currentRelationBuilder)
    }

    private fun finish(files: List<ParsedImportFile>, prepared: PreparedPreview, input: Map<String, Any?>,
        previous: Map<String, Any?>?, includeUnchanged: Boolean = false,
        snapshotTransform: ((CashImportPreviewSnapshot) -> Unit)? = null,
        currentRelationBuilder: ((CashImportPreviewSnapshot, Map<String, Map<String, Any?>>) -> List<MutableMap<String, Any?>>)? = null,
    ): Map<String, Any?> {
        val rendered = present(files, prepared, input, previous, includeUnchanged, snapshotTransform, currentRelationBuilder)
        applyEntryChoices(rendered.result, choices(input, "entry_choices"), previous)
        rendered.result["operations"] = rendered.operations
        rendered.result["row_decisions"] = rendered.rows
        @Suppress("UNCHECKED_CAST") return wire(rendered.result) as Map<String, Any?>
    }

    private fun prepare(files: List<ParsedImportFile>, input: Map<String, Any?>, previous: Map<String, Any?>?,
        sourcePlan: PreparedCashSourcePlan?): PreparedPreview {
        val recordIds = linkedMapOf<String, String>()
        for (raw in (previous?.get("operations") as? List<*>).orEmpty()) {
            val operation = raw as Map<*, *>
            val occurrence = operation["occurrence_key"] as? String
            if (!occurrence.isNullOrEmpty() && operation["transaction_id"] == null) {
                val values = operation["values"] as Map<*, *>
                recordIds[occurrence] = values["record_id"] as String
            }
        }
        val operations = prepareOperations(files, input, recordIds, sourcePlan)
        val prepared = preparePreview(operations, input, previous)
        return PreparedPreview(prepared, operations.rows, operations.candidateSets)
    }

    private fun present(files: List<ParsedImportFile>, input: PreparedPreview, inputChoices: Map<String, Any?>,
        previous: Map<String, Any?>?, includeUnchanged: Boolean,
        snapshotTransform: ((CashImportPreviewSnapshot) -> Unit)?,
        currentRelationBuilder: ((CashImportPreviewSnapshot, Map<String, Map<String, Any?>>) -> List<MutableMap<String, Any?>>)?,
    ): RenderedPreview {
        val prepared = input.preview
        val snapshot = snapshot(prepared, input.rows)
        snapshotTransform?.invoke(snapshot)
        val seeds = cashImportPreviewDiscoverySeeds(snapshot)
        val proposals = if (seeds.isEmpty()) emptyList() else discover(snapshot, seeds, prepared.accounts)
        val rules = CashImportPreviewPlanRules<BigInteger, Instant>(
            CashImportRelationGroups::build, CashImportRelationDecisions::applyChoices,
            { value, groups -> CashImportRelationDecisions.restoreSplitValues(context, value, groups) },
            CashImportRelationDecisions::applyGroups,
            { files, value, groups, failures, accounts, include, parents ->
                CashImportPreviewPresentation.present(files, value, value.build(), groups, failures, accounts, include, parents)
            }, { values -> cashImportDigest(*values.toTypedArray()) }, ::wire)
        val result = rules.present(snapshot, proposals, files, prepared.accounts, prepared.candidateIds,
            prepared.retainedParentIds, input.candidateSets, seeds, choices(inputChoices, "relation_choices"), previous,
            includeUnchanged, currentRelationBuilder?.let { builder -> { builder(snapshot, prepared.accounts) } })
        return RenderedPreview(result, snapshot.operations, snapshot.rows)
    }

    private fun wire(value: Any?): Any? = when (value) {
        is CashAmount -> fixedCashDecimal(CashStoredDecimal(value.toString()))
        is CashDecimalLiteral -> fixedCashDecimal(CashStoredDecimal(value.text))
        is CashStoredDecimal -> fixedCashDecimal(value)
        is BigDecimal -> value.toPlainString()
        is Instant -> ParsedImportCodec.timeText(value)
        is Map<*, *> -> value.entries.associate { it.key.toString() to wire(it.value) }
        is Iterable<*> -> value.map(::wire)
        else -> value
    }
}
