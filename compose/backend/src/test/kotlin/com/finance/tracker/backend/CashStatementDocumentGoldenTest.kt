package com.finance.tracker.backend

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class CashStatementDocumentGoldenTest {
    @Test fun csvAndTsvDocumentsMatchPython() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-cash-statement-documents.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertEquals(11, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val content = Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content)
            val source = case.getValue("source").jsonPrimitive.content
            val result = runCatching { CashStatementDocumentReader.readPlatformDocument(content, source) }
            if (!case.getValue("ok").jsonPrimitive.boolean) {
                val failure = result.exceptionOrNull()
                assertIs<StatementDocumentFailure>(failure, "case $index")
                assertEquals(case.getValue("error").jsonPrimitive.content, failure.code, "case $index")
                continue
            }
            val document = result.getOrElse { throw AssertionError("case $index ${it::class.simpleName} ${it.message}", it) }
            val expected = case.getValue("document").jsonObject
            assertEquals(expected.getValue("source_type").jsonPrimitive.content, document.sourceType, "case $index")
            assertEquals(expected.getValue("table_key").jsonPrimitive.content, document.tableKey, "case $index")
            assertEquals(expected.getValue("parser_version").jsonPrimitive.content, document.parserVersion, "case $index")
            assertEquals(expected["account"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.sourceAccountIdentifier, "case $index")
            assertEquals(expected["label"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.sourceAccountLabel, "case $index")
            assertEquals(expected["declared"]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull,
                document.declaredCount, "case $index")
            assertEquals(expected.getValue("observed").jsonPrimitive.int, document.observedCount, "case $index")
            assertEquals(expected.getValue("rows").jsonArray.size, document.rows.size, "case $index")
            for ((position, item) in expected.getValue("rows").jsonArray.withIndex()) {
                val row = item.jsonObject
                val actual = document.rows[position]
                assertEquals(row.getValue("position").jsonPrimitive.int, actual.position, "case $index row $position")
                assertEquals(row["payload"]?.takeIf { it != JsonNull }?.jsonObject ?: JsonNull as JsonElement,
                    actual.payload?.let { JsonObject(it.mapValues { entry -> JsonPrimitive(entry.value) }) } ?: JsonNull,
                    "case $index row $position")
                assertEquals(row["error_code"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                    actual.errorCode, "case $index row $position")
            }
            val coverage = expected.getValue("coverage").jsonObject
            assertEquals(coverage["start"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.coverage.start, "case $index start")
            assertEquals(coverage["end"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull,
                document.coverage.end, "case $index end")
            assertEquals(coverage.getValue("complete").jsonPrimitive.boolean, document.coverage.complete, "case $index")
            assertEquals(coverage.getValue("evidence").jsonPrimitive.content, document.coverage.evidence, "case $index")
            assertEquals(coverage.getValue("strategy_version").jsonPrimitive.content, document.coverage.strategyVersion)
            assertEquals(coverage.getValue("filters").jsonObject, JsonObject(document.coverage.filters.mapValues { JsonPrimitive(it.value) }))
        }
    }
}
