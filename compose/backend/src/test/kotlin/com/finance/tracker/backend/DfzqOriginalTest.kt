package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class DfzqOriginalTest {
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
    @Test fun test_parse_dfzq_sample_statement() = verify("test_parse_dfzq_sample_statement")
    @Test fun test_parse_dfzq_empty_statement() = verify("test_parse_dfzq_empty_statement")
    @Test fun test_parse_dfzq_handles_multiple_pages() = verify("test_parse_dfzq_handles_multiple_pages")
    @Test fun test_parse_dfzq_handles_summary_section() = verify("test_parse_dfzq_handles_summary_section")
    @Test fun test_parse_dfzq_constructs_ticker_suffix() = verify("test_parse_dfzq_constructs_ticker_suffix")
    @Test fun test_parse_dfzq_handles_otc_securities() = verify("test_parse_dfzq_handles_otc_securities")
    @Test fun test_parse_dfzq_keeps_source_action_in_note() = verify("test_parse_dfzq_keeps_source_action_in_note")
    @Test fun test_parse_dfzq_sorts_by_date() = verify("test_parse_dfzq_sorts_by_date")
    @Test fun test_parse_dfzq_checkin_uses_last_balance() = verify("test_parse_dfzq_checkin_uses_last_balance")
    @Test fun test_parse_dfzq_handles_repo_transactions() = verify("test_parse_dfzq_handles_repo_transactions")
    @Test fun test_construct_source_identity_buy() = verify("test_construct_source_identity_buy")
    @Test fun test_construct_source_identity_deposit() = verify("test_construct_source_identity_deposit")
    @Test fun test_map_dfzq_buy_to_trade() = verify("test_map_dfzq_buy_to_trade")
    @Test fun test_map_dfzq_sell_to_trade() = verify("test_map_dfzq_sell_to_trade")
    @Test fun test_map_dfzq_trade_splits_all_recognized_fees() = verify("test_map_dfzq_trade_splits_all_recognized_fees")
    @Test fun test_map_dfzq_buy_fee_not_separable_keeps_net() = verify("test_map_dfzq_buy_fee_not_separable_keeps_net")
    @Test fun test_map_dfzq_buy_zero_fee() = verify("test_map_dfzq_buy_zero_fee")
    @Test fun test_map_dfzq_deposit() = verify("test_map_dfzq_deposit")
    @Test fun test_map_dfzq_cash_dividend() = verify("test_map_dfzq_cash_dividend")
    @Test fun test_map_dfzq_stock_dividend() = verify("test_map_dfzq_stock_dividend")
    @Test fun test_map_dfzq_checkin() = verify("test_map_dfzq_checkin")
    @Test fun test_map_dfzq_integration_with_parser() = verify("test_map_dfzq_integration_with_parser")
    @Test fun test_map_dfzq_preserves_native_action_for_funding_income_and_tax() = verify("test_map_dfzq_preserves_native_action_for_funding_income_and_tax")
}
