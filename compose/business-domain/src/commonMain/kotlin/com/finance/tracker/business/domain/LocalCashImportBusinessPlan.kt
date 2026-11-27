package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.contracts.CashWriteSetDto
import kotlinx.serialization.json.*
import kotlin.time.Instant

enum class CashImportPlanningStage { PREPARE_SOURCES, DISCOVER_RELATIONS, BUILD_WRITE_SET }

/** Compute the complete file-import plan locally; preserve calculated types until the final plan encoding boundary. */
fun buildLocalCashImportBusinessPlan(files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>? = null, recordIds: Map<String, String> = emptyMap(), requireActive: () -> Unit,
    now: () -> Instant): MutableMap<String, Any?> = localCashImportBusinessPlan(files, source, input, previous, recordIds,
    requireActive, {}, now) { plan, _ -> plan }

/** Build the complete plan and command from the same source preparation and exclusive preview. */
fun calculateLocalCashImportBusinessPlan(files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>? = null, recordIds: Map<String, String> = emptyMap(), requireActive: () -> Unit,
    progress: (CashImportPlanningStage) -> Unit = {},
    now: () -> Instant): Pair<Map<String, Any?>, CashWriteSetDto> = localCashImportBusinessPlan(files, source, input, previous,
    recordIds, requireActive, progress, now) { plan, sources ->
        progress(CashImportPlanningStage.BUILD_WRITE_SET)
        val empty = JsonArray(emptyList())
        plan to buildLocalCashImportWriteSet(source, files, sources, freezeLocalCashImportPreviewPlan(plan),
            (input["row_choices"] ?: empty).jsonArray, (input["relation_choices"] ?: empty).jsonArray)
    }

private fun <R> localCashImportBusinessPlan(files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>?, recordIds: Map<String, String>, requireActive: () -> Unit,
    progress: (CashImportPlanningStage) -> Unit, now: () -> Instant,
    complete: (MutableMap<String, Any?>, PreparedCashSourcePlan) -> R): R {
    requireActive()
    progress(CashImportPlanningStage.PREPARE_SOURCES)
    val empty = JsonArray(emptyList())
    val choices = prepareLocalCashImportInput(input["account_selections"] ?: empty, input["row_choices"] ?: empty,
        input["relation_choices"] ?: empty, input["entry_choices"])
    val retainedIds = linkedMapOf<String, String>()
    for (raw in (previous?.get("operations") as? List<*>).orEmpty()) {
        val operation = raw as Map<*, *>
        val occurrence = operation["occurrence_key"] as? String
        if (!occurrence.isNullOrEmpty() && operation["transaction_id"] == null) {
            val values = operation["values"] as Map<*, *>
            retainedIds[occurrence] = values["record_id"] as String
        }
    }
    retainedIds.putAll(recordIds)
    val operations = prepareLocalCashImportOperations(files, source, input["account_selections"] ?: empty,
        input["row_choices"] ?: empty, retainedIds, wireValues = false)
    @Suppress("UNCHECKED_CAST") val entries = choices["entry_choices"] as List<Map<String, Any?>>
    val prepared = LocalCashImportPreviewPreparation(source, requireActive).prepare(
        operations.operations, operations.source.rows.rows, entries, previous)
    val preview = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows, now)
    val seeds = cashImportPreviewDiscoverySeeds(preview)
    progress(CashImportPlanningStage.DISCOVER_RELATIONS)
    val proposals = if (seeds.isEmpty()) emptyList() else discoverLocalCashImportRelations(
        source, preview, seeds, prepared.accounts, requireActive)
    val plan = completeLocalCashImportPreviewPlan(source, prepared, preview, proposals, files, choices, previous, operations.candidateSets)
    requireActive()
    return complete(plan, operations.source)
}
