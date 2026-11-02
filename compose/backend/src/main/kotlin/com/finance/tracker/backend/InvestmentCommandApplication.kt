package com.finance.tracker.backend

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject

internal data class InvestmentCommand(val recordType: String, val account: String, val currency: String? = null, val ticker: String = "",
    val quantity: CashAmount? = null, val price: CashAmount? = null, val commission: CashAmount = exactCashAmount("0"), val commissionAsset: String = "",
    val fromTicker: String = "", val toTicker: String = "", val toQuantity: CashAmount? = null, val amount: CashAmount? = null, val note: String = "", val date: String? = null)
internal fun investmentUtcNow(): String {
    val value = Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC)
    val seconds = value.toLocalDateTime().withNano(0).toString().let { if (it.length == 16) "$it:00" else it }
    return seconds + (if (value.nano == 0) "" else "." + (value.nano / 1000).toString().padStart(6, '0')) + "+00:00"
}
internal class InvestmentCommandApplication(private val execute: (InvestmentCommand) -> JsonObject) {
    fun buy(ticker: String, shares: String, price: String, commission: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("buy", account, currency, ticker, exactCashAmount(shares, "shares"), exactCashAmount(price, "price"), exactCashAmount(commission, "commission"), note = note, date = date))
    fun sell(ticker: String, shares: String, price: String, commission: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("sell", account, currency, ticker, exactCashAmount(shares, "shares"), exactCashAmount(price, "price"), exactCashAmount(commission, "commission"), note = note, date = date))
    fun swap(account: String, fromTicker: String, fromQuantity: String, toTicker: String, toQuantity: String, currency: String? = null, note: String = "", date: String? = null, commission: String? = "0", commissionAsset: String? = ""): JsonObject {
        val fee = exactCashAmount(commission?.takeIf { it.isNotEmpty() } ?: "0", "commission")
        val asset = pythonCashLower(valuationStrip(commissionAsset.orEmpty())).ifEmpty { if (fee != exactCashAmount("0")) pythonCashLower(valuationStrip(fromTicker)) else "" }
        return execute(InvestmentCommand("swap", account, currency, fromTicker = fromTicker, quantity = exactCashAmount(fromQuantity, "from_quantity"), toTicker = toTicker, toQuantity = exactCashAmount(toQuantity, "to_quantity"), commission = fee, commissionAsset = asset, note = note, date = date))
    }
    fun deposit(amount: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("deposit", account, currency, amount = exactCashAmount(amount, "amount"), note = note, date = date))
    fun withdraw(amount: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("withdraw", account, currency, amount = exactCashAmount(amount, "amount"), note = note, date = date))
    fun dividend(ticker: String, amount: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("dividend", account, currency, ticker, amount = exactCashAmount(amount, "amount"), note = note, date = date))
    fun checkinTicker(ticker: String, shares: String, averageCost: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("checkin_ticker", account, currency, ticker, quantity = exactCashAmount(shares, "shares"), price = exactCashAmount(averageCost, "avg_cost"), note = note, date = date))
    fun checkinCash(cash: String, currency: String?, account: String, note: String = "", date: String? = null) = execute(InvestmentCommand("checkin_cash", account, currency, amount = exactCashAmount(cash, "cash"), note = note, date = date))
}
