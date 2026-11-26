package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashAssociationPageDto
import com.finance.tracker.business.contracts.CashCategoryDeleteImpactDto
import com.finance.tracker.business.contracts.CashCategoryDeleteResultDto
import com.finance.tracker.business.contracts.CashClassificationResultDto
import com.finance.tracker.business.contracts.CashMaintenanceInput
import com.finance.tracker.business.contracts.CashMaintenancePlanDto
import com.finance.tracker.business.contracts.CashMutationResolveDto
import com.finance.tracker.business.contracts.CashProjectionDeleteImpactDto
import com.finance.tracker.business.contracts.CashProjectionDeleteResultDto
import com.finance.tracker.business.contracts.CategoryDirectoryItemDto
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The caller presents the existing preview and submits its exact revision as a separate action. */
interface ClientCashWorkspaceTransport {
    suspend fun <T> request(
        expected: String?, method: String, path: String, body: JsonObject?,
        write: Boolean, mutates: Boolean, response: DeserializationStrategy<T>,
    ): T
}

class ClientCashMaintenanceOperations(
    private val transport: ClientCashWorkspaceTransport,
    private val encodeComponent: (String) -> String,
) {
    constructor(session: ClientSessionOperations) :
        this(session, session::component)

    private suspend inline fun <reified T> request(
        workspace: String?, method: String, path: String, body: JsonObject? = null,
        write: Boolean = false, mutates: Boolean = false,
    ): T = transport.request(workspace, method, path, body, write, mutates, serializer<T>())

    suspend fun preview(workspace: String?, input: CashMaintenanceInput): CashMaintenancePlanDto {
        validateMaintenanceInput(input)
        return request(workspace, "POST", "/api/v1/cash-mutations/plans", maintenancePreviewBody(input), write = true)
    }

    suspend fun plan(workspace: String?, id: String): CashMaintenancePlanDto =
        request(workspace, "GET", "/api/v1/cash-mutations/plans/${encodeComponent(id)}")

    suspend fun resolve(workspace: String?, planId: String, key: String): CashMutationResolveDto {
        if (!com.finance.tracker.business.domain.isCashWriteSetKey(key) || planId.isBlank()) invalidMaintenanceInput()
        val resolved = request<CashMutationResolveDto>(workspace, "POST", "/api/v1/cash-mutations/resolve", buildJsonObject {
            put("plan_id", planId)
            put("idempotency_key", key)
        })
        when (resolved.status) {
            "committed" -> if (resolved.receipt?.let { it.committed && it.operationKind == "mutation" && it.planId == planId } != true)
                throw ClientOperationFailure("protocol_error")
            "pending", "retry_original", "not_committed" -> if (resolved.receipt != null) throw ClientOperationFailure("protocol_error")
            else -> throw ClientOperationFailure("protocol_error")
        }
        return resolved
    }

    suspend fun associationTargets(workspace: String?, transactionId: String, search: CashAssociationQuery): CashAssociationPageDto {
        val path = "/api/v1/cash-records/${encodeComponent(transactionId)}/association-targets"
        val query = listOf("query" to search.query, "timezone_name" to search.timezone, "kind" to search.kind?.takeIf(String::isNotBlank), "amount" to search.amount,
            "account_id" to search.accountId, "date_from" to search.dateFrom, "date_to" to search.dateTo, "cursor" to search.cursor)
            .mapNotNull { (key, value) -> value?.takeIf(String::isNotEmpty)?.let { "$key=${encodeComponent(it)}" } }.joinToString("&")
        return request(workspace, "GET", "$path?$query")
    }

    suspend fun deletionImpact(workspace: String?, ids: List<String>, ledgerSeq: Long): CashProjectionDeleteImpactDto =
        request(workspace, "POST", "/api/v1/cash-projections/delete-impact", entrySelection(ids, ledgerSeq), write = true)

    suspend fun delete(workspace: String?, ids: List<String>, ledgerSeq: Long): CashProjectionDeleteResultDto =
        request(workspace, "DELETE", "/api/v1/cash-projections",
            JsonObject(entrySelection(ids, ledgerSeq) + ("confirmed" to JsonPrimitive(true))), write = true, mutates = true)

    suspend fun categorize(workspace: String?, ids: List<String>, ledgerSeq: Long, categoryId: String?): CashClassificationResultDto =
        request(workspace, "PUT", "/api/v1/cash-projections/categories",
            JsonObject(entrySelection(ids, ledgerSeq) + ("category_id" to (categoryId?.let(::JsonPrimitive) ?: JsonNull))),
            write = true, mutates = true)

    suspend fun createCategory(workspace: String?, category: EditCashCategory): CategoryDirectoryItemDto =
        request(workspace, "POST", "/api/v1/cash-categories", category.payload(), write = true, mutates = true)

    suspend fun updateCategory(workspace: String?, id: String, category: CashCategoryPatch): CategoryDirectoryItemDto =
        request(workspace, "PATCH", "/api/v1/cash-categories/${encodeComponent(id)}",
            category.payload(), write = true, mutates = true)

    suspend fun reorderCategory(workspace: String?, id: String, direction: String, revision: Long, target: String?): CategoryDirectoryItemDto =
        request(workspace, "POST", "/api/v1/cash-categories/${encodeComponent(id)}/reorder", buildJsonObject {
            put("direction", direction)
            put("expected_revision", revision)
            target?.let { put("target_id", it) }
        }, write = true, mutates = true)

    suspend fun categoryDeletionImpact(workspace: String?, id: String): CashCategoryDeleteImpactDto =
        request(workspace, "GET", "/api/v1/cash-categories/${encodeComponent(id)}/deletion-impact")

    suspend fun deleteCategory(
        workspace: String?, id: String, expectedDirectoryRevision: Long, expectedCategoryRevision: Long, expectedUsageCount: Int,
    ): CashCategoryDeleteResultDto =
        request(workspace, "DELETE", "/api/v1/cash-categories/${encodeComponent(id)}", buildJsonObject {
            put("expected_revision", expectedDirectoryRevision)
            put("expected_category_revision", expectedCategoryRevision)
            put("expected_usage_count", expectedUsageCount)
            put("confirmed", true)
        }, write = true, mutates = true)

    private fun entrySelection(ids: List<String>, seq: Long): JsonObject {
        if (ids.isEmpty() || ids.any(String::isBlank) || ids.distinct().size != ids.size || seq < 0)
            throw ClientOperationFailure("invalid_selection")
        return buildJsonObject {
            put("projection_ids", JsonArray(ids.map(::JsonPrimitive)))
            put("projection_version", seq)
        }
    }
}

