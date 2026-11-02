package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentCoreContractTest {
    private fun failure(f: Exception): JsonObject = if (f is IllegalArgumentException) buildJsonObject { put("error", "value"); put("message", f.message) } else buildJsonObject { put("error", "internal") }
    private fun decimal(value: String?): JsonElement {
        if (value == null) return JsonNull
        val r = requireNotNull(LedgerDecimal.parseRepresentationOrNull(value))
        return buildJsonObject { put("negative", r.negative); put("coefficient", r.coefficient); put("exponent", r.exponent.toString()) }
    }
    private fun JsonObject.text(key: String, default: String = "") = this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: default
    private fun JsonObject.money(key: String): CashAmount? = this[key]?.takeUnless { it == JsonNull }?.let { exactCashAmount(pythonString(it), key) }
    private fun command(a: JsonObject) = InvestmentCommand(a.text("record_type"), a.text("account"), a["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
        a.text("ticker"), a.money("quantity"), a.money("price"), a.money("commission") ?: exactCashAmount("0"), a.text("commission_asset"), a.text("from_ticker"), a.text("to_ticker"), a.money("to_quantity"), a.money("amount"), a.text("note"), a["date"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
    private fun run(c: JsonObject): JsonElement {
        val a = c.getValue("args").jsonObject
        return try {
            when (c.text("operation")) {
                "sequence" -> {
                    val docs = a.getValue("snapshots").jsonArray.map { CashSnapshotDocument(it.jsonObject) }; val states = mutableListOf<JsonElement>()
                    for (raw in a.getValue("calls").jsonArray) {
                        val call = raw.jsonObject
                        val result = try {
                            InvestmentProjection.applyEvent(docs[call.getValue("snapshot").jsonPrimitive.int], call.getValue("row").jsonObject, call.text("default_currency"), call["base_tickers"]?.takeUnless { it == JsonNull }?.jsonArray?.map { it.jsonPrimitive.content }); JsonNull
                        } catch (f: Exception) { failure(f) }
                        states.add(buildJsonObject { put("snapshots", JsonArray(docs.map { it.value })); put("result", result) })
                        if (result != JsonNull) break
                    }
                    JsonArray(states)
                }
                "event", "command" -> {
                    val doc = CashSnapshotDocument(a.getValue("snapshot").jsonObject)
                    val result = try {
                        if (c.text("operation") == "event") { InvestmentProjection.applyEvent(doc, a.getValue("row").jsonObject, a.text("default_currency")); JsonNull }
                        else InvestmentProjection.applyCommand(doc, command(a.getValue("command").jsonObject), a.text("account_type"), a.text("default_currency"))
                    } catch (f: Exception) { failure(f) }
                    buildJsonObject { put("snapshot", doc.value); put("result", result) }
                }
                "validate" -> { validateInvestmentSnapshot(a.getValue("snapshot").jsonObject); JsonNull }
                "bases" -> JsonArray(normalizeInvestmentBaseTickers(a["values"]?.takeUnless { it == JsonNull }?.jsonArray?.map(::pythonString)).sortedWith(::pythonStringOrder).map(::JsonPrimitive))
                "semantics" -> when (a.text("method")) {
                    "default" -> JsonPrimitive(defaultInvestmentRecordSubtype(a["record_type"]))
                    else -> normalizeInvestmentRecordSemantics(a["record_type"], a["record_subtype"]).let { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }
                }
                "performance" -> {
                    val precision = a.getValue("precision").jsonPrimitive.int
                    decimal(when (a.text("method")) {
                        "total" -> calculateTotalInvestmentPeriodPnl(a.text("opening"), a.text("closing"), a.getValue("flows").jsonArray.map { it.jsonPrimitive.content }, precision)
                        "instrument" -> calculateInstrumentPeriodPnl(a.text("opening"), a.text("closing"), a.getValue("flows").jsonArray.map { InvestmentInstrumentFlow(QuoteTime.parse("2026-08-01T00:00:00+00:00"), it.jsonPrimitive.content) }, a.text("income"), a.text("costs"), precision)
                        else -> calculateInvestmentPeriodReturn(a.text("pnl"), a.text("opening"), a.getValue("flows").jsonArray.map { InvestmentPeriodFlow(QuoteTime.parse(it.jsonObject.text("at")), it.jsonObject.text("amount")) }, QuoteTime.parse(a.text("start")), QuoteTime.parse(a.text("end")), precision)
                    })
                }
                else -> error("unknown operation")
            }
        } catch (f: Exception) { failure(f) }
    }
    @Test fun actualSourceOutputsErrorsAndPartialMemoryMatch() {
        val data = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_CORE_CONTRACT"))).readText()).jsonObject
        val cases = data.getValue("cases").jsonArray; assertEquals(256, cases.size)
        val errors = cases.mapNotNull { raw -> val c = raw.jsonObject; val actual = run(c); if (actual == c["expected"]) null else "${c["name"]}: expected=${c["expected"]}; actual=$actual" }
        assertTrue(errors.isEmpty(), errors.joinToString("\n"))
    }
    @Test fun actualCommandApplicationAndProjectionRunCompleteSequence() {
        val doc = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{}}""").jsonObject); val rows = mutableListOf<JsonObject>()
        val service = InvestmentCommandApplication { command ->
            val row = InvestmentProjection.applyCommand(doc, command, "security", "USD"); rows.add(row); validateInvestmentSnapshot(doc.value)
            buildJsonObject { put("ok", true); put("row", row) }
        }
        service.deposit("1000", "USD", "Broker", date = "2026-01-01T00:00:00+00:00")
        val result = service.swap("Broker", "usd", "500", "btc", "0.01", "USD", date = "2026-01-02T00:00:00+00:00", commission = "1")
        assertEquals(JsonPrimitive(true), result["ok"]); assertEquals(JsonPrimitive("1"), rows.last()["commission"]); assertEquals(JsonPrimitive("usd"), rows.last()["commission_asset"])
        val positions = doc.value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("Broker").jsonObject.getValue("positions").jsonObject
        assertEquals(0, "499".toBigDecimal().compareTo(positions.getValue("usd").jsonObject.getValue("shares").jsonPrimitive.content.toBigDecimal()))
        assertEquals(0, "0.01".toBigDecimal().compareTo(positions.getValue("btc").jsonObject.getValue("shares").jsonPrimitive.content.toBigDecimal()))
        assertEquals(JsonPrimitive("2026-01-02"), doc.value["updated_at"])
    }
    @Test fun snapshotValidationAllowsUnboundedFiniteAndPreservesInput() {
        val value = Json.parseToJsonElement("""{"accounts":{"crypto":{"Broker":{"positions":{"btc":{"shares":"1e10000","total_cost":"1e-10000"}}}}}}""").jsonObject
        val before = value.toString(); validateInvestmentSnapshot(value); assertEquals(before, value.toString())
        assertFailsWith<IllegalArgumentException> { exactCashAmount("1e10000") }; assertFailsWith<IllegalArgumentException> { exactCashAmount("1e-10000") }
    }
}
