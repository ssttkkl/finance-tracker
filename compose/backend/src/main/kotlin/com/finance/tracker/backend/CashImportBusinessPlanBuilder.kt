package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_CATEGORIES
import java.math.BigDecimal
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
        val rendered = present(files, prepare(files, input, previous, sourcePlan), input, previous)
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
        val entryChoices = choices(input, "entry_choices")
        val prepared = CashImportPreviewPreparation(context).prepare(operations.operations, operations.source.rows.rows,
            entryChoices, previous)
        return PreparedPreview(prepared, operations.rows, operations.candidateSets)
    }

    private fun present(files: List<ParsedImportFile>, input: PreparedPreview, inputChoices: Map<String, Any?>,
        previous: Map<String, Any?>?): RenderedPreview {
        val prepared = input.preview
        val snapshot = snapshot(prepared, input.rows)
        val seeds = snapshot.components.values.filter {
            snapshot.parentOps[it.transactionId]?.get("planned_action") in setOf("create_posted", "finalize_draft")
        }.map { it.componentId.toString() }.toSet()
        val proposals = CashImportRelationDiscovery(context).discover(snapshot, seeds, prepared.accounts)
        val grouped = CashImportRelationGroups.build(snapshot, proposals)
        val groups = grouped.groups
        val selected = CashImportRelationDecisions.applyChoices(groups, choices(inputChoices, "relation_choices"), previous)
        CashImportRelationDecisions.restoreSplitValues(context, snapshot, groups)
        val failures = linkedMapOf<String, Map<String, Any?>>()
        for (failure in grouped.failures + CashImportRelationDecisions.applyGroups(snapshot, groups)) {
            val parents = (failure["parent_ids"] as List<*>).map { it.toString() }.distinct().sortedWith(cashRelationStringOrder)
            val value = failure + ("parent_ids" to parents)
            failures.putIfAbsent(cashImportDigest(failure["code"], parents, failure["suggestion_id"]), value)
        }
        for (failure in failures.values) for (parent in failure["parent_ids"] as List<*>) {
            snapshot.facts[parent]?.let { snapshot.facts[parent.toString()] = it.withAttention(true) }
        }
        val result = CashImportPreviewPresentation.present(files, snapshot, snapshot.build(), groups, failures.values.toList(),
            prepared.accounts, entryParentIds = prepared.retainedParentIds)
        result.putAll(linkedMapOf("invalidated_choices" to selected.invalidated,
            "invalidated_relation_choices" to selected.sources, "relation_choice_generations" to selected.generations,
            "source_candidate_sets" to input.candidateSets, "relation_groups" to wire(groups),
            "relation_failures" to failures.values.toList(), "dependencies" to linkedMapOf(
                "seed_ids" to snapshot.context.seedIds.sorted(), "token" to snapshot.context.token(),
                "candidate_ids" to prepared.candidateIds.sorted(), "discovery_seeds" to seeds.sortedWith(cashRelationStringOrder)),
            "type_effects" to mapOf("removed_relation_ids" to snapshot.removedRelations.sorted(),
                "removed_funding_ids" to snapshot.removedFunding.sorted())))
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
