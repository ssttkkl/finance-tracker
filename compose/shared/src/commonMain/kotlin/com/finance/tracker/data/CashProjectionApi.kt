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

suspend fun FinanceApiClient.fetchCashPage(filters: CashFiltersDto, cursor: String? = null): CashPageDto = request(
    HttpMethod.Get,
    appendQuery("/api/v1/cash-projections", listOf(
        "date_from" to filters.dateFrom,
        "date_to" to filters.dateTo,
        "account_id" to filters.accountId,
        "counterparty" to filters.counterparty,
        "category_id" to filters.categoryId,
        "uncategorized" to filters.uncategorized,
        "currency" to filters.currency,
        "amount_min" to filters.amountMin,
        "amount_max" to filters.amountMax,
        "economic_type" to filters.economicType,
        "transfer_subtype" to filters.transferSubtype,
        "composition" to filters.composition,
        "timezone" to getLocalTimeZoneId(),
        "cursor" to cursor,
    )),
)

suspend fun FinanceApiClient.fetchCashAccounts(): List<AccountDto> =
    request<AccountListDto>(HttpMethod.Get, "/api/v1/accounts?view=cash").items

suspend fun FinanceApiClient.fetchEvidence(id: String): EvidenceDto = request(
    HttpMethod.Get,
    "/api/v1/evidence/cash-projections/${encodePathSegment(id)}",
)

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
