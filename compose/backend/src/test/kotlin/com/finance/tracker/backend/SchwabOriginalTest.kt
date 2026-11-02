package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.*
import kotlinx.serialization.json.*

internal fun schwabFixtureBytes() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_SCHWAB_FIXTURE"))).readBytes()
class SchwabOriginalTest {
    private fun statement() = SchwabImporter.parse(schwabFixtureBytes())
    private fun event(row: Map<String, JsonElement>) = SchwabImporter.event(row, "嘉信", "USD")
    @Test fun countsNewestBalanceAndChronologicalOrder() {
        val s = statement(); val flows = s.transactions.filter { it.ibkrText("type") != "CHECKIN" }
        assertEquals(36, flows.size); assertEquals(mapOf("TRD" to 27, "DOI" to 4, "JRN" to 4, "WIN" to 1), flows.groupingBy { it.ibkrText("type") }.eachCount())
        assertEquals("2865.36".toBigDecimal(), s.endingCash.toBigDecimal()); assertEquals("CHECKIN", s.transactions.last().ibkrText("type"))
        assertEquals(s.endingCash.toBigDecimal(), s.transactions.last().ibkrText("amount").toBigDecimal()); assertEquals(s.endingCash.toBigDecimal(), s.transactions.last().ibkrText("balance").toBigDecimal())
        val keys = flows.map { it.ibkrText("date") to it.ibkrText("ref") }; assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
        assertEquals("120951021288", flows.first().ibkrText("ref")); assertEquals("1007269524312", flows.last().ibkrText("ref"))
    }
    @Test fun rejectsUnknownType() {
        val bytes = schwabFixtureBytes().toString(Charsets.UTF_8).replaceFirst(",TRD,", ",UNK,").toByteArray()
        assertContains(assertFailsWith<IllegalArgumentException> { SchwabImporter.parse(bytes) }.message!!, "unknown Schwab transaction type")
    }
    @Test fun tradesUseAmountAndFeesOnce() {
        val rows = statement().transactions; val sold = rows.first { it.ibkrText("type") == "TRD" && it.ibkrText("ref") == "1007269524312" }; val bot = rows.first { it.ibkrText("type") == "TRD" && it.ibkrText("ref") == "1007200374686" }
        val sell = event(sold); val buy = event(bot)
        for ((key, value) in mapOf("record_type" to "trade", "from_ticker" to "sndk.us", "from_amount" to "1", "to_ticker" to "usd", "to_amount" to "1550", "commission" to "0.03", "commission_asset" to "usd", "price" to "1550")) assertEquals(value, sell.ibkrText(key))
        for ((key, value) in mapOf("record_type" to "trade", "from_ticker" to "usd", "from_amount" to "5992", "to_ticker" to "sndk.us", "to_amount" to "4", "commission" to "0", "commission_asset" to "", "price" to "1498")) assertEquals(value, buy.ibkrText(key))
        assertEquals("schwab:1007269524312:TRD", SchwabImporter.identity(sold))
    }
    @Test fun cashActionsKeepLockedTypes() {
        val rows = statement().transactions
        fun check(row: Map<String, JsonElement>, type: String, subtype: String, direction: String, amount: String) {
            val e = event(row); assertEquals(type to subtype, e.ibkrText("record_type") to e.ibkrText("record_subtype")); assertEquals("usd", e.ibkrText(direction + "_ticker")); assertEquals(0, amount.toBigDecimal().compareTo(e.ibkrText(direction + "_amount").toBigDecimal()))
        }
        check(rows.first { it.ibkrText("type") == "WIN" }, "funding", "external", "to", "7980")
        val div = rows.first { it.ibkrText("type") == "DOI" && it.ibkrText("amount").toBigDecimal().signum() > 0 }; check(div, "income", "dividend_cash", "to", div.ibkrText("amount").toBigDecimal().abs().toPlainString())
        val interest = rows.first { it.ibkrText("type") == "DOI" && it.ibkrText("amount").toBigDecimal().signum() < 0 }; check(interest, "expense", "interest", "from", interest.ibkrText("amount").toBigDecimal().abs().toPlainString())
        val tax = rows.first { it.ibkrText("type") == "JRN" && it.ibkrText("amount").toBigDecimal().signum() < 0 }; check(tax, "expense", "tax", "from", tax.ibkrText("amount").toBigDecimal().abs().toPlainString())
        check(rows.first { it.ibkrText("type") == "JRN" && "REFUND" in it.ibkrText("note") }, "reversal", "funding_withdrawal", "to", "702.9")
    }
    @Test fun unpairedNegativeJournalFailsClosed() {
        val row = Json.parseToJsonElement("""{"type":"JRN","date":"2026-08-01","amount":"-0.14","description":"UNRELATED JOURNAL -0.14 US$"}""").jsonObject
        assertContains(assertFailsWith<IllegalArgumentException> { event(row) }.message!!, "structured tax or refund signal")
    }
    @Test fun positiveInterestKeepsIncomeInterest() {
        val row = Json.parseToJsonElement("""{"type":"DOI","date":"2026-08-01","amount":"0.42","description":"CREDIT INTEREST PAID 0.42 US$"}""").jsonObject
        val e = event(row); assertEquals("income" to "interest", e.ibkrText("record_type") to e.ibkrText("record_subtype"))
    }
    @Test fun snapshotHasNoDerivedNote() { assertEquals("", event(statement().transactions.first { it.ibkrText("type") == "CHECKIN" }).ibkrText("note")) }
    @Test fun offlineReplayReconcilesCashAndShares() {
        val doc = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        for (row in statement().transactions) InvestmentProjection.applyEvent(doc, event(row), "USD")
        val positions = doc.value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("嘉信").jsonObject.getValue("positions").jsonObject
        fun qty(ticker: String) = positions[ticker]?.jsonObject?.get("shares")?.jsonPrimitive?.content?.toBigDecimal() ?: java.math.BigDecimal.ZERO
        for ((ticker, value) in mapOf("usd" to "2865.36", "avgo.us" to "7", "msft.us" to "5", "qld" to "0", "mu" to "0", "smh" to "0", "sndk.us" to "0")) assertEquals(0, value.toBigDecimal().compareTo(qty(ticker)))
    }
}
