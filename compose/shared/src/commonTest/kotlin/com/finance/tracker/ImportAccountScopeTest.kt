package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.test.*

class ImportAccountScopeTest {
    private class Store : TokenStore {
        var reads = 0; var clears = 0
        override suspend fun get(): String { reads++; return "later-token" }
        override suspend fun set(value: String) = Unit
        override suspend fun clear() { clears++ }
    }
    private val scope = ImportAccountScope("workspace/+?&", ApiCredentialSnapshot("original-token", "https://original.example.test"))
    private val headers = headersOf(HttpHeaders.ContentType, "application/json")
    private val account = """{"account_id":"9007199254740993","name":"Original","type":"cash","active":false,"currencies":["CNY"],"account_revision":2,"deleted":false,"capabilities":{"can_update":true,"can_delete":true},"receipt_id":"receipt","committed":true}"""
    private val receipt = """{"receipt_id":"receipt","operation_kind":"account_update","committed":true,"created_at":"2026-11-16T00:00:00Z","account":$account}"""
    @Test fun listUsesTheOriginalOriginTokenAndExactlyOneEncodedWorkspace() = runTest {
        val store = Store()
        val client = FinanceApiClient({ "https://later.example.test" }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            assertEquals(listOf(scope.workspaceId), request.url.parameters.getAll("workspace_id"))
            assertEquals("cash", request.url.parameters["view"])
            respond("""{"items":[]}""", headers = headers)
        })
        try {
            assertEquals(emptyList(), ImportAccountApi(client, scope).list())
            assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { client.close() }
    }
    @Test fun everyAccountOperationAndGenericReceiptUsesTheSameOriginalScope() = runTest {
        val store = Store(); val seen = mutableListOf<Pair<HttpMethod, String>>()
        val client = FinanceApiClient({ "invalid later origin" }, store, MockEngine { request ->
            assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            assertEquals(listOf(scope.workspaceId), request.url.parameters.getAll("workspace_id"))
            seen += request.method to request.url.encodedPath
            when {
                request.url.encodedPath.contains("/receipts/") -> {
                    if (request.url.encodedPath.endsWith("by-key")) assertEquals("original-key", request.url.parameters["key"])
                    respond(receipt, headers = headers)
                }
                request.url.encodedPath.endsWith("currency-requirements") -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val item = body.getValue("items").jsonArray.single().jsonObject
                    assertEquals(JsonPrimitive("original-key"), item["idempotency_key"])
                    assertEquals(JsonPrimitive(2), item["expected_revision"])
                    respond("""{"results":[{"account_id":"9007199254740993","status":"updated","account":$account}]}""", headers = headers)
                }
                else -> {
                    if (request.method != HttpMethod.Get) {
                        assertEquals("original-key", request.headers["Idempotency-Key"])
                        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        if (request.method in listOf(HttpMethod.Patch, HttpMethod.Delete)) assertEquals(JsonPrimitive(2), body["expected_revision"])
                    }
                    respond(account, headers = headers)
                }
            }
        })
        try {
            val api = ImportAccountApi(client, scope)
            assertTrue(api.read("account/+?&").capabilities.getValue("can_delete"))
            api.create("Original", "cash", listOf("CNY"), "original-key")
            api.update("account/+?&", 2, buildJsonObject { put("active", false) }, "original-key")
            api.delete("account/+?&", 2, "original-key")
            api.ensureCurrencies(listOf(ImportCurrencyRequirementDto("original-key", "9007199254740993", 2, listOf("CNY"))))
            assertEquals("account_update", api.receiptByKey("original-key").operationKind)
            assertEquals("receipt", api.receipt("receipt").receiptId)
            val accountPath = "/api/v1/accounts/by-id/account%2F%2B%3F%26"
            assertEquals(listOf(HttpMethod.Get to accountPath, HttpMethod.Post to "/api/v1/accounts", HttpMethod.Patch to accountPath,
                HttpMethod.Delete to accountPath, HttpMethod.Post to "/api/v1/cash-import/accounts/currency-requirements",
                HttpMethod.Get to "/api/v1/cash-mutations/receipts/by-key", HttpMethod.Get to "/api/v1/cash-mutations/receipts/receipt"), seen)
            assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { client.close() }
    }
    @Test fun patchAndCurrencyContainersAreFrozenBeforeTheFirstSuspension() = runTest {
        for (operation in listOf("create", "update", "currencies")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val currencies = mutableListOf("CNY")
            val patch = mutableMapOf<String, JsonElement>("name" to JsonPrimitive("Original"), "currencies" to JsonArray(currencies.map(::JsonPrimitive)))
            val requirements = mutableListOf(ImportCurrencyRequirementDto("original", "1", 2, currencies))
            val client = FinanceApiClient({ "unused" }, Store(), MockEngine { request ->
                entered.complete(Unit); release.await()
                val body = (request.body as TextContent).text
                assertFalse("Later" in body, body); assertFalse("USD" in body, body)
                if (operation == "currencies") respond("""{"results":[]}""", headers = headers) else respond(account, headers = headers)
            })
            try {
                val api = ImportAccountApi(client, scope)
                val pending = async { when (operation) {
                    "create" -> api.create("Original", "cash", currencies, "original")
                    "update" -> api.update("1", 2, JsonObject(patch), "original")
                    else -> api.ensureCurrencies(requirements)
                } }
                entered.await(); currencies += "USD"; patch["name"] = JsonPrimitive("Later"); requirements.clear(); release.complete(Unit)
                pending.await()
            } finally { client.close() }
        }
    }
    @Test fun rejectedOriginalIdentityNeverReadsOrClearsTheLaterToken() = runTest {
        for (status in listOf(401, 403)) {
            val store = Store()
            val client = FinanceApiClient({ "unused" }, store, MockEngine { respond("{}", HttpStatusCode.fromValue(status), headers) })
            try {
                val failure = assertFailsWith<ApiFailure> { ImportAccountApi(client, scope).list() }
                assertEquals(status, failure.status)
                if (status == 401) assertEquals("original-token", failure.rejectedToken)
                assertEquals(0, store.reads); assertEquals(0, store.clears)
            } finally { client.close() }
        }
    }
    @Test fun brokenRejectionBodyKeepsItsRealStatusAndOriginalDiagnostic() = runTest {
        val store = Store(); val lost = IOException("original diagnostic body loss")
        val client = FinanceApiClient({ "unused" }, store, MockEngine {
            respond(ByteChannel().apply { cancel(lost) }, HttpStatusCode.Unauthorized, headers)
        })
        try {
            val failure = assertFailsWith<ApiFailure> { ImportAccountApi(client, scope).create("Original", "cash", listOf("CNY"), "key") }
            assertEquals(401, failure.status)
            assertEquals("original-token", failure.rejectedToken)
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === lost })
            assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { client.close() }
    }
    @Test fun lostResponseAndCancellationNeverTriggerAnAutomaticSecondWrite() = runTest {
        for (cancel in listOf(false, true)) {
            var posts = 0; var reads = 0
            val client = FinanceApiClient({ "unused" }, Store(), MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    posts++
                    if (cancel) throw CancellationException("caller cancelled") else throw IOException("response lost")
                }
                reads++; assertEquals("key", request.url.parameters["key"]); respond(receipt, headers = headers)
            })
            try {
                val api = ImportAccountApi(client, scope)
                if (cancel) assertFailsWith<CancellationException> { api.create("Original", "cash", listOf("CNY"), "key") }
                else {
                    assertFailsWith<IOException> { api.create("Original", "cash", listOf("CNY"), "key") }
                    assertEquals("receipt", api.receiptByKey("key").receiptId)
                }
                assertEquals(1, posts); assertEquals(if (cancel) 0 else 1, reads)
            } finally { client.close() }
        }
    }
    @Test fun legacyPublicCallsStillUseTheCurrentSessionWithoutAddingAScope() = runTest {
        val store = Store()
        val client = FinanceApiClient({ "https://later.example.test" }, store, MockEngine { request ->
            assertEquals("later.example.test", request.url.host)
            assertEquals("Bearer later-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.url.parameters["workspace_id"])
            respond("""{"items":[]}""", headers = headers)
        })
        try { client.listCashImportAccounts(); assertEquals(1, store.reads) } finally { client.close() }
    }
    @Test fun invalidScopeRevisionKeyOrEmptySuccessfulBodyCannotBecomeAnAccountResult() = runTest {
        val loneSurrogate = charArrayOf(0xd800.toChar()).concatToString()
        assertEquals(0xd800, loneSurrogate.single().code)
        for (workspace in listOf("", " ", loneSurrogate)) assertFailsWith<ApiFailure> { ImportAccountScope(workspace, scope.credentials) }
        var requests = 0
        val client = FinanceApiClient({ "unused" }, Store(), MockEngine { requests++; respond("", headers = headers) })
        try {
            val api = ImportAccountApi(client, scope)
            assertFailsWith<ApiFailure> { api.update("1", 0, JsonObject(emptyMap()), "key") }
            assertFailsWith<ApiFailure> { api.delete("1", 2, "bad key") }
            assertEquals(0, requests)
            assertEquals(200, assertFailsWith<ApiFailure> { api.create("Original", "cash", listOf("CNY"), "key") }.status)
            assertEquals(1, requests)
        } finally { client.close() }
    }
}