data class CashAssociationQuery(
    val query: String = "", val timezone: String = "UTC", val kind: String? = null, val amount: String? = null,
    val accountId: String? = null, val dateFrom: String? = null, val dateTo: String? = null, val cursor: String? = null,
)

data class EditCashCategory(val name: String, val parentId: String?, val description: String, val revision: Long) {
    internal fun payload() = buildJsonObject {
        put("name", name)
        put("parent_id", parentId?.let(::JsonPrimitive) ?: JsonNull)
        put("description", description)
        put("expected_revision", revision)
    }
}

data class CashCategoryParentChange(val parentId: String?)

data class CashCategoryPatch(
    val revision: Long, val name: String? = null, val parent: CashCategoryParentChange? = null, val description: String? = null,
) {
    internal fun payload() = buildJsonObject {
        put("expected_revision", revision)
        name?.let { put("name", it) }
        parent?.let { put("parent_id", it.parentId?.let(::JsonPrimitive) ?: JsonNull) }
        description?.let { put("description", it) }
    }
}

private val maintenanceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

private fun invalidMaintenanceInput(): Nothing = throw ClientOperationFailure("import_input_invalid", 0)

private fun requireMaintenanceRevision(revision: Long) {
    if (revision <= 0 || revision == Long.MAX_VALUE) invalidMaintenanceInput()
}

private fun validateMaintenanceInput(input: CashMaintenanceInput) {
    requireMaintenanceRevision(input.expectedRevision)
    if (input.intent !in setOf("save_draft", "edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations")) invalidMaintenanceInput()
    if (input.intent == "save_draft" && (!input.issueResolutions.isNullOrEmpty() || input.relationChoices.isNotEmpty())) invalidMaintenanceInput()
    if (input.intent == "edit_entry" && (input.entryId == null || input.expectedEntryRevision == null)) invalidMaintenanceInput()
    input.expectedEntryRevision?.let(::requireMaintenanceRevision)
    val context = listOf(input.editContextId, input.previousPlanId, input.expectedPlanRevision)
    if (context.any { it != null } && context.any { it == null }) invalidMaintenanceInput()
    input.expectedPlanRevision?.let(::requireMaintenanceRevision)
    if (input.memberPatches.size > 2 || input.relationChoices.size > 100_000 || (input.issueResolutions?.size ?: 0) > 100) invalidMaintenanceInput()
    if (input.intent == "resolve_issue" && input.issueResolutions.isNullOrEmpty()) invalidMaintenanceInput()
    if (input.intent == "maintain_relations" && input.issueResolutions != null) invalidMaintenanceInput()
    input.issueResolutions.orEmpty().forEach {
        requireMaintenanceRevision(it.expectedIssueRevision)
        if (it.resolution !in setOf("apply_source", "keep_existing", "create_independent", "merge_into")) invalidMaintenanceInput()
        if (it.resolution == "merge_into") {
            val targetRevision = it.expectedTargetRevision ?: invalidMaintenanceInput()
            if (it.targetTransactionId == null) invalidMaintenanceInput()
            requireMaintenanceRevision(targetRevision)
        } else if (it.targetTransactionId != null || it.expectedTargetRevision != null) invalidMaintenanceInput()
    }
    if (input.relationChoices.any { it.action !in setOf("accept", "defer", "ignore", "remove") }) invalidMaintenanceInput()
}

private fun maintenancePreviewBody(input: CashMaintenanceInput): JsonObject {
    val encoded = maintenanceJson.encodeToJsonElement(CashMaintenanceInput.serializer(), input).jsonObject
    return if (input.relationAdditions.isEmpty()) JsonObject(encoded - "relation_additions") else encoded
}
