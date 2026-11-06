package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class DfzqContractTest {
    private fun oracle() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_DFZQ_CONTRACT"))).readText()).jsonObject
    private fun compare(expected: JsonObject, name: String, operation: () -> JsonElement) {
        if ("value" in expected) { assertEquals(expected["value"], operation(), name); return }
        val failure = assertFails(name) { operation() }
        when (expected.ibkrText("error")) {
            "ValueError" -> { assertIs<IllegalArgumentException>(failure, name); assertEquals(expected.ibkrText("message"), failure.message, name) }
            "InvalidOperation", "Overflow" -> assertIs<ArithmeticException>(failure, name)
            "IndexError" -> assertIs<IndexOutOfBoundsException>(failure, name)
            "TypeError" -> assertIs<IllegalArgumentException>(failure, name)
            "KeyError" -> assertIs<NoSuchElementException>(failure, name)
            else -> fail("Unhandled source error ${expected["error"]}: $name")
        }
    }
    @Test fun fullParserIdentityAndMappingBoundaries() {
        val failures = mutableListOf<String>()
        for (raw in oracle().getValue("parse_cases").jsonArray) {
            val c = raw.jsonObject; val name = c.ibkrText("name"); val lines = c.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
            runCatching {
                compare(c.getValue("expected").jsonObject, name) { JsonArray(DfzqImporter.parse(lines).map { JsonObject(it) }) }
                compare(c.getValue("mapped").jsonObject, "mapped $name") { JsonArray(DfzqImporter.parse(lines).map { row -> buildJsonObject { put("identity", DfzqImporter.identity(row)); put("event", DfzqImporter.event(row, "东方证券", "CNY")) } }) }
            }.exceptionOrNull()?.let { failures += "$name: " + it.toString().take(220) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun directMappingPrecisionTypeAndActionBoundaries() {
        val failures = mutableListOf<String>()
        for ((i, raw) in oracle().getValue("map_cases").jsonArray.withIndex()) {
            val c = raw.jsonObject; val row = DfzqRow(c.getValue("row").jsonObject, c.getValue("decimal_fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
            runCatching {
                compare(c.getValue("expected").jsonObject, "event $i") { DfzqImporter.event(row, "东方证券", c.ibkrText("currency")) }
                compare(c.getValue("identity").jsonObject, "identity $i") { JsonPrimitive(DfzqImporter.identity(row)) }
            }.exceptionOrNull()?.let { failures += "$i: " + it.toString().take(220) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun sampleReplaysEachCompleteSnapshot() {
        val o = oracle(); val fixture = o.getValue("parse_cases").jsonArray.first().jsonObject.getValue("lines").jsonArray.map { it.jsonPrimitive.content }
        val document = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        DfzqImporter.parse(fixture).forEachIndexed { i, row ->
            InvestmentProjection.applyEvent(document, DfzqImporter.event(row, "东方证券", "CNY"), "CNY")
            assertEquals(o.getValue("snapshots").jsonArray[i], document.value, "snapshot $i")
        }
    }
}
