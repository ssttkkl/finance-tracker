package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

internal class InvestmentReplayInput(name: String) {
    private val input = inputs.getValue(name).jsonObject
    val documents = input.getValue("snapshots").jsonArray.map { CashSnapshotDocument(it.jsonObject) }
    private val calls = input.getValue("calls").jsonArray
    private var index = 0
    fun step() {
        val call = calls[index++].jsonObject
        InvestmentProjection.applyEvent(documents[call.getValue("snapshot").jsonPrimitive.int], call.getValue("row").jsonObject,
            call.getValue("default_currency").jsonPrimitive.content,
            call["base_tickers"]?.takeUnless { it == JsonNull }?.jsonArray?.map { it.jsonPrimitive.content })
    }
    fun all(): InvestmentReplayInput { while (index < calls.size) step(); return this }
    fun positions(account: String = "broker", document: Int = 0) = documents[document].value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue(account).jsonObject.getValue("positions").jsonObject
    fun text(ticker: String, field: String = "shares", account: String = "broker", document: Int = 0) = positions(account, document).getValue(ticker).jsonObject.getValue(field).jsonPrimitive.content
    fun money(expected: String, ticker: String, field: String = "shares", account: String = "broker", document: Int = 0) { assertEquals(0, expected.toBigDecimal().compareTo(text(ticker, field, account, document).toBigDecimal())) }
    companion object {
        private val inputs by lazy {
            val value = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_CORE_INPUTS"))).readText()).jsonObject
            assertEquals(JsonPrimitive(true), value["inputs_only"]); value.getValue("cases").jsonObject.also { assertEquals(31, it.size) }
        }
    }
}

