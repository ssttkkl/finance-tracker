package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.ACCOUNTS

internal data class PreparedCashImportPreview(
    val operations: List<Map<String, Any?>>,
    val accounts: Map<String, Map<String, Any?>>,
    val candidateIds: Set<Long>,
    val retainedParentIds: Set<String>,
    val context: CashRelationSqlContext,
)

/** 操作规划之后的只读准备；完整关系建议与预计收支仍由后续构建器生成。 */
internal class CashImportPreviewPreparation(private val context: CashSqlContext) {
    fun prepare(
        operations: List<Map<String, Any?>>,
        sourceRows: Map<String, SourceOccurrence>,
        entryChoices: List<Map<String, Any?>> = emptyList(),
        previousPlan: Map<String, Any?>? = null,
        additionalSeedIds: Set<Long> = emptySet(),
    ): PreparedCashImportPreview {
        context.requireActive()
        val prepared = operations.map { operation ->
            val ref = (operation["row_refs"] as List<*>).first() as String
            val source = sourceRows.getValue(ref)
            @Suppress("UNCHECKED_CAST") val values = operation["values"] as Map<String, Any?>
            operation.toMutableMap().apply {
                this["source_scope_key"] = source.accountScopeKey ?: "file:${source.fileScopeKey}"
                this["matching_record_id"] = if (source.providerId != null) values["record_id"] else
                    "occurrence:${source.occurrenceKey}"
            }
        }
        @Suppress("UNCHECKED_CAST") val targets = previousPlan?.get("entry_targets") as? Map<String, Any?> ?: emptyMap()
        val retained = linkedSetOf<String>()
        for (choice in entryChoices) {
            val target = targets[choice["edit_ref"]] as? Map<*, *> ?: throw CashImportPlanError("plan_changed")
            val parents = target["parent_ids"] as? List<*> ?: throw CashImportPlanError("plan_changed")
            parents.forEach { retained += it.toString() }
        }
        val accounts = linkedMapOf<String, Map<String, Any?>>()
        val table = ACCOUNTS
        context.sql.select(table.ID, table.NAME, table.TYPE, table.REVISION).from(table)
            .where(table.WORKSPACE_ID.eq(context.workspace)).fetch().forEach { row ->
                accounts[row[table.ID]!!.toString()] = linkedMapOf(
                    "name" to row[table.NAME], "type" to row[table.TYPE], "revision" to row[table.REVISION])
            }
        val new = prepared.filter { it["planned_action"] in setOf("create_posted", "finalize_draft") }
        val candidates = CashImportCandidateRepository(context).candidateIds(new, sourceRows)
        val existing = prepared.mapNotNull { it["transaction_id"]?.toString()?.takeIf(String::isNotEmpty)?.toLong() }.toSet()
        val relationContext = CashRelationContextService(context).load(existing + candidates + additionalSeedIds)
        return PreparedCashImportPreview(prepared, accounts, candidates, retained, relationContext)
    }
}
