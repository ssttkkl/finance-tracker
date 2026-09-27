package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

suspend fun FinanceApiClient.fetchLedgerOptions(): LedgerOptionsDto = request(HttpMethod.Get, "/api/v1/cash-ledger/options")

suspend fun FinanceApiClient.fetchCashCategories(): CashCategoryDirectoryDto = request(HttpMethod.Get, "/api/v1/cash-categories")

suspend fun FinanceApiClient.createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDto =
    request(HttpMethod.Post, "/api/v1/cash-categories", body = categoryWriteBody(name, parentId, description, expectedRevision))

suspend fun FinanceApiClient.updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryDto =
    request(HttpMethod.Patch, "/api/v1/cash-categories/${encodePathSegment(id)}", body = categoryWriteBody(name, parentId, description, expectedRevision))

suspend fun FinanceApiClient.reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategoryDto = request(
    HttpMethod.Post,
    "/api/v1/cash-categories/${encodePathSegment(id)}/reorder",
    body = buildJsonObject {
        put("direction", direction)
        put("expected_revision", expectedRevision)
    },
)

suspend fun FinanceApiClient.fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpactDto = request(
    HttpMethod.Get,
    "/api/v1/cash-categories/${encodePathSegment(id)}/deletion-impact",
)

suspend fun FinanceApiClient.deleteCashCategory(id: String, impact: CashCategoryDeleteImpactDto): CashCategoryDeleteResultDto = request(
    HttpMethod.Delete,
    "/api/v1/cash-categories/${encodePathSegment(id)}",
    body = buildJsonObject {
        put("expected_revision", impact.revision)
        put("expected_category_revision", impact.categoryRevision)
        put("expected_usage_count", impact.directUsageCount)
        put("confirmed", true)
    },
)
