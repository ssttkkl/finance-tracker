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
            val input = StructuredObject(mapOf(
                "account_name" to StructuredText("Original account"), "amount" to StructuredText("-1.20"),
                "currency" to StructuredText("CNY"), "occurred_at" to StructuredText("2026-11-19T12:00:00Z"),
                "record_type" to StructuredText("consumption"),
            ))
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

    @Test fun theJournalReleasesOnlyACertainOriginalRejection() = runTest {
        var stored: CashCreationRecovery? = null
        val journal = object : CashCreationJournal {
            override suspend fun read() = stored
            override suspend fun swap(previous: CashCreationRecovery?, next: CashCreationRecovery?): Boolean {
                if (stored != previous) return false
                stored = next
                return true
            }
        }
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine { request ->
            assertNotNull(stored)
            requests += request.method to request.url.encodedPath
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.headers["Idempotency-Key"] == "invalid-record") {
                respond("""{"error":{"code":"invalid_record"}}""", HttpStatusCode.UnprocessableEntity, json)
            } else {
                respond("""{"error":{"code":"workspace_forbidden"}}""", HttpStatusCode.Forbidden, json)
            }
        })
        try {
            val bound = api.boundTo(ApiCredentialSnapshot("owner-token", "https://api.example.test"), "workspace", "owner@example.test")
            val operations = object : CashBusinessOperations {
                override suspend fun <T> run(write: Boolean, action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T =
                    action(bound) {}
            }
            val repo = KtorCashCreationRepository(api, operations) { journal }
            val draft = CashCreationDraft(CashOrdinaryInput(emptyMap()), CashTransferInput())
            val frozen = com.finance.tracker.client.freezeCashRecord(
                com.finance.tracker.client.NewCashRecord("1", "-1.20", "CNY", "2026-11-19T12:00:00Z", "consumption"),
                "Original", "invalid-record")
            val failure = assertFailsWith<DomainFailure> {
                repo.saveRecovery(CashCreationRecovery(frozen, draft), "workspace", "owner@example.test", lookupFirst = false)
            }
            assertEquals("not_committed", failure.commitState)
            assertNull(stored)
            val denied = CashCreationRecovery(frozen.copy(id = "permission-changed"), draft)
            assertEquals(403, assertFailsWith<DomainFailure> {
                repo.saveRecovery(denied, "workspace", "owner@example.test", lookupFirst = false)
            }.status)
            assertEquals(denied, stored)
            assertEquals(403, assertFailsWith<DomainFailure> {
                repo.saveRecovery(denied, "workspace", "owner@example.test", lookupFirst = true)
            }.status)
            assertEquals(denied, stored)
            assertEquals(listOf(HttpMethod.Post, HttpMethod.Post, HttpMethod.Get), requests.map { it.first })
        } finally { api.close() }
    }
}
private class CreationTokenStore : TokenStore {
    override suspend fun get() = "isolated-token"
    override suspend fun set(value: String) {}
    override suspend fun clear() {}
}
