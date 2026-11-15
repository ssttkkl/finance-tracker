package com.finance.tracker

import com.finance.tracker.business.contracts.LedgerStatementMappingFactDto
import com.finance.tracker.business.domain.*
import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.BoundedFileSource
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.test.*

class LocalCashImportPreparationTest {
    private class File(override val name: String = "statement.pdf", val bytes: ByteArray,
        val readBytes: suspend () -> ByteArray = { bytes }) : BoundedFileSource {
        var reads = 0
        override val mediaType: String? = null
        override val size = bytes.size.toLong()
        override val lastModifiedAtMillis = 1730000000000L
        override suspend fun read(): ByteArray = error("unbounded read")
        override suspend fun readBounded(maxBytes: Int): ByteArray { reads++; return readBytes() }
    }
    private fun api(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        FinanceApiClient({ "https://preparation.example.test" }, object : TokenStore {
            override suspend fun get() = "later-token"
            override suspend fun set(value: String) = Unit
            override suspend fun clear() = error("must not clear credentials here")
        }, MockEngine(handler))
    private val credentials = ApiCredentialSnapshot("original-token", "https://original.example.test")

    @Test fun knownPasswordFailuresStoreOnlyMetadataAndNeverRetainTheFailedBytesOrPassword() = runTest {
        for (code in listOf("password_required", "password_incorrect")) {
            val bytes = "%PDF-private-synthetic-statement".encodeToByteArray()
            val file = File(bytes = bytes)
            val client = api { request ->
                assertEquals("original.example.test", request.url.host)
                assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
                respond("{\"error\":{\"code\":\"$code\",\"message\":\"untrusted diagnostic\"}}", HttpStatusCode.UnprocessableEntity,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
            try {
                val stored = client.captureLocalCashImportFileState(CashImportUploadInput("file", file, "synthetic-secret"), credentials)
                assertEquals(1, file.reads)
                val restored = LocalCashImportFile.decode(stored.encode())
                val view = restored.parsedFile()
                assertEquals("needs_password", view.status); assertEquals(code, view.errorCode); assertNull(view.document)
                assertEquals(sha256Hex(bytes), view.digest); assertEquals(bytes.size.toLong(), view.size)
                val json = Json.parseToJsonElement(stored.encode()).jsonObject
                assertEquals(JsonNull, json["bytes"]); assertEquals(JsonNull, json["document"])
                for (secret in listOf("synthetic-secret", "untrusted diagnostic", "private-synthetic-statement", Base64.encode(bytes)))
                    assertFalse(stored.encode().contains(secret))
                assertEquals("cash_import_file_source_missing", assertFailsWith<IllegalStateException> { restored.originalInput() }.message)
            } finally { client.close() }
        }
    }

    @Test fun localFormatFailureHasAnExplicitFailedStateWhileTheSuccessOnlyEntryStillThrows() = runTest {
        val client = api { error("local parsing must not upload") }
        try {
            val input = CashImportUploadInput("bad", File("empty.csv", ByteArray(0)))
            val state = client.captureLocalCashImportFileState(input, credentials)
            assertEquals("failed", state.parsedFile().status)
            assertEquals("statement_input_invalid", state.parsedFile().errorCode)
            assertNull(state.parsedFile().document)
            assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> {
                client.captureLocalCashImportFile(input, credentials)
            }.code)
        } finally { client.close() }
    }

    @Test fun authorizationAndServerFailuresAreNotConvertedToFileOutcomes() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            val client = api { respond("{\"error\":{\"code\":\"statement_unreadable\"}}", status,
                headersOf(HttpHeaders.ContentType, "application/json")) }
            try {
                val failure = assertFailsWith<ApiFailure> { client.captureLocalCashImportFileState(
                    CashImportUploadInput("file", File(bytes = "%PDF-synthetic".encodeToByteArray())), credentials) }
                assertEquals(status.value, failure.status)
                if (status == HttpStatusCode.Unauthorized) assertEquals("original-token", failure.rejectedToken)
            } finally { client.close() }
        }
    }

    @Test fun programErrorsTransportFailuresAndCancellationKeepTheirOriginalFailure() = runTest {
        for (failure in listOf(IllegalStateException("synthetic program fault"), IOException("synthetic transport"), CancellationException("synthetic cancel"))) {
            val client = api { throw failure }
            try {
                val actual = assertFailsWith<Exception> { client.captureLocalCashImportFileState(
                    CashImportUploadInput("file", File(bytes = "%PDF-synthetic".encodeToByteArray())), credentials) }
                assertTrue(generateSequence(actual as Throwable?) { it.cause }.any { it === failure || it::class == failure::class && it.message == failure.message })
            } finally { client.close() }
        }
    }

