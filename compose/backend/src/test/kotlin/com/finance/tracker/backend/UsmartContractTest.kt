package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class UsmartContractTest {
    private fun oracle() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_USMART_CONTRACT"))).readText()).jsonObject
    private fun compare(expected: JsonObject, name: String, operation: () -> JsonElement) {
        if ("value" in expected) { assertEquals(expected["value"], operation(), name); return }
        val failure = assertFails(name) { operation() }
        when (expected.ibkrText("error")) {
            "ValueError", "IllegalMonthError" -> { assertIs<IllegalArgumentException>(failure, name); assertEquals(expected.ibkrText("message"), failure.message, name) }
            "InvalidOperation", "Overflow", "DivisionByZero", "ZeroDivisionError" -> assertIs<ArithmeticException>(failure, name)
            "TypeError" -> assertIs<IllegalArgumentException>(failure, name)
            "KeyError" -> assertIs<NoSuchElementException>(failure, name)
            else -> fail("Unhandled source error ${expected["error"]}: $name")
        }
    }
    @Test fun parserAndParsedIdentityEventBoundaries() {
        val failures = mutableListOf<String>()
        for (raw in oracle().getValue("parse_cases").jsonArray) {
            val c = raw.jsonObject; val name = c.ibkrText("name")
            fun parse(): List<UsmartRow> = when (val t = c.getValue("text")) { is JsonArray -> UsmartImporter.parse(t.map { it.jsonPrimitive.content }); else -> UsmartImporter.parse(t.jsonPrimitive.content) }
            runCatching {
                compare(c.getValue("expected").jsonObject, name) { JsonArray(parse().map { JsonObject(it) }) }
                compare(c.getValue("mapped").jsonObject, "mapped $name") { JsonArray(parse().map { r -> buildJsonObject { put("identity", UsmartImporter.identity(r)); put("event", UsmartImporter.event(r, "盈立证券")) } }) }
            }.exceptionOrNull()?.let { failures += "$name: ${it.toString().take(500)}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun mappingIdentityDecimalTypesAndCashFlags() {
        val failures = mutableListOf<String>()
        for ((i, raw) in oracle().getValue("map_cases").jsonArray.withIndex()) {
            val c = raw.jsonObject; val r = UsmartRow(c.getValue("row").jsonObject, c.getValue("decimal_fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
            for (op in listOf("expected", "identity")) runCatching {
                compare(c.getValue(op).jsonObject, "$op $i") { if (op == "expected") UsmartImporter.event(r, "盈立证券", c["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content) else JsonPrimitive(UsmartImporter.identity(r)) }
            }.exceptionOrNull()?.let { failures += "$i $op: ${it.toString().take(500)}" }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
