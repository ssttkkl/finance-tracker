@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.finance.tracker.core

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.data.loadCashComputeWorkerClient
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.serialization.json.*
import kotlin.js.JsAny

internal actual val cashLedgerUsesBackgroundQueries: Boolean = true
internal actual fun createBackgroundCashLedger(workspace: String): BackgroundCashLedger? = BrowserCashLedger(workspace)

private class BrowserCashLedger(private val workspace: String) : BackgroundCashLedger {
    private var client: CashLedgerWorkerClient? = null
    private var loadedSnapshot: String? = null

    override suspend fun page(snapshot: String, filters: CashFiltersDto, timeZoneId: String?,
        cursor: String?, startIndex: Int?, expectedLedgerSeq: Long?, limit: Int): CashPageDto {
        val bounds = localCashLedgerDateBounds(filters.dateFrom, filters.dateTo, timeZoneId)
        val query = buildJsonObject {
            put("filters", Json.encodeToJsonElement(filters))
            put("zone", bounds.timeZoneId)
            bounds.dateFrom?.let { put("dateFrom", it) }
            bounds.dateTo?.let { put("dateTo", it) }
            bounds.start?.let { put("from", it.toString()) }
            bounds.end?.let { put("until", it.toString()) }
            cursor?.let { put("cursor", it) }
            startIndex?.let { put("start", it) }
            expectedLedgerSeq?.let { put("expectedSeq", it.toString()) }
            put("limit", limit)
        }
        return Json.decodeFromJsonElement(read(snapshot, query))
    }

    override suspend fun read(snapshot: String, query: JsonObject): JsonElement {
        val payload = JsonObject(query + ("workspace" to JsonPrimitive(workspace))).toString()
        val raw = try {
            val connection = client ?: loadCashComputeWorkerClient().await().createCashLedgerClient().also { client = it }
            connection.read(payload, snapshot.takeUnless { it == loadedSnapshot }).awaitResult().payload
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        } catch (failure: Exception) {
            close()
            throw DomainFailure(CASH_LEDGER_WORKER_FAILED, 0, FailureCategory.RECOVERABLE, cause = failure)
        }
        val response = Json.parseToJsonElement(raw)
        (response as? JsonObject)?.get("code")?.let {
            val code = it.jsonPrimitive.content
            if (code == "internal" && (response as JsonObject)["phase"]?.jsonPrimitive?.content == "ledger_query") {
                val kind = response["kind"]?.jsonPrimitive?.content
                val detail = kind.takeIf { it in setOf("range", "type", "runtime") } ?: "runtime"
                throw DomainFailure("cash_ledger_worker_internal", 0, FailureCategory.UNKNOWN,
                    cause = IllegalStateException("ledger_query_failed:$detail"))
            }
            throw CashProjectionDomainFailure(code)
        }
        loadedSnapshot = snapshot
        return response
    }

    override fun close() {
        client?.close()
        client = null
        loadedSnapshot = null
    }
}

internal external interface CashLedgerWorkerClient : JsAny {
    fun read(query: String, snapshot: String?): ComputeWorkerRequest<CashLedgerWorkerResponse>
    fun close()
}

internal external interface CashLedgerWorkerResponse : JsAny {
    val payload: String
}