    @Test fun failureEncodingRejectsBytesDocumentsUnknownCodesAndContradictoryStatuses() {
        val original = localCashImportFileFixture()
        val failed = LocalCashImportFile.captureFailure(original.originalInput(), byteArrayOf(1, 2), "statement_unreadable")
        val root = Json.parseToJsonElement(failed.encode()).jsonObject
        val success = Json.parseToJsonElement(original.encode()).jsonObject
        for ((key, value) in listOf("version" to JsonPrimitive(3), "version" to JsonPrimitive(1),
            "bytes" to JsonPrimitive("AQI="), "document" to success.getValue("document"),
            "status" to JsonPrimitive("parsed"), "status" to JsonPrimitive("needs_password"),
            "errorCode" to JsonPrimitive("arbitrary_private_message"), "digest" to JsonPrimitive("invalid"))) {
            assertFails { LocalCashImportFile.decode(JsonObject(root + (key to value)).toString()) }
        }
        assertEquals(1, Json.parseToJsonElement(original.encode()).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(original.encode(), LocalCashImportFile.decode(original.encode()).encode())
    }

    @Test fun preparationViewRetainsFileStatesAndExplicitSelectionsWithoutMakingATtlOrPlan() {
        val f = LocalCashImportRebaseFixture(includeRefund = true)
        val group = scanLocalCashFunding(f.files.map { it.parsedFile() }).groups.single()
        val source = f.source.copy(facts = f.source.facts.copy(statementAccountMappings = listOf(
            LedgerStatementMappingFactDto("1", group.sourceType, "funding_group_v1", group.sourceAccountRef, "10", "user", "1", "2026-10-01T00:00:00Z", "2026-10-01T00:00:00Z"))))
        val bad = LocalCashImportFile.captureFailure(CashImportUploadInput("bad", File(bytes = byteArrayOf(1))), byteArrayOf(1), "password_required")
        val view = localCashImportPreparationView("batch", f.files + bad, source, f.review)
        assertNull(view.sessionRevision); assertNull(view.expiresAt); assertNull(view.lastPlanId)
        assertEquals("open", view.state); assertTrue(view.canPreview)
        assertEquals(listOf("ready", "password_required"), view.files.map { it.status })
        assertEquals(2, view.files.first().recognizedRowCount)
        assertEquals("password_required", view.files.last().issues.single().code)
        assertTrue(view.files.last().canRetry); assertNull(view.files.last().recognizedRowCount)
        val account = view.sourceAccounts.single()
        assertEquals("10", account.suggestion.accountId); assertEquals(1L, account.suggestion.expectedAccountRevision)
        assertEquals("confirmed_mapping", account.suggestion.basis)
        assertEquals("10", account.selection?.accountId); assertEquals(1L, account.selection?.expectedAccountRevision)
        assertEquals(f.review.inputJson(), view.lastPreviewInput)
        assertNotNull(account.sessionGroupRef)
        assertNotEquals(account.sessionGroupRef, localCashImportPreparationView("another", f.files, source, f.review).sourceAccounts.single().sessionGroupRef)
        val document = assertNotNull(f.files.single().parsedFile().document)
        val partial = LocalCashImportFile.capture(f.files.single().originalInput(), byteArrayOf(1),
            document.copy(rows = document.rows.map { it.copy(issueCodes = listOf("statement_unreadable")) }))
        assertEquals("partial", localCashImportPreparationView("batch", listOf(partial), source, null).files.single().status)
        assertFalse(localCashImportPreparationView("batch", listOf(bad), source, null).canPreview)
        assertEquals(emptyList(), localCashImportPreparationView("batch", emptyList(), source, null).sourceAccounts)
    }

    @Test fun fileNamesOnlyLoseTheirPathAndOverflowCodePoints() {
        val file = LocalCashImportRebaseFixture().files.single()
        val original = "账号1234567890123456_密码=synthetic-secret_" + "😀".repeat(260) + ".pdf"
        val encoded = Json.parseToJsonElement(file.encode()).jsonObject
        val renamed = LocalCashImportFile.decode(JsonObject(encoded + ("filename" to JsonPrimitive("folder\\$original"))).toString())
        val view = localCashImportPreparationView("batch", listOf(renamed), LocalCashImportRebaseFixture().source, null).files.single()
        assertEquals("账号1234567890123456_密码=synthetic-secret_" + "😀".repeat(216), view.displayName)
        assertEquals(255, view.displayName.pythonCodePointCount())
        assertTrue(view.displayName.startsWith("账号1234567890123456_密码=synthetic-secret_"))
        assertFalse(view.displayName.contains("folder")); assertFalse(view.displayName.last().isHighSurrogate())
    }
}
