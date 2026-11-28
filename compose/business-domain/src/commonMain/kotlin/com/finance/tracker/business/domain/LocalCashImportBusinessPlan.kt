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
    requireActive, {}, now).plan

/** Build the complete plan and command from the same source preparation and exclusive preview. */
fun calculateLocalCashImportBusinessPlan(files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>? = null, recordIds: Map<String, String> = emptyMap(), requireActive: () -> Unit,
    progress: (CashImportPlanningStage) -> Unit = {},
    now: () -> Instant): Pair<Map<String, Any?>, CashWriteSetDto> {
    val calculated = calculateImportPlan(files, source, input, previous, recordIds, requireActive, progress, now)
    return calculated.plan to calculated.changes
}

class PreparedCashImportReview internal constructor(
    val plan: JsonObject,
    val changes: CashWriteSetDto,
    val canCommit: Boolean,
    val previousReviewFor: (JsonObject) -> LocalCashImportReview,
    val previewContext: JsonElement?,
    private val source: LedgerSnapshotDto,
    private val retainedReview: LocalCashImportReview,
    private val trustedWorkspacePlan: Boolean,
) {
    fun confirmedReview(): LocalCashImportReview = retainedReview.withConclusion(source, changes, plan, trustedWorkspacePlan)
}

/** Snapshot the choice history while the typed plan is still in scope. */
fun prepareLocalCashImportReview(
    files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>?, recordIds: Map<String, String>, includePreviewContext: Boolean,
    requireActive: () -> Unit, progress: (CashImportPlanningStage) -> Unit, now: () -> Instant,
    workspace: CashImportReviewWorkspace? = null,
): PreparedCashImportReview = prepareLocalCashImportReview(
    files, source, input, previous, recordIds, includePreviewContext, requireActive, progress, now, workspace, {},
)

fun prepareLocalCashImportReview(
    files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>?, recordIds: Map<String, String>, includePreviewContext: Boolean,
    requireActive: () -> Unit, progress: (CashImportPlanningStage) -> Unit, now: () -> Instant,
    workspace: CashImportReviewWorkspace?, sample: (String) -> Unit,
): PreparedCashImportReview {
    val (plan, frozen, changes) = calculateImportPlan(files, source, input, previous, recordIds, requireActive, progress, now, workspace, sample)
    return PreparedCashImportReview(frozen, changes, plan["can_commit"] == true, LocalCashImportReview.forPreview(plan),
        if (includePreviewContext) LocalCashImportReview.encodePreviewContext(plan) else null,
        source, LocalCashImportReview.freeze(input, plan), trustedWorkspacePlan = workspace != null)
}

private data class CalculatedImportPlan(val plan: Map<String, Any?>, val frozen: JsonObject, val changes: CashWriteSetDto)

private fun calculateImportPlan(
    files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>?, recordIds: Map<String, String>, requireActive: () -> Unit,
    progress: (CashImportPlanningStage) -> Unit, now: () -> Instant,
    workspace: CashImportReviewWorkspace? = null, sample: (String) -> Unit = {},
): CalculatedImportPlan {
    val (plan, sources) = localCashImportBusinessPlan(files, source, input, previous, recordIds, requireActive, progress, now)
    progress(CashImportPlanningStage.BUILD_WRITE_SET)
    val empty = JsonArray(emptyList())
    sample("planning_complete")
    val retained = if (workspace == null) plan else localCashImportChoiceContextFromPlan(plan) +
        mapOf("can_commit" to plan["can_commit"], "entry_targets" to plan["entry_targets"])
    val frozen = if (workspace == null) freezeLocalCashImportPreviewPlan(plan) else {
        freezeLocalCashImportPreviewPlan(plan, workspace) {
            @Suppress("UNCHECKED_CAST")
            (plan.remove("operations") as MutableList<MutableMap<String, Any?>>)
        }
    }
    sample("plan_frozen")
    val changes = buildLocalCashImportWriteSet(source, files, sources, frozen,
        (input["row_choices"] ?: empty).jsonArray, (input["relation_choices"] ?: empty).jsonArray, trustedFrozenPlan = true)
    sample("write_set")
    return CalculatedImportPlan(retained, frozen, changes)
}

private data class LocalPreparedImportPlan(val plan: MutableMap<String, Any?>, val sources: PreparedCashSourcePlan)

private fun localCashImportBusinessPlan(files: List<ParsedImportFile>, source: LedgerSnapshotDto, input: JsonObject,
    previous: Map<String, Any?>?, recordIds: Map<String, String>, requireActive: () -> Unit,
    progress: (CashImportPlanningStage) -> Unit, now: () -> Instant): LocalPreparedImportPlan {
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
    val operations = prepareOwnedLocalCashImportOperations(files, source, input["account_selections"] ?: empty,
        input["row_choices"] ?: empty, retainedIds)
    @Suppress("UNCHECKED_CAST") val entries = choices["entry_choices"] as List<Map<String, Any?>>
    val prepared = LocalCashImportPreviewPreparation(source, requireActive).prepareOwned(
        operations.operations, operations.source.rows.rows, entries, previous)
    val preview = buildOwnedLocalCashImportPreviewSnapshot(source, prepared.context, operations.operations, operations.rows, now)
    val seeds = cashImportPreviewDiscoverySeeds(preview)
    progress(CashImportPlanningStage.DISCOVER_RELATIONS)
    val proposals = if (seeds.isEmpty()) emptyList() else discoverLocalCashImportRelations(
        source, preview, seeds, prepared.accounts, requireActive)
    val plan = completeLocalCashImportPreviewPlan(source, prepared, preview, proposals, files, choices, previous, operations.candidateSets)
    requireActive()
    return LocalPreparedImportPlan(plan, operations.source)
}
