package com.finance.tracker.backend

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentDocumentTest {
    private fun oracle() = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_DOCUMENT_CONTRACT"))))).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun realApplicationFilesPreserveAllRowsMetadataIdentitiesAndEvents() {
        for (raw in oracle().getValue("cases").jsonArray) {
            val c = raw.jsonObject; val source = c.text("source")
            val rows = InvestmentDocument.read(source, c.text("filename"), Base64.getDecoder().decode(c.text("bytes")))
            assertEquals(c["rows"], JsonArray(rows.map { JsonObject(it) }), c.text("name"))
            assertEquals(c["mapped"], JsonArray(rows.map { row -> buildJsonObject {
                put("identity", InvestmentDocument.identity(source, row)); put("payload", InvestmentDocument.sourcePayload(source, row))
                put("event", InvestmentDocument.event(source, row, "broker", c.text("currency")))
            } }), c.text("name"))
            assertTrue(rows.all { when (source) { "ibkr" -> it is IbkrRow; "schwab" -> it is SchwabRow; "dfzq" -> it is DfzqRow; else -> it is UsmartRow } })
        }
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun utf8ReplacementMatchesPythonEvenForSurrogateEncodingAndIncompleteSequences() {
        for (raw in oracle().getValue("utf8").jsonArray) {
            val c = raw.jsonObject
            assertEquals(c.text("text"), InvestmentDocument.decodeText(Base64.getDecoder().decode(c.text("bytes"))), c.text("bytes"))
        }
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun sourcePayloadRequiresOriginalSnapshotAndFiniteJson() {
        for (raw in oracle().getValue("payloads").jsonArray) {
            val c = raw.jsonObject; val expected = c.getValue("expected").jsonObject
            val row = mapOf("_source_payload" to c.getValue("payload"))
            if ("value" in expected) assertEquals(expected["value"], InvestmentDocument.sourcePayload(c.text("source"), row))
            else assertEquals(expected.text("message"), assertFailsWith<IllegalArgumentException> { InvestmentDocument.sourcePayload(c.text("source"), row) }.message)
        }
    }
    @Test fun currencyFallbackSourceAliasesAndUnsupportedInputMatchApplication() {
        val base = listOf(mapOf("_ibkr_base_currency" to JsonPrimitive(" hkd ")))
        assertEquals("HKD", InvestmentDocument.currency("ibkr", null, base))
        assertEquals("USD", InvestmentDocument.currency("ibkr", "usd", base))
        assertEquals(" USD ", InvestmentDocument.currency("ibkr", " usd ", base))
        assertFailsWith<IllegalArgumentException> { InvestmentDocument.currency("ibkr", null, listOf(emptyMap())) }
        for ((source, default) in listOf("dfzq" to "CNY", "schwab" to "USD", "usmart-hk" to "USD", "usmart_hk" to "USD")) {
            assertEquals(default, InvestmentDocument.currency(source, "", listOf(emptyMap())))
            assertEquals("HKD", InvestmentDocument.currency(source, "hkd", listOf(emptyMap())))
        }
        assertEquals("usmart_hk_pdf", InvestmentDocument.sourceType("usmart_hk"))
        assertFailsWith<IllegalArgumentException> { InvestmentDocument.read("unknown", "statement.txt", byteArrayOf()) }
        for (source in listOf("ibkr", "schwab")) assertFailsWith<java.nio.charset.CharacterCodingException> { InvestmentDocument.read(source, "statement.txt", byteArrayOf(-1)) }
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun actualEncryptedAndPlainPdfDispatchUsesFreshWorkerAndOriginalMappedOutput() {
        val contract = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PDF_TOOL_CONTRACT")))
        for (raw in Json.parseToJsonElement(Files.readString(contract)).jsonObject.getValue("cases").jsonArray) {
            val c = raw.jsonObject; val source = if (c.text("broker") == "dfzq") "dfzq" else "usmart-hk"
            val bytes = Files.readAllBytes(contract.parent.resolve(c.text("file")))
            val password = c["password"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
            val rows = InvestmentDocument.read(source, "statement.PDF", bytes, password)
            assertEquals(c["expected"], JsonArray(rows.map { row -> buildJsonObject {
                put("row", JsonObject(row)); put("identity", InvestmentDocument.identity(source, row))
                put("event", InvestmentDocument.event(source, row, c.text("account"), c.text("currency")))
            } }))
            if (password != null) {
                assertTrue(assertFailsWith<StatementPdfPasswordFailure> { InvestmentDocument.read(source, "statement.pdf", bytes) }.required)
                assertFalse(assertFailsWith<StatementPdfPasswordFailure> { InvestmentDocument.read(source, "statement.pdf", bytes, "wrong") }.required)
            }
        }
    }
}
