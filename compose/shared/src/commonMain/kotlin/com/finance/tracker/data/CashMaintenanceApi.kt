package com.finance.tracker.data

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*

private const val MAINTENANCE = "/api/v1/cash-mutations"
private val maintenanceJson = Json(apiJson) { encodeDefaults = true; explicitNulls = false }

suspend fun FinanceApiClient.readCurrentCashRecord(transactionId: String): CashCurrentRecordDto =
    request(HttpMethod.Get, "/api/v1/cash-records/${encodePathSegment(transactionId)}")

suspend fun FinanceApiClient.saveCashDraft(transactionId: String, revision: Long, patch: JsonObject, key: String): CashDraftSaveDto {
    requireCashRevision(revision)
    return request(HttpMethod.Patch, "/api/v1/cash-records/${encodePathSegment(transactionId)}/draft",
        buildJsonObject { put("expected_revision", revision); put("patch", patch) }, cashKeyHeaders(key))
}

suspend fun FinanceApiClient.previewCashMaintenance(input: CashMaintenanceInput): CashMaintenancePlanDto {
    requireCashRevision(input.expectedRevision)
    if (input.intent !in setOf("edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations")) invalidCashInput()
    if (input.intent == "edit_entry" && (input.entryId == null || input.expectedEntryRevision == null)) invalidCashInput()
    input.expectedEntryRevision?.let(::requireCashRevision)
    val context = listOf(input.editContextId, input.previousPlanId, input.expectedPlanRevision)
    if (context.any { it != null } && context.any { it == null }) invalidCashInput()
    input.expectedPlanRevision?.let(::requireCashRevision)
    if (input.memberPatches.size > 2 || input.relationChoices.size > 100_000 || (input.issueResolutions?.size ?: 0) > 100) invalidCashInput()
    if (input.intent == "resolve_issue" && input.issueResolutions.isNullOrEmpty()) invalidCashInput()
    if (input.intent == "maintain_relations" && input.issueResolutions != null) invalidCashInput()
    input.issueResolutions.orEmpty().forEach {
        requireCashRevision(it.expectedIssueRevision)
        if (it.resolution !in setOf("apply_source", "keep_existing", "create_independent", "merge_into")) invalidCashInput()
        if (it.resolution == "merge_into") {
            if (it.targetTransactionId == null || it.expectedTargetRevision == null) invalidCashInput()
            requireCashRevision(it.expectedTargetRevision)
        } else if (it.targetTransactionId != null || it.expectedTargetRevision != null) invalidCashInput()
    }
    if (input.relationChoices.any { it.action !in setOf("accept", "defer", "ignore", "remove") }) invalidCashInput()
    return request(HttpMethod.Post, "$MAINTENANCE/plans", maintenanceJson.encodeToJsonElement(input))
}

suspend fun FinanceApiClient.readCashMaintenancePlan(planId: String): CashMaintenancePlanDto =
    request(HttpMethod.Get, maintenancePlanPath(planId))

suspend fun FinanceApiClient.readCurrentCashMaintenancePlan(contextId: String): CashMaintenancePlanDto =
    request(HttpMethod.Get, "$MAINTENANCE/edit-contexts/${encodePathSegment(contextId)}/plan")

suspend fun FinanceApiClient.readCashMaintenanceItems(planId: String, cursor: String? = null, limit: Int = 50, confirmationStatus: String = "all"): CashPlanItemPageDto {
    if (confirmationStatus !in setOf("all", "confirmed", "pending")) invalidCashInput()
    return request(HttpMethod.Get, appendQuery(maintenancePlanPath(planId) + "/items", cashPageQuery(cursor, limit) + ("confirmation_status" to confirmationStatus)))
}
suspend fun FinanceApiClient.readCashMaintenanceItem(planId: String, itemId: String): CashPlanItemDetailDto =
    request(HttpMethod.Get, maintenancePlanPath(planId) + "/items/${encodePathSegment(itemId)}")

suspend fun FinanceApiClient.commitCashMaintenance(planId: String, revision: Long, key: String): CashMutationReceiptDto {
    requireCashRevision(revision)
    return request(HttpMethod.Post, maintenancePlanPath(planId) + "/commit",
        buildJsonObject { put("expected_plan_revision", revision) }, cashKeyHeaders(key))
}
private fun maintenancePlanPath(id: String) = "$MAINTENANCE/plans/${encodePathSegment(id)}"
