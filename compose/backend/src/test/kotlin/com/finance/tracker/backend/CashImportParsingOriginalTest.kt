package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashXlsParseResultDto
import com.finance.tracker.business.domain.normalizeLocalCashStatement
import com.finance.tracker.business.domain.parseLocalIcbcAsiaStatement
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashImportParsingOriginalTest {
    private fun multipart(bytes: ByteArray, extension: String) = MultiPartFormDataContent(formData {
        append("metadata", """{"files":[{"client_file_ref":"original"}]}""")
        append("file:original", bytes, Headers.build {
            append(HttpHeaders.ContentDisposition, "filename=\"statement.$extension\"")
        })
    })

    @Test fun actualBankBytesUseCurrentParsingBoundariesAndPreserveIncompleteRows() = withLedgerTestWorkspace { db, _, token ->
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR"))).resolve("bank")
        val ccbBytes = root.resolve("ccb.bin").readBytes()
        val asiaBytes = root.resolve("asia.bin").readBytes()
        val before = detailDatabaseState(db)
        val queries = AtomicInteger()
        testApplication {
            application { financeModule(authOnlyCashParseDatabase(queries)) }
            val response = client.post("/api/v1/cash-import/xls/parse") {
                bearerAuth(token); setBody(multipart(ccbBytes, "xls"))
            }
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val wire = Json.decodeFromString<CashXlsParseResultDto>(response.bodyAsText())
            val ccb = normalizeLocalCashStatement(wire.document)
            val asia = parseLocalIcbcAsiaStatement(asiaBytes)
            assertEquals(CashStatementRowNormalization.parseCashStatement(ccbBytes), ccb)
            assertEquals(CashStatementRowNormalization.parseCashStatement(asiaBytes), asia)
            assertEquals(listOf("amount_unreadable"), ccb.rows[1].issueCodes)
            val values = assertNotNull(asia.rows[0].fields, "${asia.sourceType} ${asia.rows[0].errorCode} ${asia.rows[0].issueCodes}")
            assertEquals("-100.50", values["amount"])
            assertEquals(1, asia.observedCount); assertEquals(1L, asia.declaredCount); assertEquals(9, asia.rows.single().position)
            assertEquals("  原始備註\n  ", (values["source_payload"] as Map<*, *>)["備註"])
            assertNull(ccb.declaredCount)
            assertFailsWith<StatementDocumentFailure> {
                CashStatementRowNormalization.parseCashStatement("unrecognized".encodeToByteArray())
            }
        }
        assertTrue(queries.get() > 0)
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun asiaQuotedTabsQuotesAndNewlinesRemainOneLogicalRecordAndBadQuotesFailClosed() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_CONTEXT_CONTRACT_DIR"))).resolve("bank")
        val raw = BankStatementDocumentReader.readBankDocument(root.resolve("asia-quoted.bin").readBytes())
        assertEquals(1, raw.rows.size); assertEquals(1, raw.observedCount); assertEquals(1L, raw.declaredCount)
        assertEquals(9, raw.rows.single().position); assertEquals("a\t\"quoted\"\nrest", raw.rows.single().payload!!["備註"])
        assertEquals("statement_unreadable", assertFailsWith<StatementDocumentFailure> {
            BankStatementDocumentReader.readBankDocument(root.resolve("asia-bad-quote.bin").readBytes())
        }.code)
    }


    @Test fun pdfAndXlsParsingDoNotHoldTheWorkspaceLock() {
        for (extension in listOf("pdf", "xls")) withLedgerTestWorkspace { db, workspace, token ->
            val before = detailDatabaseState(db)
            var calls = 0
            val queries = AtomicInteger()
            fun checkConcurrentWriter() {
                calls++
                val entered = CountDownLatch(1); val executor = Executors.newSingleThreadExecutor()
                try {
                    val other = executor.submit { db.write { connection ->
                        assertTrue(connection.lockWorkspace(db.sqlite, workspace)); entered.countDown()
                    } }
                    assertTrue(entered.await(2, TimeUnit.SECONDS), "heavy parsing must be outside the workspace boundary")
                    other.get(2, TimeUnit.SECONDS)
                } finally { executor.shutdownNow() }
            }
            val raw = CashStatementDocument("ccb_debit", "sheet:0", "synthetic-v1", emptyList(), null, null,
                CashSourceCoverage(null, null, false, "", emptyMap(), "v1"), null, 0)
            testApplication {
                application { financeModule(authOnlyCashParseDatabase(queries),
                    pdfParses = CashPdfParseService { _, _, _ -> checkConcurrentWriter(); CashStatementRowNormalization.normalizeDocument(raw) },
                    xlsParses = CashXlsParseService { checkConcurrentWriter(); raw }) }
                val bytes = if (extension == "pdf") "%PDF-synthetic".encodeToByteArray()
                    else byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
                val response = client.post("/api/v1/cash-import/$extension/parse") {
                    bearerAuth(token); setBody(multipart(bytes, extension))
                }
                assertEquals(HttpStatusCode.OK, response.status, "$extension ${response.bodyAsText()}")
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
            assertEquals(1, calls)
            assertTrue(queries.get() > 0)
            assertEquals(before, detailDatabaseState(db))
        }
    }
}
