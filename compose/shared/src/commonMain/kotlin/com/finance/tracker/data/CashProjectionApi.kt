package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

suspend fun FinanceApiClient.fetchCashAccounts(): List<AccountDto> =
    request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

suspend fun FinanceApiClient.classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResultDto = request(
    HttpMethod.Put,
    "/api/v1/cash-projections/categories",
    body = buildJsonObject {
        put("projection_ids", apiJson.encodeToJsonElement(ids))
        put("projection_version", projectionVersion)
        put("category_id", categoryId?.let(::JsonPrimitive) ?: JsonNull)
    },
)

suspend fun FinanceApiClient.fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpactDto = request(
    HttpMethod.Post,
    "/api/v1/cash-projections/delete-impact",
    body = buildJsonObject {
        put("projection_ids", apiJson.encodeToJsonElement(ids))
        put("projection_version", projectionVersion)
    },
)

suspend fun FinanceApiClient.deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResultDto = request(
    HttpMethod.Delete,
    "/api/v1/cash-projections",
    body = buildJsonObject {
        put("projection_ids", apiJson.encodeToJsonElement(ids))
        put("projection_version", projectionVersion)
        put("confirmed", true)
    },
)
