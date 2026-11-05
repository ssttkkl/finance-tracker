package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class InvestmentValidationOriginalTest {
    private fun snapshot(shares: String, cost: String, type: String = "security", account: String = "broker", ticker: String = "tsla") = buildJsonObject { put("accounts", buildJsonObject { put(type, buildJsonObject { put(account, buildJsonObject { put("currency", "USD"); put("positions", buildJsonObject { put(ticker, buildJsonObject { put("shares", shares); put("total_cost", cost); put("cost_currency", "USD") }) }) }) }) }) }
    private fun reject(shares: String, cost: String, field: String, type: String = "security") { assertContains(assertFailsWith<IllegalArgumentException> { validateInvestmentSnapshot(snapshot(shares, cost, type)) }.message!!, "non-finite $field") }
    @Test fun test_validate_accepts_finite_positive_values() { validateInvestmentSnapshot(Json.parseToJsonElement("""{"accounts":{"security":{"东方证券":{"currency":"CNY","positions":{"cny":{"shares":"10000.00","total_cost":"10000.00","cost_currency":"CNY"},"600000.sh":{"shares":"100","total_cost":"1250.00","cost_currency":"CNY"}}}}}}""").jsonObject) }
    @Test fun test_validate_accepts_negative_shares() { validateInvestmentSnapshot(snapshot("-10", "-2500.00")) }
    @Test fun test_validate_accepts_zero_values() { validateInvestmentSnapshot(snapshot("0", "0")) }
    @Test fun test_validate_rejects_nan_shares() { reject("NaN", "2500.00", "shares") }
    @Test fun test_validate_rejects_infinity_shares() { reject("Infinity", "2500.00", "shares") }
    @Test fun test_validate_rejects_negative_infinity_shares() { reject("-Infinity", "2500.00", "shares") }
    @Test fun test_validate_rejects_nan_total_cost() { reject("10", "NaN", "total_cost") }
    @Test fun test_validate_rejects_infinity_total_cost() { reject("10", "Infinity", "total_cost") }
    @Test fun test_validate_empty_snapshot() { validateInvestmentSnapshot(Json.parseToJsonElement("""{"accounts":{}}""").jsonObject) }
    @Test fun test_validate_crypto_accounts() { validateInvestmentSnapshot(Json.parseToJsonElement("""{"accounts":{"crypto":{"binance":{"currency":"USDT","positions":{"btc":{"shares":"0.12345678","total_cost":"5000.00","cost_currency":"USDT"},"usdt":{"shares":"10000.00","total_cost":"10000.00","cost_currency":"USDT"}}}}}}""").jsonObject) }
    @Test fun test_validate_crypto_rejects_nan() { reject("NaN", "5000.00", "shares", "crypto") }
    @Test fun test_validate_includes_account_and_ticker_in_error() { val message = assertFailsWith<IllegalArgumentException> { validateInvestmentSnapshot(snapshot("NaN", "1250.00", account = "东方证券", ticker = "600000.sh")) }.message!!; assertContains(message, "600000.sh"); assertContains(message, "东方证券") }
    @Test fun test_validate_rejects_infinity_total_cost_with_field_name() { reject("10", "Infinity", "total_cost") }
}
