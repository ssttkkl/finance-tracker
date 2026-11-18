package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class CashXlsParseApiTest {
    private class Xls(override val name: String = "synthetic.xls", override val size: Long? = null,
        private val onRead: () -> ByteArray = { byteArrayOf(0, 1, 127, -1) }) : BoundedFileSource {
        override val mediaType: String? = null
        override val lastModifiedAtMillis = 1730000000000L
        override suspend fun read(): ByteArray = error("unbounded read")
        override suspend fun readBounded(maxBytes: Int): ByteArray {
            assertEquals(100 * 1024 * 1024, maxBytes)
            return onRead()
        }
    }
    private fun api(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = "synthetic-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = Unit
        }, MockEngine(handler))
    private val wire = CashXlsParseResultDto("cash-rules-v1", CashStatementDocument("ccb_debit", "ccb_debit_xls", "v1",
        listOf(RawCashRow(1, mapOf("金额" to "-12.340000000000000001", "摘要" to "商户𐐀"), documentPosition = "0")),
        "synthetic-account", null, CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 9007199254740993L, 1))

    @Test fun oneXlsUsesOnlyStatelessEndpointAndPreservesTheCompleteRawDocument() = runTest {
        var requests = 0
        val client = api { request ->
            requests++
            assertEquals("/api/v1/cash-import/xls/parse", request.url.encodedPath)
            assertEquals("", request.url.encodedQuery)
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            val body = request.body.toByteArray()
            val text = body.decodeToString()
            assertTrue(text.contains("file:xls-a")); assertTrue(text.contains("ccb-debit"))
            assertTrue(text.contains("1730000000000")); assertFalse(text.contains("password"))
            assertTrue(body.toList().windowed(4).any { it == listOf<Byte>(0, 1, 127, -1) })
            respond(Json.encodeToString(wire), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        try {
            assertEquals(wire, client.parseCashXls(CashImportUploadInput("xls-a", Xls(), sourceHint = "ccb-debit")))
            assertEquals(1, requests)
        } finally { client.close() }
    }

    @Test fun invalidMetadataTypePasswordSourceAndBudgetNeverReadOrSend() = runTest {
        var reads = 0
        fun file(name: String = "synthetic.xls", size: Long? = null) = Xls(name, size) { reads++; byteArrayOf() }
        val client = api { error("must not send") }
        try {
            for ((input, code) in listOf(
                CashImportUploadInput("bad?", file()) to "import_payload_invalid",
                CashImportUploadInput("a", file("synthetic.xlsx")) to "import_file_unsupported_type",
                CashImportUploadInput("a", file("bad\r.xls")) to "import_file_unsupported_type",
                CashImportUploadInput("a", file(), password = "") to "import_payload_invalid",
                CashImportUploadInput("a", file(), sourceHint = "icbc_asia") to "statement_source_unsupported",
                CashImportUploadInput("a", file(size = 100L * 1024 * 1024 + 1)) to "import_file_too_large",
            )) assertEquals(code, assertFailsWith<ApiFailure> { client.parseCashXls(input) }.code)
            assertEquals(0, reads)
            assertEquals("import_file_too_large", assertFailsWith<ApiFailure> {
                client.parseCashXls(CashImportUploadInput("a", Xls { throw FileReadLimitExceeded() }))
            }.code)
            assertFailsWith<CancellationException> { client.parseCashXls(CashImportUploadInput("a", Xls { throw CancellationException("cancel") })) }
            assertFailsWith<IllegalStateException> { client.parseCashXls(CashImportUploadInput("a", Xls { error("synthetic bug") })) }
        } finally { client.close() }
    }

    @Test fun parserRejectionAndTemporaryFailureRetainTheirActualCodes() = runTest {
        for ((code, status) in listOf("statement_unreadable" to HttpStatusCode.UnprocessableEntity, "import_unavailable" to HttpStatusCode.ServiceUnavailable)) {
            val client = api { respond("""{"error":{"code":"$code","message":"synthetic","field_errors":[],"retryable":${status.value == 503}}}""",
                status, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            try {
                val failure = assertFailsWith<ApiFailure> { client.parseCashXls(CashImportUploadInput("a", Xls())) }
                assertEquals(code, failure.code); assertEquals(status.value, failure.status)
            } finally { client.close() }
        }
    }

    @Test fun lateUnauthorizedParseCannotClearCredentialsFromANewerLogin() = runTest {
        var token: String? = "old-login"
        val store = object : TokenStore {
            override suspend fun get() = token
            override suspend fun set(value: String) { token = value }
            override suspend fun clear() { token = null }
        }
        val client = FinanceApiClient({ "https://api.example.test" }, store, MockEngine { request ->
            assertEquals("Bearer old-login", request.headers[HttpHeaders.Authorization])
            store.set("new-login")
            respond("""{"error":{"code":"authentication_required"}}""", HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals(401, assertFailsWith<ApiFailure> { client.parseCashXls(CashImportUploadInput("a", Xls())) }.status)
            assertEquals("new-login", store.get())
        } finally { client.close() }
    }
}
