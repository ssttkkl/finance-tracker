package com.finance.tracker

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.BoundedFileSource
import com.finance.tracker.domain.FileSource
import com.finance.tracker.domain.FileReadLimitExceeded
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class LocalCashStatementReaderTest {
    private class File(override val name: String = "statement.csv", override val size: Long? = null,
        override val lastModifiedAtMillis: Long? = 1730000000000L, private val bytes: suspend () -> ByteArray) : BoundedFileSource {
        override val mediaType: String? = null
        override suspend fun read(): ByteArray = error("unbounded read")
        override suspend fun readBounded(maxBytes: Int): ByteArray {
            assertEquals(100 * 1024 * 1024, maxBytes)
            return bytes()
        }
    }
    private fun api(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData = { error("must remain offline") }) =
        FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
            override suspend fun get() = "token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = error("must not clear newer credentials")
        }, MockEngine(handler))
    private fun wire(document: ParsedCashDocument) = normalizedCashDocumentWire(document) { encodeLocalCashValue(it).jsonObject }
    private val coverage = CashSourceCoverage(null, null, false, "", emptyMap(), "v1")

    @Test fun csvXlsxAndAsiaReadTheOriginalFileOnceAndNeverUpload() = runTest {
        val client = api()
        try {
            for ((fixture, source) in listOf(csv to "alipay", xlsx to "wechat", asia to "icbc_asia")) {
                val bytes = Base64.decode(fixture)
                var reads = 0
                // Extension/media type do not replace the original byte/source routing.
                val input = CashImportUploadInput("stable", File { reads++; bytes }, sourceHint = source.replace('_', '-'))
                val expected = when (source) {
                    "icbc_asia" -> parseLocalIcbcAsiaStatement(bytes, source)
                    else -> if (bytes[0] == 'P'.code.toByte()) parseLocalCashXlsxStatement(bytes, source)
                        else parseLocalCashCsvStatement(bytes, source)
                }
                assertEquals(wire(expected), wire(client.parseLocalCashStatement(input)))
                assertTrue(expected.rows.isNotEmpty())
                assertEquals(1, reads)
            }
            // A UTF-16 BOM is also sufficient when no source was selected.
            assertEquals("icbc_asia", client.parseLocalCashStatement(CashImportUploadInput("asia", File { Base64.decode(asia) })).sourceType)
        } finally { client.close() }
    }

    @Test fun pdfAndOleUseOnlyTheirParseOnlyEndpointsAndTheSameReadBytes() = runTest {
        val pdfDocument = NormalizedCashDocumentDto("icbc_debit", "icbc_pdf", "v1", listOf(
            NormalizedCashRowDto(7, buildJsonObject { put("amount", "-12.340000000000000001"); put("source_revision", Json.parseToJsonElement("9007199254740993")) },
                "", "synthetic-id", emptyList(), null, "candidate", null, "0", buildJsonObject { put("text", "原文𐐀") })), null, null, coverage, null, 1, null)
        val xlsDocument = CashStatementDocument("ccb_debit", "sheet:0", "v1", listOf(RawCashRow(7, mapOf(
            "交易日期" to "20260815", "交易时间" to "10:00:00", "交易金额" to "-20.00", "对方户名" to "商户", "对方账号" to "6222",
            "序号" to "1", "摘要" to "消费", "账户余额" to "1000.00"))), "1234", "synthetic", coverage, null, 1)
        val pdfBytes = "%PDF-synthetic".encodeToByteArray()
        val xlsBytes = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31)
        var calls = 0
        val client = api { request ->
            calls++
            val pdf = request.url.encodedPath.endsWith("/pdf/parse")
            assertEquals(if (pdf) "/api/v1/cash-import/pdf/parse" else "/api/v1/cash-import/xls/parse", request.url.encodedPath)
            val bytes = request.body.toByteArray()
            val body = bytes.decodeToString()
            assertFalse("private-original-name" in body)
            assertTrue("1730000000000" in body)
            assertTrue("file:stable" in body)
            val original = if (pdf) pdfBytes else xlsBytes
            assertTrue(bytes.toList().windowed(original.size).any { it == original.toList() })
            if (pdf) {
                assertTrue("password" in body && "synthetic-secret" in body)
                respond(Json.encodeToString(CashPdfParseResultDto("v1", pdfDocument)), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            } else {
                assertFalse("password" in body)
                respond(Json.encodeToString(CashXlsParseResultDto("v1", xlsDocument)), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        try {
            var reads = 0
            val pdf = client.parseLocalCashStatement(CashImportUploadInput("stable", File("private-original-name.csv") { reads++; pdfBytes }, password = "synthetic-secret"))
            val xls = client.parseLocalCashStatement(CashImportUploadInput("stable", File("private-original-name.xlsx") { reads++; xlsBytes }, sourceHint = "ccb-debit"))
            assertEquals(wire(localNormalizedCashDocument(pdfDocument)), wire(pdf))
            assertEquals("9007199254740993", encodeLocalCashValue(pdf.rows.single().fields?.get("source_revision")).jsonPrimitive.content)
            assertEquals("-12.340000000000000001", pdf.rows.single().fields?.get("amount"))
            assertEquals("原文𐐀", pdf.rows.single().sourceExplanation["text"])
            assertEquals(wire(normalizeLocalCashStatement(xlsDocument)), wire(xls))
            assertTrue(xls.rows.single().fields != null); assertEquals("-20.00", xls.rows.single().fields?.get("amount").toString())
            assertEquals(2, reads); assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun malformedMetadataAndUnboundedSourcesNeverReadOrSend() = runTest {
        var reads = 0
        fun file(name: String = "statement.csv", size: Long? = null) = File(name, size) { reads++; byteArrayOf(1) }
        val client = api()
        try {
            for ((input, code) in listOf(
                CashImportUploadInput("bad?", file()) to "import_payload_invalid",
                CashImportUploadInput("a", file(), sourceHint = "BAD") to "import_payload_invalid",
                CashImportUploadInput("a", File(lastModifiedAtMillis = -1) { reads++; byteArrayOf() }) to "import_payload_invalid",
                CashImportUploadInput("a", file(), password = "x".repeat(1025)) to "import_payload_invalid",
                CashImportUploadInput("a", file("bad\r.csv")) to "import_file_unsupported_type",
                CashImportUploadInput("a", file("statement.exe")) to "import_file_unsupported_type",
                CashImportUploadInput("a", file(size = 100L * 1024 * 1024 + 1)) to "import_file_too_large",
            )) assertEquals(code, assertFailsWith<ApiFailure> { client.parseLocalCashStatement(input) }.code)
            val unbounded = object : FileSource {
                override val name = "statement.csv"
                override val mediaType: String? = null
                override val size: Long? = null
                override suspend fun read(): ByteArray = error("must not read")
            }
            assertEquals("import_file_size_unavailable", assertFailsWith<ApiFailure> {
                client.parseLocalCashStatement(CashImportUploadInput("a", unbounded))
            }.code)
            assertEquals(0, reads)
        } finally { client.close() }
    }

    @Test fun localAndRemoteFailuresNeverBecomeParsedDocuments() = runTest {
        val client = api()
        try {
            assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { byteArrayOf() }))
            }.code)
            assertEquals("statement_header_not_found", assertFailsWith<StatementDocumentFailure> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { "not a statement".encodeToByteArray() }))
            }.code)
            assertEquals("import_file_too_large", assertFailsWith<ApiFailure> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { throw FileReadLimitExceeded() }))
            }.code)
            val io = kotlinx.io.IOException("synthetic read fault")
            assertSame(io, assertFailsWith<kotlinx.io.IOException> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { throw io }))
            })
            val cancel = CancellationException("synthetic cancel")
            assertSame(cancel, assertFailsWith<CancellationException> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { throw cancel }))
            })
            val bug = IllegalStateException("synthetic bug")
            assertSame(bug, assertFailsWith<IllegalStateException> {
                client.parseLocalCashStatement(CashImportUploadInput("a", File { throw bug }))
            })
        } finally { client.close() }
        for ((code, status) in listOf("password_required" to 422, "password_incorrect" to 422, "import_unavailable" to 503, "authentication_required" to 401, "internal_error" to 500)) {
            val remote = api { respond("""{"error":{"code":"$code"}}""", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.ContentType, "application/json")) }
            try {
                val error = assertFailsWith<ApiFailure> { remote.parseLocalCashStatement(CashImportUploadInput("a", File { "%PDF-synthetic".encodeToByteArray() })) }
                assertEquals(code, error.code); assertEquals(status, error.status)
            } finally { remote.close() }
        }
    }

    @Test fun remoteCancellationAndProgramFaultPreserveTheirTypeAndCause() = runTest {
        for (failure in listOf(CancellationException("synthetic cancel"), IllegalStateException("synthetic bug"))) {
            val client = api { throw failure }
            try {
                val actual = assertFailsWith<Exception> {
                    client.parseLocalCashStatement(CashImportUploadInput("a", File { "%PDF-synthetic".encodeToByteArray() }))
                }
                assertEquals(failure::class, actual::class)
                // Ktor/coroutines may recover a stack trace by copying an exception with its original cause.
                assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure })
            } finally { client.close() }
        }
    }

    // Byte fixtures frozen by the original document oracles (platform cases 0/11, bank case 6).
    private val csv = "77u/5pSv5LuY5a6d5Lqk5piT6K6w5b2V5piO57uG5p+l6K+iDQrmlK/ku5jlrp3otKbmiLfvvJpbZml4dHVyZS1vd25lckBleGFtcGxlLnRlc3RdDQrotbflp4vml7bpl7TvvJpbMjAyNi0wOC0wMSAwMDowMDowMF0g57uI5q2i5pe26Ze077yaWzIwMjYtMDgtMzEgMjM6NTk6NTldDQrkuqTmmJPnsbvlnovvvJpb5YWo6YOoXQ0K5YWxMueslOiusOW9lQ0K5Lqk5piT5pe26Ze0LOS6pOaYk+WIhuexuyzkuqTmmJPlr7nmlrks5a+55pa56LSm5Y+3LOWVhuWTgeivtOaYjizmlLYv5pSvLOmHkeminSzmlLYv5LuY5qy+5pa55byPLOS6pOaYk+eKtuaAgSzkuqTmmJPorqLljZXlj7cs5ZWG5a626K6i5Y2V5Y+3DQoyMDI2LTA4LTE1IDEwOjAwOjAwLOaXpeeUqOeZvui0pyzmtYvor5XllYbmiLcsYWNjb3VudCwiICDljp/lp4vmj4/ov7AK56ys5LqM6KGMICAiLOaUr+WHuiwyMC4wMCzmtYvor5Xpk7booYzljaEs5Lqk5piT5oiQ5YqfLDAwMDAxMjMsbWVyY2hhbnQtMQ0KMjAyNi0wOC0xNSAxMDowMDowMCzml6XnlKjnmb7otKcs5rWL6K+V5ZWG5oi3LGFjY291bnQsIiAg5Y6f5aeL5o+P6L+wCuesrOS6jOihjCAgIizmlK/lh7osMjAuMDAs5rWL6K+V6ZO26KGM5Y2hLOS6pOaYk+aIkOWKnywwMDAwMTIzLG1lcmNoYW50LTENCg=="
    private val xlsx = "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"
    private val asia = "//6AkkyIDVQxegkA5V2Akp5OMm0NAAoAgJJMiOyMX4YJADEAMgAzADQANQA2ADcAOAA5ADAADQAKAGNeLnoJAEgASwBEAA0ACgA9fkZ7eGUJADEADQAKAOVlH2cJADIAMAAyADYALQAwADgALQAwADEACQAtAAkAMgAwADIANgAtADAAOAAtADMAMQANAAoAj15fhgkApE4TZkJmk5UJAAkAd41vYOVlH2cJAG1p2VJemItXCQBYZIGJCQA2ZWVR0ZFNmAkAL2X6UdGRTZgJABiZTZgJAA1cuWXsjF+GCQANXLllNmINVAkAkWFJi1+GCQAvU4dzCQCZUDuKCQCkThNmNFhAYg0ACgAxAAkAMgAwADIANgAtADAAOAAtADEANQAgADEAMAA6ADAAMAA6ADAAMAAJAAkAMgAwADIANgAtADAAOAAtADEANQAJAIhtOY0JAIhtOY0JAAkALQAyADAALgAwADAACQAxADAAMAAwAC4AMAAwAAkANgAyADIAMgAJAEZVN2IJAHYAMQAJAAkACQA="
}
