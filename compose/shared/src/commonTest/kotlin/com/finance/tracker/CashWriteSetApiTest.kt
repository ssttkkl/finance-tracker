package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.contracts.CashMutationReceiptDto
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

class CashWriteSetApiTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val headers get() = headersOf(HttpHeaders.ContentType, "application/json")
    private val credentials = ApiCredentialSnapshot("original-token", "https://original.example.test")
    private val result = """{"transactions":[{"id":"9007199254740993","row_revision":"9223372036854775807","component_ids":["9007199254740995","2"]}],
        "source_bindings":[{"id":"5","revision":"8"}],"issues":[{"id":"6","revision":"9"}],
        "statement_account_mappings":[{"id":"7","revision":"10"}],"relations":[{"id":"8","revision":"11"}],
        "entries":[{"id":"条目😀","root_transaction_id":"9007199254740993","identity_transaction_id":"2","revision":"12"}],
        "resolved_issue_ids":["13","14"],"retired_entry_ids":["旧条目二","旧条目一"]}"""
    private fun receipt() = buildJsonObject {
        put("receipt_id", "receipt"); put("plan_id", JsonNull); put("operation_kind", "cash_write_set"); put("committed", true)
        put("created_at", "2026-11-14T00:00:00Z"); put("status", "completed"); put("ledger_seq", "9007199254740993")
        put("write_set_result", json.parseToJsonElement(result))
    }
    private fun input(payload: JsonElement = JsonObject(emptyMap())): CashWriteSetRequestDto {
        val target = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("行😀"), null)
        val row = CashTransactionWriteDto(target, CashTransactionWriteValuesDto("2", "posted", "import", null, "atomic", "ccb_debit", "source",
            payload, JsonObject(emptyMap()), null, JsonObject(emptyMap()), "2026-11-14T00:00:00Z", "-12.30", "CNY", "Party", "", JsonArray(emptyList()), "S", null, "consumption", "not_applicable"),
            listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part"), "2", "-12.30", "CNY", "0", "", "", JsonObject(emptyMap()))))
        return CashWriteSetRequestDto("9007199254740993", "cash-rules-v1", CashWriteSetDto(transactions = listOf(row)))
    }
    private suspend fun FinanceApiClient.accept(workspace: String, command: CashWriteSetRequestDto, key: String,
        bound: ApiCredentialSnapshot) =
        acceptCashImportConfirmation(workspace, LocalCashImportSubmission.freeze(key, command), bound) {}
    private class Store : TokenStore {
        var reads = 0; var clears = 0
        override suspend fun get(): String { reads++; return "later-token" }
        override suspend fun set(value: String) = Unit
        override suspend fun clear() { clears++ }
    }
    @Test fun submissionPreservesWireTextNullsExactNumbersAndAllActualIdentities() = runTest {
        val payload = json.parseToJsonElement("""{"n":9007199254740993,"d":1.2300,"e":1E+003,"z":-0,"nil":null}""")
        val store = Store(); var requests = 0
        val api = FinanceApiClient({ "https://later.example.test" }, store, cashImportMockEngine { request ->
            requests++
            assertEquals(HttpMethod.Post, request.method); assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization]); assertEquals("original", request.headers["Idempotency-Key"])
            assertEquals("/api/v1/cash-import/write-set", request.url.encodedPath)
            assertEquals(listOf("工作区/+?&"), request.url.parameters.getAll("workspace_id"))
            val text = (request.body as TextContent).text
            for (token in listOf("9007199254740993", "1.2300", "1E+003", "-0")) assertTrue(token in text, text)
            val body = json.parseToJsonElement(text).jsonObject
            assertEquals(JsonPrimitive("9007199254740993"), body["base_ledger_seq"])
            val changes = body.getValue("changes").jsonObject
            assertEquals(JsonArray(emptyList()), changes["statement_account_mappings"])
            assertFalse("account_mapping_groups" in changes); assertFalse("account_mapping_rows" in changes)
            val row = changes.getValue("transactions").jsonArray.single().jsonObject
            assertEquals(JsonNull, row.getValue("target").jsonObject["expected_revision"])
            assertEquals(JsonNull, row["entry_edit"])
            assertEquals(payload, row.getValue("values").jsonObject["source_payload"])
            respond("""{"operation_id":"original","status":"accepted"}""", HttpStatusCode.Accepted, headers)
        })
        try {
            val actual = api.accept("工作区/+?&", input(payload), "original", credentials)
            assertEquals(CashImportConfirmationStatus.ACCEPTED, actual.status)
            assertEquals("original", actual.operationId)
            assertNull(actual.receiptId)
            assertEquals(1, requests); assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { api.close() }
    }
    @Test fun callerContainerChangesAfterSuspensionCannotReplaceTheEncodedCommand() = runTest {
        val mutable = linkedMapOf<String, JsonElement>("value" to JsonPrimitive("original"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = FinanceApiClient({ "invalid later origin" }, Store(), cashImportMockEngine { request ->
            entered.complete(Unit); release.await()
            val body = json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val values = body.getValue("changes").jsonObject.getValue("transactions").jsonArray.single().jsonObject.getValue("values").jsonObject
            assertEquals(JsonPrimitive("original"), values.getValue("source_payload").jsonObject["value"])
            respond("""{"operation_id":"original","status":"accepted"}""", HttpStatusCode.Accepted, headers)
        })
        try {
            val pending = async { api.accept("workspace", input(JsonObject(mutable)), "original", credentials) }
            entered.await(); mutable["value"] = JsonPrimitive("later"); release.complete(Unit)
            assertEquals(CashImportConfirmationStatus.ACCEPTED, pending.await().status)
        } finally { api.close() }
    }
    @Test fun scopedIdAndKeyReadsKeepTheOriginalCredentialsAndNeverUsePlanResolve() = runTest {
        val store = Store(); val paths = mutableListOf<String>()
        val api = FinanceApiClient({ "invalid later origin" }, store, cashImportMockEngine { request ->
            assertEquals(HttpMethod.Get, request.method); assertEquals("original.example.test", request.url.host)
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            assertEquals(listOf("workspace"), request.url.parameters.getAll("workspace_id"))
            paths += request.url.encodedPath
            if (request.url.encodedPath == "/api/v1/cash-import/write-set/receipt") assertEquals("original", request.headers["Idempotency-Key"])
            respond(receipt().toString(), headers = headers)
        })
        try {
            assertEquals("receipt", api.readCashImportWriteSetReceiptByKey("workspace", "original", credentials).receiptId)
            assertEquals("receipt", api.readCashImportWriteSetReceipt("workspace", "receipt", credentials).receiptId)
            assertEquals(listOf("/api/v1/cash-import/write-set/receipt", "/api/v1/cash-mutations/receipts/receipt"), paths)
            assertEquals(0, store.reads); assertEquals(0, store.clears)
        } finally { api.close() }
    }
    @Test fun authenticatedCapabilityProbeIsCachedOnlyAfterSuccess() = runTest {
        CashImportTransportCache.resetForTest()
        var calls = 0
        val body = """{"command_versions":[1,2],"request_encodings":["identity","gzip"],"response_encodings":["gzip"]}"""
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { request ->
            calls++
            assertEquals(HttpMethod.Options, request.method)
            assertEquals("/api/v1/cash-import/write-set", request.url.encodedPath)
            assertEquals(listOf("workspace"), request.url.parameters.getAll("workspace_id"))
            assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            respond(body, headers = headers)
        })
        try {
            val first = api.cashImportTransportCapabilities("workspace", credentials)
            assertTrue(first!!.supportsCompactCommand)
            assertTrue(first.supportsGzipRequest)
            assertEquals(first, api.cashImportTransportCapabilities("workspace", credentials))
            assertEquals(1, calls)
        } finally { api.close(); CashImportTransportCache.resetForTest() }
        var failures = 0
        val failing = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine {
            failures++; respond("{}", HttpStatusCode.NotFound, headers = headers)
        })
        try {
            assertNull(failing.cashImportTransportCapabilities("workspace", credentials))
            assertNull(failing.cashImportTransportCapabilities("workspace", credentials))
            assertEquals(2, failures)
        } finally { failing.close(); CashImportTransportCache.resetForTest() }
    }

    @Test fun lostResponseCanBeReadByOriginalKeyWithoutAutomaticResubmission() = runTest {
        var posts = 0; var gets = 0
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { request ->
            if (request.method == HttpMethod.Post) { posts++; throw IOException("synthetic response loss") }
            gets++; assertEquals("original", request.headers["Idempotency-Key"])
            respond(receipt().toString(), headers = headers)
        })
        try {
            assertFailsWith<IOException> { api.accept("workspace", input(), "original", credentials) }
            val saved = api.readCashImportWriteSetReceiptByKey("workspace", "original", credentials)
            assertEquals("receipt", saved.receiptId); assertEquals(1, posts); assertEquals(1, gets)
        } finally { api.close() }
    }
    @Test fun missingKeyRemainsNotFoundWithoutInventingANotCommittedProof() = runTest {
        var requests = 0
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine {
            requests++; respond("""{"error":{"code":"receipt_not_found"}}""", HttpStatusCode.NotFound, headers)
        })
        try {
            val failed = assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceiptByKey("workspace", "original", credentials) }
            assertEquals(404, failed.status); assertEquals("receipt_not_found", failed.code); assertNull(failed.commitState); assertEquals(1, requests)
        } finally { api.close() }
    }
    @Test fun businessConflictsAndUnknownProgramsPreserveOnlyAuthoritativeCommitState() = runTest {
        for ((status, code, state) in listOf(Triple(409, "ledger_base_changed", "not_committed"), Triple(409, "idempotency_conflict", null),
            Triple(500, "internal_error", "unknown"), Triple(503, "import_unavailable", "not_committed"))) {
            val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine {
                respond(buildJsonObject { put("error", buildJsonObject { put("code", code) }); if (state != null) put("commit_state", state) }.toString(), HttpStatusCode.fromValue(status), headers)
            })
            try {
                val failed = assertFailsWith<ApiFailure> { api.accept("workspace", input(), "original", credentials) }
                assertEquals(status, failed.status); assertEquals(code, failed.code); assertEquals(state, failed.commitState)
            } finally { api.close() }
        }
    }
    @Test fun anonymousOrRejectedOriginalIdentityNeverAdoptsOrClearsLaterLogin() = runTest {
        for (token in listOf(null, "expired-original")) {
            val store = Store()
            val api = FinanceApiClient({ "unused" }, store, cashImportMockEngine { request ->
                assertEquals(token?.let { "Bearer $it" }, request.headers[HttpHeaders.Authorization])
                respond("{}", HttpStatusCode.Unauthorized, headers)
            })
            try {
                val failed = assertFailsWith<ApiFailure> { api.accept("workspace", input(), "original", ApiCredentialSnapshot(token, credentials.apiOrigin)) }
                assertEquals(401, failed.status); assertEquals(token, failed.rejectedToken)
                assertEquals(0, store.reads); assertEquals(0, store.clears)
            } finally { api.close() }
        }
    }
    @Test fun successRequiresACompleteCurrentWriteSetReceiptRatherThanLegacyOrPartialData() = runTest {
        val valid = receipt()
        val bad = listOf(JsonObject(valid - "ledger_seq"), JsonObject(valid - "write_set_result"),
            JsonObject(valid + ("committed" to JsonPrimitive(false))), JsonObject(valid + ("operation_kind" to JsonPrimitive("import"))),
            JsonObject(valid + ("plan_id" to JsonPrimitive("ip_old"))), JsonObject(valid + ("ledger_seq" to JsonPrimitive("01"))),
            JsonObject(valid + ("ledger_seq" to JsonPrimitive("9223372036854775808"))),
            JsonObject(valid + ("write_set_result" to JsonObject(valid.getValue("write_set_result").jsonObject - "entries"))))
        for (body in bad.map { it.toString() } + listOf("", " ", "{}", "null", "not-json")) {
            val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { respond(body, headers = headers) })
            try {
                val failed = assertFailsWith<ApiFailure>(body) {
                    api.readCashImportWriteSetReceiptByKey("workspace", "original", credentials)
                }
                assertEquals(200, failed.status); assertNull(failed.commitState); assertNotNull(failed.cause)
            } finally { api.close() }
        }
    }
    @Test fun noContentCannotBePublishedAsACommittedCommand() = runTest {
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { respond("", HttpStatusCode.NoContent, headers) })
        try {
            val failed = assertFailsWith<ApiFailure> { api.accept("workspace", input(), "original", credentials) }
            assertEquals(204, failed.status); assertNull(failed.commitState); assertNotNull(failed.cause)
        } finally { api.close() }
    }
    @Test fun rejectionHeadersSurviveBrokenDiagnosticBodiesWithoutInventingCommitState() = runTest {
        for (status in listOf(401, 403, 409, 500, 503)) {
            val store = Store(); val lost = IOException("synthetic diagnostic body loss")
            val api = FinanceApiClient({ "unused" }, store, cashImportMockEngine {
                respond(ByteChannel().apply { cancel(lost) }, HttpStatusCode.fromValue(status), headers)
            })
            try {
                val failed = assertFailsWith<ApiFailure> { api.accept("workspace", input(), "original", credentials) }
                assertEquals(status, failed.status); assertNull(failed.commitState)
                assertTrue(generateSequence<Throwable>(failed) { it.cause }.any { it === lost })
                assertEquals(0, store.clears); assertEquals(0, store.reads)
            } finally { api.close() }
        }
    }
    @Test fun cancellationPropagatesWithoutRetryingOrManufacturingAResult() = runTest {
        val cancelled = CancellationException("synthetic cancellation"); var requests = 0
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { requests++; throw cancelled })
        try {
            val actual = assertFailsWith<CancellationException> { api.accept("workspace", input(), "original", credentials) }
            assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancelled })
            assertEquals(1, requests)
        } finally { api.close() }
    }
    @Test fun invalidScopeOrOperationReferencesFailBeforeNetwork() = runTest {
        var requests = 0
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { requests++; error("Must not request") })
        try {
            val loneSurrogate = charArrayOf(0xd800.toChar()).concatToString()
            assertEquals(listOf(0xd800), loneSurrogate.map { it.code })
            for (workspace in listOf("", " ", loneSurrogate)) {
                assertFailsWith<ApiFailure> { api.accept(workspace, input(), "original", credentials) }
                assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceiptByKey(workspace, "original", credentials) }
                assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceipt(workspace, "receipt", credentials) }
            }
            assertFailsWith<ApiFailure> { api.accept("workspace", input(), "bad key", credentials) }
            assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceiptByKey("workspace", "", credentials) }
            assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceipt("workspace", loneSurrogate, credentials) }
            assertEquals(0, requests)
        } finally { api.close() }
    }
    @Test fun idLookupRejectsADifferentReceiptAndEscapesTheRequestedOpaqueIdentity() = runTest {
        val id = "回执/+?&"; var requests = 0
        val api = FinanceApiClient({ "unused" }, Store(), cashImportMockEngine { request ->
            requests++
            assertTrue(request.url.encodedPath.endsWith("%E5%9B%9E%E6%89%A7%2F%2B%3F%26"))
            assertEquals("workspace", request.url.parameters["workspace_id"])
            val value = if (requests == 1) receipt() else JsonObject(receipt() + ("receipt_id" to JsonPrimitive(id)))
            respond(value.toString(), headers = headers)
        })
        try {
            val failed = assertFailsWith<ApiFailure> { api.readCashImportWriteSetReceipt("workspace", id, credentials) }
            assertEquals(200, failed.status); assertNull(failed.commitState)
            assertEquals(id, api.readCashImportWriteSetReceipt("workspace", id, credentials).receiptId)
        } finally { api.close() }
    }

}