class InvestmentReplayOriginalTest {
    private fun replay(name: String) = InvestmentReplayInput(name).all()
    @Test fun test_cash_to_cash_swap_keeps_each_fiat_pocket_in_its_native_cost_currency() {
        val f = replay("test_cash_to_cash_swap_keeps_each_fiat_pocket_in_its_native_cost_currency")
        f.money("2020.56", "hkd", account = "盈立证券"); f.money("2020.56", "hkd", "total_cost", "盈立证券"); assertEquals("HKD", f.text("hkd", "cost_currency", "盈立证券"))
        f.money("412.32", "usd", account = "盈立证券"); f.money("412.32", "usd", "total_cost", "盈立证券"); assertEquals("USD", f.text("usd", "cost_currency", "盈立证券"))
    }
    @Test fun test_swap_buy_cash_to_ticker() { val f = replay("test_swap_buy_cash_to_ticker"); f.money("8748.00", "cny"); f.money("100", "600000.sh"); f.money("1252.00", "600000.sh", "total_cost") }
    @Test fun test_swap_sell_ticker_to_cash() { val f = replay("test_swap_sell_ticker_to_cash"); f.money("50", "600000.sh"); f.money("625.50", "600000.sh", "total_cost"); f.money("9398.00", "cny") }
    @Test fun test_swap_crypto_to_crypto() { val f = replay("test_swap_crypto_to_crypto"); f.money("0.05", "btc", account = "binance"); f.money("2500.00", "btc", "total_cost", "binance"); f.money("1.5", "eth", account = "binance"); f.money("2500.00", "eth", "total_cost", "binance") }
    @Test fun test_swap_with_third_party_commission() { val f = replay("test_swap_with_third_party_commission"); f.money("0", "usdt", account = "binance"); f.money("0.1", "btc", account = "binance"); f.money("5000.00", "btc", "total_cost", "binance"); f.money("9.99", "bnb", account = "binance"); f.money("2999.99", "bnb", "total_cost", "binance") }
    @Test fun test_external_funding_increases_cash() { val f = replay("test_external_funding_increases_cash"); f.money("11000.00", "cny"); f.money("11000.00", "cny", "total_cost") }
    @Test fun test_external_funding_withdrawal_decreases_cash() { val f = replay("test_external_funding_withdrawal_decreases_cash"); f.money("5000.00", "cny"); f.money("5000.00", "cny", "total_cost") }
    @Test fun test_cash_dividend_increases_cash_no_cost() { val f = replay("test_cash_dividend_increases_cash_no_cost"); f.money("8869.00", "cny"); f.money("8869.00", "cny", "total_cost"); f.money("100", "600000.sh"); f.money("1251.00", "600000.sh", "total_cost") }
    @Test fun test_position_snapshot_replaces_position() { val f = replay("test_position_snapshot_replaces_position"); f.money("100", "600000.sh"); f.money("1250.00", "600000.sh", "total_cost") }
    @Test fun test_cash_snapshot_replaces_cash_balance() { val f = replay("test_cash_snapshot_replaces_cash_balance"); f.money("9000.00", "cny"); f.money("9000.00", "cny", "total_cost") }
    @Test fun test_event_creates_account_if_missing() { val f = replay("test_event_creates_account_if_missing"); val accounts = f.documents[0].value.getValue("accounts").jsonObject; assertTrue("security" in accounts); assertTrue("new_broker" in accounts.getValue("security").jsonObject); f.money("1000.00", "usd", account = "new_broker") }
    @Test fun test_swap_allows_sell_without_prior_position() { replay("test_swap_allows_sell_without_prior_position").money("-50", "600000.sh") }
    @Test fun test_swap_zero_shares_and_zero_cost_edge() { val f = replay("test_swap_zero_shares_and_zero_cost_edge"); f.money("0", "600000.sh"); f.money("0", "cny") }
    @Test fun test_external_funding_creates_missing_cash_position() { replay("test_external_funding_creates_missing_cash_position").money("100", "usd") }
    @Test fun test_subaccount_funding_changes_the_current_investment_account() { assertEquals("80", replay("test_subaccount_funding_changes_the_current_investment_account").text("usd", account = "exchange")) }
    @Test fun test_historical_investment_record_types_are_rejected() { for (type in listOf("deposit", "withdraw", "swap", "dividend", "fee", "checkin")) assertContains(assertFailsWith<IllegalArgumentException> { replay("test_historical_investment_record_types_are_rejected[$type]") }.message!!, "unsupported investment record_type") }
    @Test fun test_invalid_record_subtype_is_rejected_without_mutating_snapshot() { val f = InvestmentReplayInput("test_invalid_record_subtype_is_rejected_without_mutating_snapshot"); val before = f.documents[0].value; assertContains(assertFailsWith<IllegalArgumentException> { f.all() }.message!!, "invalid investment record_subtype"); assertEquals(before, f.documents[0].value) }
    @Test fun test_normalize_empty_uses_default_including_stables() { val bases = normalizeInvestmentBaseTickers(null); assertTrue("usd" in bases && "usdt" in bases && bases === defaultInvestmentBaseTickers || "usdt" in bases) }
    @Test fun test_hkd_usd_fx_with_account_bases_no_conflict() { val f = replay("test_hkd_usd_fx_with_account_bases_no_conflict"); f.money("1838.82", "hkd", account = "盈立"); f.money("1838.82", "hkd", "total_cost", "盈立"); assertEquals("HKD", f.text("hkd", "cost_currency", "盈立")); f.money("402.32", "usd", account = "盈立"); f.money("402.32", "usd", "total_cost", "盈立"); assertEquals("USD", f.text("usd", "cost_currency", "盈立")) }
    @Test fun test_equity_buy_accumulates_cost_in_event_currency() { val f = replay("test_equity_buy_accumulates_cost_in_event_currency"); f.money("10", "mrvl.us", account = "IBKR"); f.money("1994.32", "mrvl.us", "total_cost", "IBKR"); assertEquals("USD", f.text("mrvl.us", "cost_currency", "IBKR")); val expected = "10000".toBigDecimal() - "1990.40".toBigDecimal() - "3.92".toBigDecimal(); f.money(expected.toPlainString(), "usd", account = "IBKR"); f.money(f.text("usd", account = "IBKR"), "usd", "total_cost", "IBKR") }
    @Test fun test_usdt_base_crypto_buy() { val f = replay("test_usdt_base_crypto_buy"); f.money("0", "usdt", account = "Binance"); f.money("0.1", "btc", account = "Binance"); f.money("5000", "btc", "total_cost", "Binance"); assertEquals("USDT", f.text("btc", "cost_currency", "Binance")) }
    @Test fun test_expense_reduces_base_cash_like_withdrawal() { replay("test_expense_reduces_base_cash_like_withdrawal").money("99.88", "usd", account = "A") }
    @Test fun test_dividend_increases_cash() { replay("test_dividend_increases_cash").money("12.77", "usd", account = "A") }
    @Test fun test_expense_reversal_increases_cash() { replay("test_expense_reversal_increases_cash").money("10.27", "usd", account = "A") }
    @Test fun test_ipo_debit_and_refund_are_signed_cash_movements() { val f = InvestmentReplayInput("test_ipo_debit_and_refund_are_signed_cash_movements"); f.step(); f.money("4818.26", "hkd", account = "A"); f.step(); f.money("10000", "hkd", account = "A") }
    @Test fun test_investment_projection_deducts_commission_from_cash_settlement_and_rejects_cost_currency_mix() { val f = InvestmentReplayInput("test_investment_projection_deducts_commission_from_cash_settlement_and_rejects_cost_currency_mix"); f.step(); assertEquals("718", f.text("usd", account = "IBKR")); assertContains(assertFailsWith<IllegalArgumentException> { f.step() }.message!!, "cost currency") }
    @Test fun test_investment_projection_soft_start_oversell_and_numeric_overflow() { val f = InvestmentReplayInput("test_investment_projection_soft_start_oversell_and_numeric_overflow"); f.step(); f.money("-1", "aapl", account = "IBKR"); f.money("40", "usd", account = "IBKR"); assertContains(assertFailsWith<IllegalArgumentException> { f.step() }.message!!, "NUMERIC(38,18)") }
    @Test fun test_investment_record_type_subtype_combinations_fail_closed() { for ((type, subtype) in listOf("funding" to "tax", "expense" to "external", "reversal" to "reward", "snapshot" to "interest")) { val message = assertFailsWith<IllegalArgumentException> { validateInvestmentRecordSubtype(type, subtype) }.message!!; assertTrue("record_type" in message || "record_subtype" in message) } }
    @Test fun test_investment_economic_fact_type_subtype_combinations_are_accepted() { for (pair in listOf("funding" to "external", "funding" to "subaccount", "trade" to "security", "income" to "interest", "expense" to "tax", "reversal" to "funding_withdrawal", "reversal" to "expense_handling_fee", "reversal" to "expense_penalty", "subscription" to "ipo_refund", "adjustment" to "fx_net", "snapshot" to "position")) assertEquals(pair, validateInvestmentRecordSubtype(pair.first, pair.second)) }
}
