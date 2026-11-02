package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class UsmartOriginalTest {
    private fun oracle() = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_USMART_CONTRACT"))).readText()).jsonObject
    private fun verify(name: String) {
        val calls = oracle().getValue("originals").jsonObject.getValue(name).jsonArray
        val parsed = mutableMapOf<Int, List<UsmartRow>>()
        assertTrue(calls.isNotEmpty())
        for ((index, raw) in calls.withIndex()) {
            val call = raw.jsonObject
            fun row() = call["parse_call"]?.jsonPrimitive?.int?.let { val r = parsed.getValue(it)[call.getValue("row_index").jsonPrimitive.int]; UsmartRow(JsonObject(r + (call["patch"]?.jsonObject ?: emptyMap())), r.decimalFields) }
                ?: UsmartRow(call.getValue("row").jsonObject, call.getValue("decimal_fields").jsonArray.map { it.jsonPrimitive.content }.toSet())
            fun operation(): JsonElement = when (call.ibkrText("op")) {
                "parse" -> JsonArray(UsmartImporter.parse(call.ibkrText("text")).also { parsed[index] = it }.map { JsonObject(it) })
                "holdings" -> JsonArray(UsmartImporter.holdings(call.ibkrText("text"), call.ibkrText("original"), call.ibkrText("period"), call.ibkrText("end")).also { parsed[index] = it }.map { JsonObject(it) })
                "profile" -> JsonPrimitive(UsmartImporter.profile(call.ibkrText("text")))
                "identity" -> JsonPrimitive(UsmartImporter.identity(row()))
                "event" -> UsmartImporter.event(row(), call.ibkrText("account"), call["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                else -> error("Unknown original operation")
            }
            val expected = call.getValue("expected").jsonObject
            if ("value" in expected) assertEquals(expected["value"], operation(), "$name call $index")
            else { val failure = assertFailsWith<IllegalArgumentException> { operation() }; assertEquals(expected.ibkrText("message"), failure.message, "$name call $index") }
        }
    }
    @Test fun test_statement_profile_uses_day_trading_statement_layout_not_filename() = verify("test_statement_profile_uses_day_trading_statement_layout_not_filename")
    @Test fun test_trade_groups_merge_fills_and_put_fee_outside_gross_cash_leg() = verify("test_trade_groups_merge_fills_and_put_fee_outside_gross_cash_leg")
    @Test fun test_trade_and_checkin_identities_are_stable_and_distinct() = verify("test_trade_and_checkin_identities_are_stable_and_distinct")
    @Test fun test_cash_identity_uses_source_snapshot_not_output_note() = verify("test_cash_identity_uses_source_snapshot_not_output_note")
    @Test fun test_empty_columns_holdings_and_cash_checkins_use_native_currency() = verify("test_empty_columns_holdings_and_cash_checkins_use_native_currency")
    @Test fun test_vertical_holdings_keep_original_source_units() = verify("test_vertical_holdings_keep_original_source_units")
    @Test fun test_traded_but_absent_from_holdings_gets_zero_share_checkin() = verify("test_traded_but_absent_from_holdings_gets_zero_share_checkin")
    @Test fun test_cash_flags_ignore_trade_mirrors_and_map_non_trade_rows() = verify("test_cash_flags_ignore_trade_mirrors_and_map_non_trade_rows")
    @Test fun test_fx_rows_pair_to_one_swap_and_unpaired_fails_closed() = verify("test_fx_rows_pair_to_one_swap_and_unpaired_fails_closed")
    @Test fun test_transfer_by_sign_is_not_a_transfer_action_and_unknown_flag_fails_closed() = verify("test_transfer_by_sign_is_not_a_transfer_action_and_unknown_flag_fails_closed")
    @Test fun test_cash_fee_and_dividend_actions() = verify("test_cash_fee_and_dividend_actions")
    @Test fun test_tax_refund_maps_to_expense_reversal_not_funding() = verify("test_tax_refund_maps_to_expense_reversal_not_funding")
    @Test fun test_explicit_cash_flags_use_fee_semantics_instead_of_external_funding() = verify("test_explicit_cash_flags_use_fee_semantics_instead_of_external_funding")
    @Test fun fullSampleRowsIdentitiesEventsAndEachSnapshot() {
        val o = oracle(); val rows = UsmartImporter.parse(o.ibkrText("text"))
        assertEquals(o["rows"], JsonArray(rows.map { JsonObject(it) }))
        val document = CashSnapshotDocument(Json.parseToJsonElement("""{"accounts":{"security":{}}}""").jsonObject)
        rows.forEachIndexed { i, row ->
            val expected = o.getValue("mapped").jsonArray[i].jsonObject
            assertEquals(expected.ibkrText("identity"), UsmartImporter.identity(row), "identity $i")
            val event = UsmartImporter.event(row, "盈立证券")
            assertEquals(expected["event"], event, "event $i")
            InvestmentProjection.applyEvent(document, event, "USD")
            assertEquals(o.getValue("snapshots").jsonArray[i], document.value, "snapshot $i")
        }
        val trade = rows.first { it["kind"] == JsonPrimitive("trade") && it["ticker"] == JsonPrimitive("dell.us") }
        val event = UsmartImporter.event(trade, "盈立证券")
        assertEquals(trade.getValue("net").jsonPrimitive.content.toBigDecimal(), event.ibkrText("to_amount").toBigDecimal() - event.ibkrText("commission").toBigDecimal())
        val cash = rows.first { it["kind"] == JsonPrimitive("cash") }
        assertEquals(UsmartImporter.identity(cash), UsmartImporter.identity(UsmartRow(JsonObject(cash + ("note" to JsonPrimitive("仅用于展示的改写文案"))), cash.decimalFields)))
    }
}
