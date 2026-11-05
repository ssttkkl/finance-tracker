package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class DfzqOriginalTest {
    @Test fun originalStatementAmountScaleOverEighteenRejects() {
        val row = Json.parseToJsonElement("""{"date":"2026-07-01","action":"DEPOSIT","ticker":"","amount":"0.1234567890123456789","shares":"0","price":"0","fee":"0","note":""}""").jsonObject
        val failure = assertFailsWith<IllegalArgumentException> { DfzqImporter.event(row, "东方证券", "CNY") }
        assertTrue("最多保留 18 位小数" in failure.message.orEmpty())
    }

    private fun verify(name: String) {
        val root = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_DFZQ_CONTRACT"))).readText()).jsonObject
        val calls = root.getValue("originals").jsonObject.getValue(name).jsonArray
        assertTrue(calls.isNotEmpty())
        val parsed = mutableMapOf<Int, List<DfzqRow>>()
        for ((index, raw) in calls.withIndex()) {
            val call = raw.jsonObject
            fun row() = call["parse_call"]?.jsonPrimitive?.int?.let { parsed.getValue(it)[call.getValue("row_index").jsonPrimitive.int] }
                ?: DfzqRow(call.getValue("row").jsonObject, call.getValue("decimal_fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
            val actual = when (call.ibkrText("op")) {
                "parse" -> JsonArray(DfzqImporter.parse(call.getValue("lines").jsonArray.map { it.jsonPrimitive.content }).also { parsed[index] = it }.map { JsonObject(it) })
                "identity" -> JsonPrimitive(DfzqImporter.identity(row()))
                "event" -> DfzqImporter.event(row(), call.ibkrText("account"), call.ibkrText("currency"))
                else -> error("Unknown original call")
            }
            assertEquals(call["expected"], actual, "$name call $index")
            if (name == "test_map_dfzq_trade_splits_all_recognized_fees") {
                val event = actual.jsonObject; val incoming = row().ibkrText("action") == "SELL"
                val cash = event.ibkrText(if (incoming) "to_amount" else "from_amount").toBigDecimal()
                val commission = event.ibkrText("commission").toBigDecimal()
                assertEquals(0, (if (incoming) cash - commission else cash + commission).compareTo(row().ibkrText("amount").toBigDecimal()))
            }
        }
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_sample_statement() = verify("test_parse_dfzq_sample_statement")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_empty_statement() = verify("test_parse_dfzq_empty_statement")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_handles_multiple_pages() = verify("test_parse_dfzq_handles_multiple_pages")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_handles_summary_section() = verify("test_parse_dfzq_handles_summary_section")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_constructs_ticker_suffix() = verify("test_parse_dfzq_constructs_ticker_suffix")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_handles_otc_securities() = verify("test_parse_dfzq_handles_otc_securities")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_keeps_source_action_in_note() = verify("test_parse_dfzq_keeps_source_action_in_note")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_sorts_by_date() = verify("test_parse_dfzq_sorts_by_date")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_checkin_uses_last_balance() = verify("test_parse_dfzq_checkin_uses_last_balance")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_parse_dfzq_handles_repo_transactions() = verify("test_parse_dfzq_handles_repo_transactions")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_construct_source_identity_buy() = verify("test_construct_source_identity_buy")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_construct_source_identity_deposit() = verify("test_construct_source_identity_deposit")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_buy_to_trade() = verify("test_map_dfzq_buy_to_trade")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_sell_to_trade() = verify("test_map_dfzq_sell_to_trade")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_trade_splits_all_recognized_fees() = verify("test_map_dfzq_trade_splits_all_recognized_fees")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_buy_fee_not_separable_keeps_net() = verify("test_map_dfzq_buy_fee_not_separable_keeps_net")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_buy_zero_fee() = verify("test_map_dfzq_buy_zero_fee")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_deposit() = verify("test_map_dfzq_deposit")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_cash_dividend() = verify("test_map_dfzq_cash_dividend")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_stock_dividend() = verify("test_map_dfzq_stock_dividend")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_checkin() = verify("test_map_dfzq_checkin")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_integration_with_parser() = verify("test_map_dfzq_integration_with_parser")
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun test_map_dfzq_preserves_native_action_for_funding_income_and_tax() = verify("test_map_dfzq_preserves_native_action_for_funding_income_and_tax")
}
