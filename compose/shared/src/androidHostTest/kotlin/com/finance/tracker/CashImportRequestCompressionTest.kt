package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportRequestCompressionTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    private val credentials = ApiCredentialSnapshot("compression-token", "https://compression.example.test")
    private val store = object : TokenStore {
        override suspend fun get(): String = "later-token"
        override suspend fun set(value: String) = Unit
        override suspend fun clear() = Unit
    }

    private fun request() = CashWriteSetRequestDto("1", "cash-rules-v1", CashWriteSetDto())
    private fun submission(payload: String = "x".repeat(40_000)) =
        LocalCashImportSubmission.freeze("compressed-command", request().copy(changes = CashWriteSetDto(
            transactions = listOf(CashTransactionWriteDto(
                CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("row"), null),
                CashTransactionWriteValuesDto("1", "posted", "import", null, "atomic", "ccb_debit", "source",
                    JsonObject(mapOf("text" to JsonPrimitive(payload))), JsonObject(emptyMap()), null, JsonObject(emptyMap()),
                    "2026-11-14T00:00:00Z", "1", "CNY", "P", "", JsonArray(emptyList()), "S", null, null, null),
                emptyList())))))

    @Test fun gzipRequestUsesTheSameFrozenJsonWhenTheServerAdvertisedIt() = runTest {
        CashImportTransportCache.resetForTest()
        CashImportTransportCache.probe(CashImportTransportCache.key(credentials.apiOrigin, credentials.token)) {
            CashImportTransportCapabilities(setOf(1, 2), setOf("identity", "gzip"), setOf("gzip"))
        }
        var inspected = false
        val api = FinanceApiClient({ "unused" }, store, cashImportMockEngine { request ->
            inspected = true
            assertEquals("gzip", request.headers[HttpHeaders.ContentEncoding])
            val content = request.body as? ByteArrayContent ?: fail("expected byte content")
            val decoded = GZIPInputStream(content.bytes().inputStream()).readBytes().decodeToString()
            val body = json.parseToJsonElement(decoded).jsonObject
            assertEquals(JsonPrimitive("1"), body["base_ledger_seq"])
            assertTrue(decoded.contains("x".repeat(1_000)))
            respond("""{"operation_id":"${request.headers["Idempotency-Key"]}","status":"accepted"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = api.acceptCashImportConfirmation("workspace", submission(), credentials) {}
            assertEquals("accepted", result.status.name.lowercase())
            assertTrue(inspected)
        } finally { api.close() }
    }

    @Test fun withoutACapabilityTheOriginalJsonAndTextBodyAreKept() = runTest {
        CashImportTransportCache.resetForTest()
        var inspected = false
        val api = FinanceApiClient({ "unused" }, store, cashImportMockEngine { request ->
            inspected = true
            assertNull(request.headers[HttpHeaders.ContentEncoding])
            assertTrue(request.body is TextContent)
            respond("""{"operation_id":"${request.headers["Idempotency-Key"]}","status":"accepted"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            api.acceptCashImportConfirmation("workspace", submission(), credentials) {}
            assertTrue(inspected)
        } finally { api.close() }
    }

    @Test fun persistedCompactTransportIsSentInsteadOfTheFullLogicalCommand() = runTest {
        CashImportTransportCache.resetForTest()
        val frozen = submission()
        val compact = JsonObject(json.parseToJsonElement(frozen.requestBody).jsonObject +
            ("command_version" to JsonPrimitive(2)) +
            ("request_digest" to JsonPrimitive(com.finance.tracker.business.domain.cashWriteSetRequestDigest(frozen.request())))).toString()
        var inspected = false
        val api = FinanceApiClient({ "unused" }, store, cashImportMockEngine { request ->
            inspected = true
            val text = (request.body as TextContent).text
            assertEquals(2, json.parseToJsonElement(text).jsonObject.getValue("command_version").jsonPrimitive.int)
            assertEquals(compact, text)
            respond("""{"operation_id":"${request.headers["Idempotency-Key"]}","status":"accepted"}""", HttpStatusCode.Accepted, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            api.acceptCashImportConfirmation("workspace", frozen.withTransport(compact), credentials) {}
            assertTrue(inspected)
        } finally { api.close() }
    }

    @Test fun capabilityParserRejectsMalformedAdvertisementsAndKeepsIdentityFallback() {
        val caps = parseCashImportTransportCapabilities(buildJsonObject {
            put("command_versions", JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))))
            put("request_encodings", JsonArray(listOf(JsonPrimitive("identity"), JsonPrimitive("gzip"))))
            put("response_encodings", JsonArray(listOf(JsonPrimitive("gzip"))))
        })
        assertTrue(caps!!.supportsCompactCommand)
        assertTrue(caps.supportsGzipRequest)
        assertNull(parseCashImportTransportCapabilities(buildJsonObject {
            put("command_versions", JsonArray(listOf(JsonPrimitive("2"))))
            put("request_encodings", JsonArray(listOf(JsonPrimitive("gzip"))))
            put("response_encodings", JsonArray(emptyList()))
        }))
    }
}
