package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class SchwabContractTest {
    private fun oracle() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_SCHWAB_CONTRACT"))).readText()).jsonObject
    private fun output(s: SchwabStatement) = buildJsonObject {
        put("transactions", JsonArray(s.transactions.map { JsonObject(it) })); put("ending_cash", s.endingCash); put("currency", s.currency)
    }
    private fun compare(expected: JsonObject, name: String, operation: () -> JsonElement) {
        if ("value" in expected) { assertEquals(expected["value"], operation(), name); return }
        val failure = assertFails(name) { operation() }
        when (expected.ibkrText("error")) {
            "ValueError" -> { assertIs<IllegalArgumentException>(failure, name); assertEquals(expected.ibkrText("message"), failure.message, name) }
            "InvalidOperation", "Overflow" -> assertIs<ArithmeticException>(failure, name)
            "AttributeError" -> assertIs<IllegalArgumentException>(failure, name)
            "Error" -> assertEquals(expected.ibkrText("message"), failure.message, name)
            else -> fail("Unhandled original exception ${expected["error"]}: $name")
        }
    }
    @Test fun completeParsedRowsIdentitiesEventsAndEachReplaySnapshot() {
        val o = oracle(); val s = SchwabImporter.parse(schwabFixtureBytes()); assertEquals(o["statement"], output(s))
        val doc = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        for ((i, row) in s.transactions.withIndex()) {
            val expected = o.getValue("mapped").jsonArray[i].jsonObject; assertEquals(expected.ibkrText("identity"), SchwabImporter.identity(row), "identity $i")
            val event = SchwabImporter.event(row, "嘉信", "USD"); assertEquals(expected["event"], event, "event $i")
            InvestmentProjection.applyEvent(doc, event, "USD"); assertEquals(o.getValue("snapshots").jsonArray[i], doc.value, "snapshot $i")
        }
    }
    @Test fun originalCsvAndDecimalBoundaryOutputs() {
        val failures = mutableListOf<String>()
        for (raw in oracle().getValue("parse_cases").jsonArray) {
            val c = raw.jsonObject
            runCatching {
            compare(c.getValue("expected").jsonObject, c.ibkrText("name")) { output(SchwabImporter.parse(c.ibkrText("content").toByteArray())) }
            if (c["parsed_events"] != JsonNull) compare(c.getValue("parsed_events").jsonObject, "parsed " + c.ibkrText("name")) {
                val s = SchwabImporter.parse(c.ibkrText("content").toByteArray())
                JsonArray(s.transactions.map { row -> buildJsonObject { put("identity", SchwabImporter.identity(row)); put("event", SchwabImporter.event(row, "嘉信", "USD")) } })
            }
            }.exceptionOrNull()?.let { failures += c.ibkrText("name") + ": " + it.toString().take(160) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun originalCompleteMappingAndIdentityBoundaries() {
        for ((i, raw) in oracle().getValue("map_cases").jsonArray.withIndex()) {
            val c = raw.jsonObject; val row = c.getValue("row").jsonObject
            compare(c.getValue("expected").jsonObject, "event $i") { SchwabImporter.event(row, "嘉信", c.ibkrText("currency")) }
            compare(c.getValue("identity").jsonObject, "identity $i") { JsonPrimitive(SchwabImporter.identity(row)) }
        }
    }
}
