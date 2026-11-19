package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashCreationApiTest {
    @Test fun directCreationKeepsExactStringsAndDoesNotRequireMaintenanceReceipt() = runTest {
        val paths = mutableListOf<String>(); val bodies = mutableListOf<JsonObject>()
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine { request ->
            paths += request.url.encodedPath
            bodies += apiJson.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            respond("""{"ok":true,"rows":[],"data":{"amount":"12345678901234567890.0100"}}""", HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo = KtorCashCreationRepository(api)
        val input = StructuredObject(mapOf("amount" to StructuredText("12345678901234567890.0100"), "note" to StructuredText("")))
        repo.createRecord(input); repo.createTransfer(input)
        assertEquals(listOf("/api/v1/cash-records", "/api/v1/cashflow/transfer"), paths)
        assertEquals(listOf(JsonPrimitive("12345678901234567890.0100"), JsonPrimitive("12345678901234567890.0100")), bodies.map { it["amount"] })
        assertTrue(bodies.all { it["note"] == JsonPrimitive("") && "idempotency_key" !in it && "entry_id" !in it })
        api.close()
    }
    @Test fun aSuccessfulCreationRefreshesTheLocalSnapshotOnce() = runTest {
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine {
            respond("""{"ok":true,"rows":[],"data":{}}""", HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var refreshes = 0
        var captures = 0
        val repo = KtorCashCreationRepository(api) { captures++; { _ -> refreshes++ } }
        val input = StructuredObject(emptyMap())
        repo.createRecord(input); repo.createTransfer(input)
        assertEquals(2, refreshes)
        assertEquals(2, captures)
        api.close()
    }
    @Test fun rejectedDirectCommandPreservesServerFailureWithoutRetry() = runTest {
        var calls = 0
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine {
            calls++; respond("""{"error":{"code":"invalid_record","message":"invalid"}}""", HttpStatusCode.UnprocessableEntity,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var refreshes = 0
        val failure = assertFailsWith<DomainFailure> { KtorCashCreationRepository(api) { { _ -> refreshes++ } }.createRecord(StructuredObject(emptyMap())) }
        assertEquals("invalid_record", failure.code); assertEquals(422, failure.status); assertEquals(1, calls)
        assertEquals(0, refreshes)
        api.close()
    }

    @Test fun keyedSaveNeverCrossesAUserInTheSameWorkspace() = runTest {
        var requests = 0
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine {
            requests++
            respond("""{"receipt_id":"one","committed":true,"operation_kind":"cash_create_manual","created_at":"2026-11-19T00:00:00Z","ledger_seq":"1"}""",
                HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val bound = api.boundTo(ApiCredentialSnapshot("other-token", "https://api.example.test"), "workspace", "other@example.test")
            val operations = object : CashBusinessOperations {
                override suspend fun <T> run(write: Boolean, action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T =
                    action(bound) {}
            }
            val repo = KtorCashCreationRepository(api, operations)
            val input = StructuredObject(emptyMap())
            assertFailsWith<CancellationException> {
                repo.save(CashCreationMode.ORDINARY, input, "original", listOf("1"), "workspace", "owner@example.test")
            }
            assertEquals(0, requests)
            repo.save(CashCreationMode.ORDINARY, input, "new", listOf("1"), "workspace", "other@example.test")
            assertEquals(1, requests)
        } finally {
            api.close()
        }
    }
}
private class CreationTokenStore : TokenStore {
    override suspend fun get() = "isolated-token"
    override suspend fun set(value: String) {}
    override suspend fun clear() {}
}
