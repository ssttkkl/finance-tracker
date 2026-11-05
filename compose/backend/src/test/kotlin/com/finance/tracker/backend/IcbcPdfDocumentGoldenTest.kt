package com.finance.tracker.backend

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class IcbcPdfDocumentGoldenTest {
    @Test fun icbcPdfDocumentsMatchPython() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(38, cases.size)
        for (case in cases) {
            val item = case.jsonObject
            val name = item.getValue("name").jsonPrimitive.content
            val content = Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content)
            val source = item["source"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val password = item["password"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            val result = runCatching { IcbcPdfDocumentReader.readIcbcDocument(content, source, password) }
            val passwordError = item["password_error"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            if (passwordError != null) {
                val failure = result.exceptionOrNull()
                assertIs<StatementPdfPasswordFailure>(failure, name)
                assertEquals(passwordError == "required", failure.required, name)
                continue
            }
            if (!item.getValue("ok").jsonPrimitive.boolean) {
                val failure = result.exceptionOrNull()
                assertIs<StatementDocumentFailure>(failure, name)
                assertEquals(item.getValue("error").jsonPrimitive.content, failure.code, name)
                continue
            }
            val document = result.getOrElse { throw AssertionError("$name ${it.message}", it) }
            val expected = item.getValue("document").jsonObject
            assertEquals(expected.getValue("source_type").jsonPrimitive.content, document.sourceType, name)
            assertEquals(expected.getValue("table_key").jsonPrimitive.content, document.tableKey, name)
            assertEquals(expected.getValue("parser_version").jsonPrimitive.content, document.parserVersion, name)
            assertEquals(expected["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.sourceAccountIdentifier, name)
            assertEquals(expected["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.sourceAccountLabel, name)
            assertEquals(expected["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
                document.declaredCount, name)
            assertEquals(expected.getValue("observed").jsonPrimitive.int, document.observedCount, name)
            assertEquals(expected["currency"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.sourceCurrency, name)
            assertEquals(expected.getValue("rows").jsonArray.size, document.rows.size, name)
            for ((position, raw) in expected.getValue("rows").jsonArray.withIndex()) {
                val row = raw.jsonObject
                val actual = document.rows[position]
                assertEquals(row.getValue("position").jsonPrimitive.int, actual.position, "$name row $position")
                assertEquals(row["payload"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonNull,
                    actual.payload?.let { JsonObject(it.mapValues { entry -> JsonPrimitive(entry.value) }) } ?: JsonNull,
                    "$name row $position")
                assertEquals(row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.errorCode, "$name row $position")
                assertEquals(row["document_position"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.documentPosition, "$name row $position")
            }
            val coverage = expected.getValue("coverage").jsonObject
            assertEquals(coverage["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.coverage.start, "$name start")
            assertEquals(coverage["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.coverage.end, "$name end")
            assertEquals(coverage.getValue("complete").jsonPrimitive.boolean, document.coverage.complete, "$name complete")
            assertEquals(coverage.getValue("evidence").jsonPrimitive.content, document.coverage.evidence, "$name evidence")
            assertEquals(coverage.getValue("strategy_version").jsonPrimitive.content, document.coverage.strategyVersion)
            assertEquals(coverage.getValue("start_inclusive").jsonPrimitive.boolean, document.coverage.startInclusive)
            assertEquals(coverage.getValue("end_inclusive").jsonPrimitive.boolean, document.coverage.endInclusive)
            assertEquals(coverage.getValue("time_precision").jsonPrimitive.content, document.coverage.timePrecision)
            assertEquals(coverage["document_key"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.coverage.documentKey, "$name key")
            assertEquals(coverage["page_total"]?.takeIf { it != JsonNull }?.jsonPrimitive?.intOrNull,
                document.coverage.pageTotal, "$name page total")
            assertEquals(coverage.getValue("filters").jsonObject,
                JsonObject(document.coverage.filters.mapValues { JsonPrimitive(it.value) }), "$name filters")
            assertEquals(coverage.getValue("page_fingerprints").jsonObject,
                JsonObject(document.coverage.pageFingerprints.mapValues { JsonPrimitive(it.value) }), "$name fingerprints")
        }
    }

    @Test fun dispatcherRoutesPdfBytesToIcbcReader() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-icbc-pdf-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        val item = cases.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == "credit" }
        val content = Base64.getDecoder().decode(item.getValue("content").jsonPrimitive.content)
        val document = CashStatementDocuments.readDocument(content)
        assertEquals("icbc_credit", document.sourceType)
        assertEquals("icbc-pdf-tables-v1", document.parserVersion)
    }
}
