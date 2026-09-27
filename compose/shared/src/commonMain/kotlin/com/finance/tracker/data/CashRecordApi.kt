package com.finance.tracker.data

import com.finance.tracker.core.*
import com.finance.tracker.domain.*
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

suspend fun FinanceApiClient.fetchCashRecord(id: String): CashRecordDetailDto = request(
    HttpMethod.Get,
    "/api/v1/cash-records/${encodePathSegment(id)}",
)

suspend fun FinanceApiClient.fetchCashRecords(
    query: String? = null,
    excludeId: String? = null,
    dateFrom: String? = null,
    dateTo: String? = null,
    cursor: String? = null,
    limit: Int = 20,
): CashRecordPageDto = request(
    HttpMethod.Get,
    appendQuery("/api/v1/cash-records", listOf(
        "query" to query,
        "exclude_id" to excludeId,
        "date_from" to dateFrom,
        "date_to" to dateTo,
        "timezone" to getLocalTimeZoneId(),
        "cursor" to cursor,
        "limit" to limit.toString(),
    )),
)

suspend fun FinanceApiClient.createCashRecord(body: JsonObject): CashRecordDetailDto = request(HttpMethod.Post, "/api/v1/cash-records", body)

suspend fun FinanceApiClient.updateCashRecord(id: String, body: JsonObject): CashRecordDetailDto = request(
    HttpMethod.Put,
    "/api/v1/cash-records/${encodePathSegment(id)}",
    body,
)

suspend fun FinanceApiClient.deleteCashRecord(id: String, mode: String): CashRecordDeleteResultDto = request(
    HttpMethod.Delete,
    "/api/v1/cash-records/${encodePathSegment(id)}",
    body = buildJsonObject { put("mode", mode) },
)

suspend fun FinanceApiClient.createCashRelation(primaryFactId: String, secondaryFactId: String, kind: String): CashRecordDetailDto = request(
    HttpMethod.Post,
    "/api/v1/cash-relations",
    body = buildJsonObject {
        put("primary_fact_id", primaryFactId)
        put("secondary_fact_id", secondaryFactId)
        put("kind", kind)
        put("status", "accepted")
    },
)

suspend fun FinanceApiClient.updateCashRelation(id: String, kind: String): CashRecordDetailDto = request(
    HttpMethod.Put,
    "/api/v1/cash-relations/${encodePathSegment(id)}",
    body = buildJsonObject { put("kind", kind) },
)

suspend fun FinanceApiClient.cancelCashRelation(id: String): OkResultDto = request(
    HttpMethod.Delete,
    "/api/v1/cash-relations/${encodePathSegment(id)}",
    body = buildJsonObject {},
)

suspend fun FinanceApiClient.dissolveCashRelations(factId: String): CashRecordDetailDto = request(
    HttpMethod.Post,
    "/api/v1/cash-relations/dissolve",
    body = buildJsonObject { put("fact_id", factId) },
)
