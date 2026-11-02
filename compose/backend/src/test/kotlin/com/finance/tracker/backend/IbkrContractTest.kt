package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class IbkrContractTest {
    private fun oracle() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_IBKR_CONTRACT"))).readText()).jsonObject
    private fun output(s: IbkrStatement) = buildJsonObject {
        put("transactions", JsonArray(s.transactions.map { JsonObject(it) })); put("base_currency", s.baseCurrency); put("ending_cash", s.endingCash); put("beginning_cash", s.beginningCash); put("period", s.period); put("when_generated", s.whenGenerated)
    }
    private fun compare(expected: JsonObject, name: String, operation: () -> JsonElement) {
        if ("value" in expected) { assertEquals(expected["value"], operation(), name); return }
        val failure = assertFails(name) { operation() }
        when (expected.ibkrText("error")) {
            "ValueError" -> { assertIs<IllegalArgumentException>(failure, name); assertEquals(expected.ibkrText("message"), failure.message, name) }
            "InvalidOperation", "Overflow" -> assertIs<ArithmeticException>(failure, name)
            "Error" -> assertEquals(expected.ibkrText("message"), failure.message, name)
            else -> fail("Unhandled original exception ${expected["error"]}: $name")
        }
    }
    @Test fun completeParsedRowsIdentitiesEventsAndEachReplaySnapshot() {
        val o = oracle(); val s = IbkrImporter.parse(ibkrFixtureBytes()); assertEquals(o["statement"], output(s))
        val doc = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        for ((i, row) in s.transactions.withIndex()) {
            val expected = o.getValue("mapped").jsonArray[i].jsonObject; assertEquals(expected.ibkrText("identity"), IbkrImporter.identity(row), "identity $i")
            val event = IbkrImporter.event(row, "IBKR", s.baseCurrency); assertEquals(expected["event"], event, "event $i")
            InvestmentProjection.applyEvent(doc, event, s.baseCurrency); assertEquals(o.getValue("snapshots").jsonArray[i], doc.value, "snapshot $i")
        }
    }
    @Test fun originalCsvAndDecimalBoundaryOutputs() {
        for (raw in oracle().getValue("parse_cases").jsonArray) {
            val c = raw.jsonObject; compare(c.getValue("expected").jsonObject, c.ibkrText("name")) { output(IbkrImporter.parse(c.ibkrText("content").toByteArray())) }
            if (c["parsed_events"] != JsonNull) compare(c.getValue("parsed_events").jsonObject, "parsed " + c.ibkrText("name")) {
                val s = IbkrImporter.parse(c.ibkrText("content").toByteArray())
                JsonArray(s.transactions.map { row -> buildJsonObject { put("identity", IbkrImporter.identity(row)); put("event", IbkrImporter.event(row, "IBKR", s.baseCurrency)) } })
            }
        }
    }
    @Test fun originalCompleteMappingAndIdentityBoundaries() {
        for ((i, raw) in oracle().getValue("map_cases").jsonArray.withIndex()) {
            val c = raw.jsonObject; val row = c.getValue("row").jsonObject
            compare(c.getValue("expected").jsonObject, "event $i") { IbkrImporter.event(row, "IBKR", c.ibkrText("currency")) }
            compare(c.getValue("identity").jsonObject, "identity $i") { JsonPrimitive(IbkrImporter.identity(row)) }
        }
    }
}
