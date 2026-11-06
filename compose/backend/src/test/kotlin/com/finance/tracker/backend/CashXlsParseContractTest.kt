package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashXlsParseResultDto
import com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.normalizeLocalCashStatement
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import io.ktor.server.application.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import kotlinx.serialization.json.*

class CashXlsParseContractTest {
    private val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-bank-statement-documents.json"))
        .bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject }
    private val ole = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
    private fun bytes(item: JsonObject) = Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content)
    private fun multipart(item: JsonObject) = MultiPartFormDataContent(formData {
        append("metadata", """{"files":[{"client_file_ref":"synthetic"}]}""")
        append("file:synthetic", bytes(item), Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"synthetic.xls\"")
        })
    })

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun allOriginalOleDocumentsReturnRawFieldsAndRemainEquivalentAfterLocalNormalization() = withLedgerTestWorkspace { db,_,token ->
        val selected = cases.filter { val bytes = bytes(it); bytes.size >= ole.size && ole.indices.all { i -> bytes[i] == ole[i] } }
        assertEquals(6, selected.size)
        val before = detailDatabaseState(db)
        val queries = AtomicInteger()
        testApplication {
            application { financeModule(authOnlyCashParseDatabase(queries)) }
            for ((index, item) in selected.withIndex()) {
                val response = client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token); setBody(multipart(item)) }
                assertEquals(if (item.getValue("ok").jsonPrimitive.boolean) HttpStatusCode.OK else HttpStatusCode.UnprocessableEntity,
                    response.status, "case $index ${response.bodyAsText()}")
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                val value = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                if (!item.getValue("ok").jsonPrimitive.boolean) {
                    assertEquals(item.getValue("error"), value.getValue("error").jsonObject.getValue("code"))
                } else {
                    assertEquals(setOf("rules_version", "document"), value.keys)
                    assertEquals(CASH_RULES_VERSION, value.getValue("rules_version").jsonPrimitive.content)
                    val expected = BankStatementDocumentReader.readBankDocument(bytes(item), "ccb_debit")
                    val actual = Json.decodeFromJsonElement<CashStatementDocument>(value.getValue("document"))
                    assertEquals(expected, actual, "complete raw document $index")
                    assertEquals(CashStatementRowNormalization.normalizeDocument(expected), normalizeLocalCashStatement(actual),
                        "complete local normalized document $index")
                }
            }
        }
        assertTrue(queries.get() > 0)
        assertEquals(before, detailDatabaseState(db))
    }

    private fun document(): CashStatementDocument = CashStatementDocument("ccb_debit", "ccb_debit_xls", "synthetic-v1",
        listOf(com.finance.tracker.business.contracts.RawCashRow(1, mapOf("摘要" to "账".repeat(512)))), null, null,
        CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), 1L, 1)

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun authenticationAndViewerRejectionHappenBeforeReadingOrParsing() = withLedgerTestWorkspace { db, workspace, token ->
        val calls = AtomicInteger()
        testApplication {
            application { financeModule(db, xlsParses = CashXlsParseService { calls.incrementAndGet(); document() }) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cash-import/xls/parse").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cash-import/xls/parse") { bearerAuth("unknown-token") }.status)
            setAccountTestRole(db, workspace, "viewer")
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token) }.status)
            assertEquals(0, calls.get())
        }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun changedSelectionRoleAndExpirySuppressDocumentsAndParserErrors() {
        for (change in listOf("selection", "role", "expiry")) for (parseError in listOf(false, true))
            withLedgerTestWorkspace { db, workspace, token ->
                val calls = AtomicInteger()
                testApplication {
                    application { financeModule(db, xlsParses = CashXlsParseService {
                        calls.incrementAndGet()
                        if (change == "role") setAccountTestRole(db, workspace, "viewer")
                        else db.write { connection ->
                            val update = connection.jooq(db.sqlite).update(USER_SESSIONS)
                            if (change == "selection") update.setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID)
                                .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
                            else update.set(USER_SESSIONS.EXPIRES_AT, java.time.Instant.parse("2000-01-01T00:00:00Z"))
                                .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
                        }
                        if (parseError) throw StatementDocumentFailure("statement_unreadable")
                        document()
                    }) }
                    try {
                        val response = client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
                        assertEquals(if (change == "expiry") HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden,
                            response.status, "$change/$parseError")
                        assertEquals(1, calls.get())
                        assertFalse(response.bodyAsText().contains("document"))
                        assertFalse(response.bodyAsText().contains("statement_unreadable"))
                    } finally {
                        db.write { connection -> connection.jooq(db.sqlite).update(USER_SESSIONS)
                            .set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace)
                            .set(USER_SESSIONS.EXPIRES_AT, java.time.Instant.parse("2099-01-01T00:00:00Z"))
                            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() }
                    }
                }
            }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun invalidMetadataFilesPasswordsAndSourcesNeverInvokeTheParser() = withLedgerTestWorkspace { db, _, token ->
        val calls = AtomicInteger()
        val valid = """{"files":[{"client_file_ref":"a"}]}"""
        val cases = listOf(listOf(valid, valid),
            listOf("""{"files":[{"client_file_ref":"a"}],"files":[{"client_file_ref":"a"}]}"""),
            listOf("""{"files":[{"client_file_ref":"a"},{"client_file_ref":"b"}]}"""),
            listOf("""{"files":[{"client_file_ref":"a","password":""}]}"""),
            listOf("""{"files":[{"client_file_ref":"a","source_hint":"icbc_asia"}]}"""))
        val before = detailDatabaseState(db)
        testApplication {
            application { financeModule(db, xlsParses = CashXlsParseService { calls.incrementAndGet(); document() }) }
            for ((index, metadata) in cases.withIndex()) {
                val response = client.post("/api/v1/cash-import/xls/parse") {
                    bearerAuth(token)
                    setBody(MultiPartFormDataContent(formData {
                        metadata.forEach { append("metadata", it) }
                        append("file:a", byteArrayOf(1), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"synthetic.xls\"") })
                        if (index == 2) append("file:b", byteArrayOf(2), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"synthetic.xls\"") })
                    }))
                }
                assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "$index ${response.bodyAsText()}")
                assertEquals(if (index == 4) "statement_source_unsupported" else "import_payload_invalid",
                    Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
        }
        assertEquals(0, calls.get()); assertEquals(before, detailDatabaseState(db))
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun busyTimeoutAndProgramFailureKeepAccurateErrorsAndClearOwnedBytes() {
        for (code in listOf("statement_parser_busy", "statement_parser_timeout", "program"))
            withLedgerTestWorkspace { db, _, token ->
                var owned: ByteArray? = null
                testApplication {
                    application { financeModule(db, xlsParses = CashXlsParseService { bytes ->
                        owned = bytes
                        if (code == "program") error("synthetic XLS invariant")
                        throw StatementDocumentFailure(code)
                    }) }
                    val response = client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
                    if (code == "program") {
                        assertEquals(HttpStatusCode.InternalServerError, response.status)
                        assertEquals("internal_error", Json.parseToJsonElement(response.bodyAsText()).jsonObject
                            .getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
                        assertFalse(response.bodyAsText().contains("synthetic XLS invariant"))
                        assertFalse(response.bodyAsText().contains("Stack Trace"))
                    } else {
                        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
                        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                        assertEquals("import_unavailable", error.getValue("code").jsonPrimitive.content)
                        assertTrue(error.getValue("retryable").jsonPrimitive.boolean)
                    }
                    assertTrue(requireNotNull(owned).isNotEmpty()); assertTrue(requireNotNull(owned).all { it == 0.toByte() })
                }
            }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun fileBudgetRejectsBeforeParsingAndClosesMultipartResources() = withLedgerTestWorkspace { db, _, token ->
        val calls = AtomicInteger()
        val resources = mutableListOf<CashMultipartResources>()
        testApplication {
            application {
                intercept(ApplicationCallPipeline.Setup) { call.attributes.put(cashMultipartObserverKey) { resources.add(it) } }
                financeModule(db, xlsParses = CashXlsParseService(maxFileBytes = 16) { calls.incrementAndGet(); document() })
            }
            val response = client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status, response.bodyAsText())
            assertEquals(0, calls.get()); assertEquals(1, resources.size)
            assertTrue(resources.single().pump.isCompleted); assertTrue(resources.single().sinks.all { it.closed })
        }
    }

    @Test fun responseBudgetCountsTheWholeRawDocumentAsActualUtf8() {
        val value = CashXlsParseResultDto(CASH_RULES_VERSION, document())
        val budget = Json { encodeDefaults = true; explicitNulls = true }.encodeToString(value).encodeToByteArray().size
        val upload = CashImportUpload("a", "synthetic.xls", byteArrayOf(1))
        assertEquals(value, CashXlsParseService(maxResponseBytes = budget) { document() }.parse(upload))
        assertEquals("statement_rows_too_large", assertFailsWith<StatementDocumentFailure> {
            CashXlsParseService(maxResponseBytes = budget - 1) { document() }.parse(upload)
        }.code)
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun actualWorkerAbortIsAProgramFailureRatherThanABadStatement() = withLedgerTestWorkspace { db, _, token ->
        val admission = PdfAdmission(1)
        val process = PdfProcessClient(admission, PdfWorkerLaunch(mainClass = "com.finance.tracker.backend.PdfFaultWorker",
            arguments = listOf("crash")), timeoutMillis = 3000)
        testApplication {
            application { financeModule(db, xlsParses = CashXlsParseService(parseDocument = process::readXls)) }
            val response = client.post("/api/v1/cash-import/xls/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertFalse(response.bodyAsText().contains("statement_unreadable"))
            assertFalse(response.bodyAsText().contains("Stack Trace"))
            assertEquals(0, admission.active)
        }
    }

    @Test fun cancellationAndDeniedAuthorizationRetainOriginalFailureSemantics() = runBlocking {
        val cancellation = CancellationException("synthetic XLS cancellation")
        var authorized = false
        assertFailsWith<CancellationException> {
            authorizedCashParse({ throw cancellation }) { authorized = true }
        }
        assertFalse(authorized)
        val original = AssertionError("synthetic XLS invariant")
        val denied = AccessFailure(true)
        val result = assertFailsWith<AccessFailure> {
            authorizedCashParse({ throw original }) { throw denied }
        }
        assertSame(denied, result); assertSame(original, result.suppressed.single())
    }
}
