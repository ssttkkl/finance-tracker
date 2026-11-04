package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.test.*
import kotlinx.serialization.json.*

internal fun ibkrFixtureBytes() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IBKR_FIXTURE"))).readBytes()
internal fun Map<String, JsonElement>.ibkrText(key: String) = getValue(key).jsonPrimitive.content
@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class IbkrOriginalTest {
    private fun statement() = IbkrImporter.parse(ibkrFixtureBytes())
    @Test fun readsFlowCensusAndSummaryCash() {
        val s = statement(); val flows = s.transactions.filter { it.ibkrText("action") != "CHECKIN" }
        assertEquals(38, flows.size)
        assertEquals(mapOf("买" to 17, "卖" to 10, "存款" to 6, "股息" to 1, "外国预扣税" to 1, "借方利息" to 1, "外汇交易组成部分" to 2), flows.groupingBy { it.ibkrText("action") }.eachCount())
        assertEquals("USD", s.baseCurrency); assertEquals("5044.938780328453".toBigDecimal(), s.endingCash.toBigDecimal())
        assertEquals("CHECKIN", s.transactions.last().ibkrText("action")); assertEquals(s.endingCash.toBigDecimal(), s.transactions.last().ibkrText("amount").toBigDecimal())
    }
    @Test fun rejectsUnknownTransactionType() {
        val bytes = ibkrFixtureBytes().toString(Charsets.UTF_8).replaceFirst(",卖,GOOG,", ",未知类型,GOOG,").toByteArray()
        assertContains(assertFailsWith<IllegalArgumentException> { IbkrImporter.parse(bytes) }.message!!, "unknown IBKR transaction type")
    }
    @Test fun equityUsesGrossPlusCommissionOnce() {
        val rows = statement().transactions
        val buy = rows.first { it.ibkrText("action") == "买" && it.ibkrText("code") == "SNDK" }
        val sell = rows.first { it.ibkrText("action") == "卖" && it.ibkrText("code") == "GOOG" }
        val b = IbkrImporter.event(buy, "IBKR", "USD"); val s = IbkrImporter.event(sell, "IBKR", "USD")
        for ((key, expected) in mapOf("from_ticker" to "usd", "from_amount" to "5478.28", "to_ticker" to "sndk.us", "to_amount" to "4", "commission" to "1.000012", "commission_asset" to "usd")) assertEquals(expected, b.ibkrText(key))
        for ((key, expected) in mapOf("from_ticker" to "goog.us", "from_amount" to "6", "to_ticker" to "usd", "to_amount" to "2092.77", "commission" to "1.044299062", "commission_asset" to "usd")) assertEquals(expected, s.ibkrText(key))
        assertEquals("ibkr:20260717:买:SNDK:4:-5479.280012:-1.000012", IbkrImporter.identity(buy))
    }
    @Test fun nonEquityCashActionsKeepLockedTypes() {
        val rows = statement().transactions
        for ((action, values) in mapOf("存款" to listOf("funding", "external", "to_amount", "4757"), "股息" to listOf("income", "dividend_cash", "to_amount", "0.45"), "外国预扣税" to listOf("expense", "tax", "from_amount", "0.14"), "借方利息" to listOf("expense", "interest", "from_amount", "0.34"))) {
            val e = IbkrImporter.event(rows.first { it.ibkrText("action") == action }, "IBKR", "USD")
            assertEquals(values[0] to values[1], e.ibkrText("record_type") to e.ibkrText("record_subtype")); assertEquals(values[3], e.ibkrText(values[2]))
        }
    }
    @Test fun fxUsesNetCashWithoutNominalHkd() {
        val rows = statement().transactions.filter { it.ibkrText("action") == "外汇交易组成部分" }; val events = rows.map { IbkrImporter.event(it, "IBKR", "USD") }
        for (e in events) { assertFalse("hkd" in listOf(e.ibkrText("from_ticker"), e.ibkrText("to_ticker"))); assertEquals("adjustment" to "fx_net", e.ibkrText("record_type") to e.ibkrText("record_subtype")) }
        assertEquals("adjustment", events[0].ibkrText("record_type")); assertEquals("usd", events[0].ibkrText("to_ticker")); assertEquals(0, "2.7556650000065686E-7".toBigDecimal().setScale(18, java.math.RoundingMode.HALF_EVEN).abs().compareTo(events[0].ibkrText("to_amount").toBigDecimal()))
        assertEquals("adjustment", events[1].ibkrText("record_type")); assertEquals("usd", events[1].ibkrText("from_ticker")); assertEquals(0, "2.030467550750018".toBigDecimal().compareTo(events[1].ibkrText("from_amount").toBigDecimal()))
        assertEquals(rows[1]["note"], events[1]["note"]); assertFalse("佣金" in events[1].ibkrText("note"))
    }
    @Test fun summarySnapshotHasNoDerivedNote() {
        val s = statement(); val e = IbkrImporter.event(s.transactions.first { it.ibkrText("action") == "CHECKIN" }, "IBKR", s.baseCurrency); assertEquals("", e.ibkrText("note"))
    }
    @Test fun offlineReplayReconcilesCashAndOpenShares() {
        val s = statement(); val doc = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        for (row in s.transactions) InvestmentProjection.applyEvent(doc, IbkrImporter.event(row, "IBKR", s.baseCurrency), s.baseCurrency)
        val p = doc.value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("IBKR").jsonObject.getValue("positions").jsonObject
        fun quantity(ticker: String) = p.getValue(ticker).jsonObject.ibkrText("shares").toBigDecimal()
        assertEquals(0, quantity("usd").compareTo(s.endingCash.toBigDecimal()))
        assertEquals(mapOf("avgo.us" to "5", "ko.us" to "30", "nvda.us" to "25", "sndk.us" to "4", "tsm.us" to "20"), listOf("avgo.us", "ko.us", "nvda.us", "sndk.us", "tsm.us").associateWith { quantity(it).stripTrailingZeros().toPlainString() })
        assertTrue("hkd" !in p || quantity("hkd").signum() == 0)
    }
}
