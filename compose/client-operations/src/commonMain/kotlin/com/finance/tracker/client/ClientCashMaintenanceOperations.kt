package com.finance.tracker.client

import com.finance.tracker.business.contracts.CashAssociationPageDto
import com.finance.tracker.business.contracts.CashCategoryDeleteImpactDto
import com.finance.tracker.business.contracts.CashCategoryDeleteResultDto
import com.finance.tracker.business.contracts.CashClassificationResultDto
import com.finance.tracker.business.contracts.CashMaintenanceInput
import com.finance.tracker.business.contracts.CashMaintenancePlanDto
import com.finance.tracker.business.contracts.CashProjectionDeleteImpactDto
import com.finance.tracker.business.contracts.CashProjectionDeleteResultDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.CategoryDirectoryItemDto
import com.finance.tracker.business.domain.buildLocalCashCategoryDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The caller presents the existing preview and submits its exact revision as a separate action. */
class ClientCashMaintenanceOperations(private val session: ClientSessionOperations) {
    suspend fun preview(workspace: String?, input: CashMaintenanceInput): CashMaintenancePlanDto =
        session.cashWorkspaceRequest(workspace, "POST", "/api/v1/cash-mutations/plans", maintenancePreviewBody(input), write = true)

    suspend fun plan(workspace: String?, id: String): CashMaintenancePlanDto =
        session.cashWorkspaceRequest(workspace, "GET", "/api/v1/cash-mutations/plans/${session.component(id)}")

    suspend fun associationTargets(workspace: String?, transactionId: String, search: CashAssociationQuery): CashAssociationPageDto {
        val path = "/api/v1/cash-records/${session.component(transactionId)}/association-targets"
        val query = listOf("query" to search.query, "timezone_name" to search.timezone, "kind" to search.kind, "amount" to search.amount,
            "account_id" to search.accountId, "date_from" to search.dateFrom, "date_to" to search.dateTo, "cursor" to search.cursor)
            .mapNotNull { (key, value) -> value?.let { "$key=${session.component(it)}" } }.joinToString("&")
        return session.cashWorkspaceRequest(workspace, "GET", "$path?$query")
    }

    suspend fun deletionImpact(workspace: String?, ids: List<String>, ledgerSeq: Long): CashProjectionDeleteImpactDto =
        session.cashWorkspaceRequest(workspace, "POST", "/api/v1/cash-projections/delete-impact", entrySelection(ids, ledgerSeq), write = true)

    suspend fun delete(workspace: String?, ids: List<String>, ledgerSeq: Long): CashProjectionDeleteResultDto =
        session.cashWorkspaceRequest(workspace, "DELETE", "/api/v1/cash-projections",
            JsonObject(entrySelection(ids, ledgerSeq) + ("confirmed" to JsonPrimitive(true))), write = true, mutates = true)

    suspend fun categorize(workspace: String?, ids: List<String>, ledgerSeq: Long, categoryId: String?): CashClassificationResultDto =
        session.cashWorkspaceRequest(workspace, "PUT", "/api/v1/cash-projections/categories",
            JsonObject(entrySelection(ids, ledgerSeq) + ("category_id" to (categoryId?.let(::JsonPrimitive) ?: JsonNull))),
            write = true, mutates = true)

    suspend fun categories(workspace: String?): CategoryDirectoryDto {
        val snapshot = session.ledger(workspace).ledger.snapshot
        return buildLocalCashCategoryDirectory(snapshot.facts.cashCategories, snapshot.facts.categoryDirectoryRevision)
    }

    suspend fun createCategory(workspace: String?, category: EditCashCategory): CategoryDirectoryItemDto =
        session.cashWorkspaceRequest(workspace, "POST", "/api/v1/cash-categories", category.payload(), write = true, mutates = true)

    suspend fun updateCategory(workspace: String?, id: String, category: CashCategoryPatch): CategoryDirectoryItemDto =
        session.cashWorkspaceRequest(workspace, "PATCH", "/api/v1/cash-categories/${session.component(id)}",
            category.payload(), write = true, mutates = true)

    suspend fun reorderCategory(workspace: String?, id: String, direction: String, revision: Long, target: String?): CategoryDirectoryItemDto =
        session.cashWorkspaceRequest(workspace, "POST", "/api/v1/cash-categories/${session.component(id)}/reorder", buildJsonObject {
            put("direction", direction)
            put("expected_revision", revision)
            target?.let { put("target_id", it) }
        }, write = true, mutates = true)

    suspend fun categoryDeletionImpact(workspace: String?, id: String): CashCategoryDeleteImpactDto =
        session.cashWorkspaceRequest(workspace, "GET", "/api/v1/cash-categories/${session.component(id)}/deletion-impact")

    suspend fun deleteCategory(
        workspace: String?, id: String, expectedDirectoryRevision: Long, expectedCategoryRevision: Long, expectedUsageCount: Int,
    ): CashCategoryDeleteResultDto =
        session.cashWorkspaceRequest(workspace, "DELETE", "/api/v1/cash-categories/${session.component(id)}", buildJsonObject {
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

fun maintenancePreviewBody(input: CashMaintenanceInput): JsonObject {
    val encoded = maintenanceJson.encodeToJsonElement(CashMaintenanceInput.serializer(), input).jsonObject
    return if (input.relationAdditions.isEmpty()) JsonObject(encoded - "relation_additions") else encoded
}
