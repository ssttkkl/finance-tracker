package com.finance.tracker.backend

import com.finance.tracker.business.domain.CASH_RULES_VERSION
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.ktor.server.application.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashPdfParseContractTest {
    private val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json"))
        .bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject }

    private fun multipart(item: JsonObject) = MultiPartFormDataContent(formData {
        append("metadata", buildJsonObject { put("files", buildJsonArray { add(buildJsonObject {
            put("client_file_ref", "synthetic")
            item["source"]?.takeIf { it != JsonNull }?.let { put("source_hint", it) }
            item["password"]?.takeIf { it != JsonNull }?.let { put("password", it) }
        }) }) }.toString())
        append("file:synthetic", Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content),
            Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"synthetic.pdf\"") })
    })


    @Test fun allOriginalPdfCasesUseStatelessNormalizedHttpWithoutReadingOrWritingTheLedger() = withLedgerTestWorkspace { db,_,token ->
        val before = detailDatabaseState(db)
        val queries = AtomicInteger()
        testApplication {
            application { financeModule(authOnlyCashParseDatabase(queries)) }
            assertEquals(38, cases.size)
            for (item in cases) {
                val name = item.getValue("name").jsonPrimitive.content
                val response = client.post("/api/v1/cash-import/pdf/parse") { bearerAuth(token); setBody(multipart(item)) }
                val body = response.bodyAsText()
                if (item.getValue("ok").jsonPrimitive.boolean)
                    assertEquals(HttpStatusCode.OK, response.status, "$name $body")
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl], name)
                val value = Json.parseToJsonElement(body).jsonObject
                if (!item.getValue("ok").jsonPrimitive.boolean) {
                    val password = item["password_error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
                    val expected = when (password) { "required" -> "password_required"; "invalid" -> "password_incorrect"
                        else -> item.getValue("error").jsonPrimitive.content }
                    assertEquals(HttpStatusCode.UnprocessableEntity, response.status, "$name $body")
                    assertEquals(expected, value.getValue("error").jsonObject.getValue("code").jsonPrimitive.content, name)
                    assertFalse(body.contains("wrong"), name)
                } else {
                    assertEquals(HttpStatusCode.OK, response.status, "$name $body")
                    assertEquals(setOf("rules_version", "document"), value.keys, name)
                    assertEquals(CASH_RULES_VERSION, value.getValue("rules_version").jsonPrimitive.content, name)
                    val native = CashStatementRowNormalization.parseCashStatement(Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content),
                        item["source"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                        item["password"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content)
                    val old = Json.parseToJsonElement(ParsedImportCodec().bytes(native).decodeToString()).jsonObject
                    val expected = JsonObject(old + ("coverage" to Json { encodeDefaults = true; explicitNulls = true }.encodeToJsonElement(native.coverage)))
                    assertEquals(expected, value.getValue("document"), name)
                    if (name == "password-ok") assertFalse(body.contains("secret"))
                }
            }
        }
        assertTrue(queries.get() > 0)
        assertEquals(before, detailDatabaseState(db))
    }

    private fun successfulDocument(): ParsedCashDocument = ParsedCashDocument("icbc", "icbc_pdf", "synthetic-v1",
        emptyList(), null, null, CashSourceCoverage(null, null, false, "", emptyMap(), "coverage-v1"), 0L, 0)

    @Test fun authenticationAndViewerRejectionHappenBeforeParsing() = withLedgerTestWorkspace { db,workspace,token ->
        val calls = AtomicInteger()
        testApplication {
            application { financeModule(db, pdfParses = CashPdfParseService { _,_,_ -> calls.incrementAndGet(); successfulDocument() }) }
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cash-import/pdf/parse").status)
            assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cash-import/pdf/parse") { bearerAuth("expired-or-unknown") }.status)
            setAccountTestRole(db,workspace,"viewer")
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cash-import/pdf/parse") { bearerAuth(token) }.status)
            assertEquals(0,calls.get())
        }
    }

    @Test fun selectionAndRoleChangesDuringParsingPreventBothDocumentsAndParserErrorsFromEscaping() {
        for (change in listOf("selection", "role", "expiry")) for (parseError in listOf(false,true))
            withLedgerTestWorkspace { db,workspace,token ->
                val calls = AtomicInteger()
                testApplication {
                    application { financeModule(db, pdfParses = CashPdfParseService { _,_,_ ->
                        calls.incrementAndGet()
                        if (change == "role") setAccountTestRole(db,workspace,"viewer")
                        else db.write { c ->
                            val update = c.jooq(db.sqlite).update(USER_SESSIONS)
                            if (change == "selection") update.setNull(USER_SESSIONS.ACTIVE_WORKSPACE_ID)
                                .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
                            else update.set(USER_SESSIONS.EXPIRES_AT,java.time.Instant.parse("2000-01-01T00:00:00Z"))
                                .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute()
                        }
                        if (parseError) throw StatementPdfPasswordFailure(true)
                        successfulDocument()
                    }) }
                    try {
                        val response = client.post("/api/v1/cash-import/pdf/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
                        assertEquals(if (change == "expiry") HttpStatusCode.Unauthorized else HttpStatusCode.Forbidden,response.status,"$change/$parseError")
                        assertEquals(1,calls.get())
                        assertFalse(response.bodyAsText().contains("document"))
                        assertFalse(response.bodyAsText().contains("password_required"))
                    } finally {
                        db.write { c -> c.jooq(db.sqlite).update(USER_SESSIONS)
                            .set(USER_SESSIONS.ACTIVE_WORKSPACE_ID,workspace)
                            .set(USER_SESSIONS.EXPIRES_AT,java.time.Instant.parse("2099-01-01T00:00:00Z"))
                            .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(token))).execute() }
                    }
                }
            }
    }

    @Test fun malformedMetadataAndMultipleFilesAreRejectedWithoutCallingTheParser() = withLedgerTestWorkspace { db,_,token ->
        val calls = AtomicInteger()
        testApplication {
            application { financeModule(db, pdfParses = CashPdfParseService { _,_,_ -> calls.incrementAndGet(); successfulDocument() }) }
            val valid = """{"files":[{"client_file_ref":"a"}]}"""
            val inputs = listOf(
                listOf(valid,valid),
                listOf("""{"files":[{"client_file_ref":"a"}],"files":[{"client_file_ref":"a"}]}"""),
                listOf("""{"files":[{"client_file_ref":"a"},{"client_file_ref":"b"}]}"""),
                listOf("""{"files":[{"client_file_ref":"a","password":"${"x".repeat(1025)}"}]}"""))
            for ((index,metadata) in inputs.withIndex()) {
                val response = client.post("/api/v1/cash-import/pdf/parse") {
                    bearerAuth(token)
                    setBody(MultiPartFormDataContent(formData {
                        metadata.forEach { append("metadata",it) }
                        append("file:a",byteArrayOf(1),Headers.build { append(HttpHeaders.ContentDisposition,"filename=\"synthetic.pdf\"") })
                        if(index==2) append("file:b",byteArrayOf(2),Headers.build { append(HttpHeaders.ContentDisposition,"filename=\"synthetic.pdf\"") })
                    }))
                }
                assertEquals(HttpStatusCode.UnprocessableEntity,response.status,"case $index ${response.bodyAsText()}")
                assertEquals("import_payload_invalid",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            }
            assertEquals(0,calls.get())
        }
    }

    @Test fun busyAndTimeoutAreRetryableAndOwnedFileBytesAreClearedOnFailure() {
        for (code in listOf("statement_parser_busy","statement_parser_timeout")) withLedgerTestWorkspace { db,_,token ->
            var owned:ByteArray? = null
            testApplication {
                application { financeModule(db,pdfParses=CashPdfParseService { bytes,_,_ -> owned=bytes; throw StatementDocumentFailure(code) }) }
                val response=client.post("/api/v1/cash-import/pdf/parse") { bearerAuth(token); setBody(multipart(cases.first())) }
                assertEquals(HttpStatusCode.ServiceUnavailable,response.status)
                val error=Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
                assertEquals("import_unavailable",error.getValue("code").jsonPrimitive.content)
                assertTrue(error.getValue("retryable").jsonPrimitive.boolean)
                assertTrue(requireNotNull(owned).isNotEmpty())
                assertTrue(requireNotNull(owned).all { it==0.toByte() })
            }
        }
    }
    @Test fun fileBudgetRejectsBeforeTheParserAndReleasesMultipartResources()=withLedgerTestWorkspace { db,_,token ->
        val calls=AtomicInteger()
        val resources=mutableListOf<CashMultipartResources>()
        testApplication {
            application {
                intercept(io.ktor.server.application.ApplicationCallPipeline.Setup) {
                    call.attributes.put(cashMultipartObserverKey) {resources.add(it)}
                }
                financeModule(db,pdfParses=CashPdfParseService(maxFileBytes=16) { _,_,_ ->calls.incrementAndGet();successfulDocument()})
            }
            val response=client.post("/api/v1/cash-import/pdf/parse") {bearerAuth(token);setBody(multipart(cases.first()))}
            assertEquals(HttpStatusCode.PayloadTooLarge,response.status,response.bodyAsText())
            assertEquals("upload_too_large",Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(0,calls.get())
            assertEquals(1,resources.size)
            assertTrue(resources.single().pump.isCompleted)
            assertTrue(resources.single().sinks.all {it.closed})
        }
    }

}
