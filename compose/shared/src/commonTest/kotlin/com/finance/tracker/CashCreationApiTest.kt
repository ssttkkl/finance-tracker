package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
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
    @Test fun rejectedDirectCommandPreservesServerFailureWithoutRetry() = runTest {
        var calls = 0
        val api = FinanceApiClient({ "https://api.example.test" }, CreationTokenStore(), MockEngine {
            calls++; respond("""{"error":{"code":"invalid_record","message":"invalid"}}""", HttpStatusCode.UnprocessableEntity,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val failure = assertFailsWith<DomainFailure> { KtorCashCreationRepository(api).createRecord(StructuredObject(emptyMap())) }
        assertEquals("invalid_record", failure.code); assertEquals(422, failure.status); assertEquals(1, calls)
        api.close()
    }
}
private class CreationTokenStore : TokenStore {
    override suspend fun get() = "isolated-token"
    override suspend fun set(value: String) {}
    override suspend fun clear() {}
}
