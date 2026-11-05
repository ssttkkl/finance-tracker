package com.finance.tracker.backend

import com.finance.tracker.business.domain.parseLocalCashCsvStatement
import com.finance.tracker.business.domain.parseLocalIcbcAsiaStatement
import java.util.Base64
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashStatementFileParityTest {
    private fun cases(resource: String): List<JsonObject> = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream(resource)).bufferedReader().use { it.readText() }).jsonArray.map { it.jsonObject }
    private fun bytes(case: JsonObject) = Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content)
    private fun compare(cases: List<JsonObject>, expected: Int,
        native: (ByteArray, String?) -> CashStatementDocument,
        local: (ByteArray, String?) -> ParsedCashDocument, normalizationErrors: Map<Int, String> = emptyMap()) {
        assertEquals(expected, cases.size)
        for ((index, case) in cases.withIndex()) {
            val content = bytes(case)
            val original = content.copyOf()
            val source = case["source"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content
            val previous = runCatching { CashStatementRowNormalization.normalizeDocument(native(content, source)) }
            val current = runCatching { local(content, source) }
            val code = if (case.getValue("ok").jsonPrimitive.boolean) normalizationErrors[index]
                else case.getValue("error").jsonPrimitive.content
            if (code == null) {
                assertEquals(previous.getOrThrow(), current.getOrThrow(), "complete normalized file $index")
            } else {
                assertEquals(code, assertIs<StatementDocumentFailure>(previous.exceptionOrNull(), "native $index").code)
                assertEquals(code, assertIs<StatementDocumentFailure>(current.exceptionOrNull(), "local $index").code)
            }
            assertContentEquals(original, content, "source file bytes $index")
        }
    }
    @Test fun csvByteFactoryMatchesCompleteOriginalFilesAndNormalization() {
        val selected = cases("/python-cash-statement-documents.json").filter {
            val content = bytes(it)
            !(content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte())
        }
        // The raw fixture retains an Alipay malformed row; normalization rejects that entire file.
        compare(selected, 11, CashStatementDocumentReader::readPlatformDocument, ::parseLocalCashCsvStatement,
            normalizationErrors = mapOf(6 to "statement_unreadable"))
    }
    @Test fun oversizedWorkbookKeepsSourceAndInputChecksBeforeAnyZipRead() {
        val content = ByteArray(100 * 1024 * 1024 + 1).apply { this[0] = 'P'.code.toByte(); this[1] = 'K'.code.toByte() }
        assertEquals("statement_source_unsupported", assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(content, "unknown")
        }.code)
        assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> {
            CashStatementDocumentReader.readPlatformDocument(content, "wechat")
        }.code)
    }
    @Test fun asiaByteFactoryMatchesCompleteOriginalFilesAndNormalization() {
        val ole = byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte())
        val selected = cases("/python-bank-statement-documents.json").filter {
            val content = bytes(it)
            !(content.size >= ole.size && ole.indices.all { index -> content[index] == ole[index] })
        }
        compare(selected, 5, BankStatementDocumentReader::readBankDocument, ::parseLocalIcbcAsiaStatement)
    }
}
