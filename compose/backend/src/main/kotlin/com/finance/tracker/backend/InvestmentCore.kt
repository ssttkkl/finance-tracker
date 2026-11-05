package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal val defaultInvestmentBaseTickers = setOf("usd", "hkd", "cny", "eur", "gbp", "jpy", "aud", "cad", "chf", "nzd", "sgd", "usdt", "usdc")
internal fun normalizeInvestmentBaseTickers(codes: Iterable<String>?): Set<String> = codes?.map { pythonCashLower(valuationStrip(it)) }?.filter { it.isNotEmpty() }?.toSet()?.takeIf { it.isNotEmpty() } ?: defaultInvestmentBaseTickers
private val investmentSubtypes = mapOf("funding" to listOf("external", "subaccount"), "trade" to listOf("security", "fx", "repo"),
    "income" to listOf("dividend_cash", "dividend_stock", "interest", "reward"), "expense" to listOf("commission", "tax", "interest", "handling_fee", "penalty"),
    "reversal" to listOf("expense_tax", "expense_interest", "expense_commission", "expense_handling_fee", "expense_penalty", "funding_withdrawal"),
    "subscription" to listOf("ipo_debit", "ipo_refund"), "adjustment" to listOf("fx_net", "manual", "unclassified"), "snapshot" to listOf("cash", "position"))
private fun investmentText(value: JsonElement?): String = if (value == null || !pythonTruthy(value)) "" else pythonString(value)
private fun investmentRepr(value: JsonElement?): String = (value as? JsonPrimitive)?.takeIf { it.isString }?.let { pythonStringRepr(it.content) } ?: pythonString(value ?: JsonNull)
internal fun validateInvestmentRecordSubtype(type: String, subtype: String) = validateInvestmentRecordSubtype(JsonPrimitive(type), JsonPrimitive(subtype))
internal fun validateInvestmentRecordSubtype(type: JsonElement?, subtype: JsonElement?): Pair<String, String> {
    val t = pythonCashLower(valuationStrip(investmentText(type))); val s = pythonCashLower(valuationStrip(investmentText(subtype)))
    val allowed = investmentSubtypes[t] ?: throw IllegalArgumentException("unsupported investment record_type: ${pythonString(type ?: JsonNull)}")
    require(s in allowed) { "invalid investment record_subtype ${investmentRepr(subtype)} for record_type ${investmentRepr(type)}" }
    return t to s
}
internal fun defaultInvestmentRecordSubtype(type: JsonElement?): String {
    val t = pythonCashLower(valuationStrip(investmentText(type)))
    return mapOf("funding" to "external", "trade" to "security", "income" to "dividend_cash", "expense" to "commission", "reversal" to "expense_commission", "subscription" to "ipo_debit", "adjustment" to "manual", "snapshot" to "cash")[t] ?: throw IllegalArgumentException("unsupported investment record_type: ${pythonString(type ?: JsonNull)}")
}
internal fun normalizeInvestmentRecordSemantics(type: JsonElement?, subtype: JsonElement? = null): Pair<String, String> {
    val t = JsonPrimitive(pythonCashLower(valuationStrip(investmentText(type))))
    val s = if (subtype == null || subtype == JsonNull || subtype == JsonPrimitive("")) JsonPrimitive(defaultInvestmentRecordSubtype(t)) else JsonPrimitive(pythonCashLower(valuationStrip(pythonString(subtype))))
    return validateInvestmentRecordSubtype(t, s)
}

internal class InvestmentDecimal private constructor(val number: BigDecimal, private val negativeZero: Boolean = false) : Comparable<InvestmentDecimal> {
    private val negative get() = number.signum() < 0 || number.signum() == 0 && negativeZero
    override fun compareTo(other: InvestmentDecimal) = number.compareTo(other.number)
    fun zero() = number.signum() == 0
    fun plus(other: InvestmentDecimal, precision: Int = 80): InvestmentDecimal {
        val result = number.add(other.number, MathContext(precision, RoundingMode.HALF_EVEN))
        return InvestmentDecimal(result, result.signum() == 0 && negative && other.negative)
    }
    fun minus(other: InvestmentDecimal, precision: Int = 80) = plus(InvestmentDecimal(other.number.negate(), !other.negative), precision)
    fun times(other: InvestmentDecimal, precision: Int = 80): InvestmentDecimal {
        val result = number.multiply(other.number, MathContext(precision, RoundingMode.HALF_EVEN))
        return InvestmentDecimal(result, result.signum() == 0 && negative != other.negative)
    }
    fun divide(other: InvestmentDecimal, precision: Int): InvestmentDecimal {
        val result = number.divide(other.number, MathContext(precision, RoundingMode.HALF_EVEN))
        return InvestmentDecimal(result, result.signum() == 0 && negative != other.negative)
    }
    fun releasedCost(other: InvestmentDecimal): InvestmentDecimal {
        val result = divide(other, 40)
        return InvestmentDecimal(result.number.setScale(18, RoundingMode.HALF_UP), result.negative)
    }
    fun rawText() = (if (number.signum() == 0 && negativeZero) "-" else "") + number.toString()
    fun fixed(): String { exactCashAmount(rawText(), "projected value"); return (if (number.signum() == 0 && negativeZero) "-" else "") + number.toPlainString() }
    companion object {
        val ZERO = InvestmentDecimal(BigDecimal.ZERO)
        fun exact(value: String, field: String): InvestmentDecimal { val parsed = exactCashAmount(value, field); return InvestmentDecimal(parsed.arithmeticValue(), parsed.representation.negative) }
        fun exact(value: JsonElement?, field: String, default: Boolean = false): InvestmentDecimal = exact(if (default && (value == null || value == JsonNull)) "0" else pythonString(value ?: JsonNull), field)
        fun raw(value: BigDecimal) = InvestmentDecimal(value)
    }
}

private class InvestmentObject(value: JsonObject = JsonObject(emptyMap())) {
    private val values = value.mapValuesTo(linkedMapOf<String, Any>()) { (_, v) -> if (v is JsonObject) InvestmentObject(v) else v }
    operator fun get(key: String): JsonElement? = when (val v = values[key]) { is InvestmentObject -> v.json(); else -> v as JsonElement? }
    operator fun set(key: String, value: String) { values[key] = JsonPrimitive(value) }
    operator fun set(key: String, value: JsonElement) { values[key] = value }
    fun objectDefault(key: String, default: JsonObject = JsonObject(emptyMap())): InvestmentObject {
        if (key !in values) values[key] = InvestmentObject(default)
        return values[key] as? InvestmentObject ?: error("investment snapshot must be a mapping")
    }
    fun json(): JsonObject = JsonObject(values.mapValues { (_, v) -> if (v is InvestmentObject) v.json() else v as JsonElement })
}

internal object InvestmentProjection {
    private fun isBase(ticker: String, bases: Set<String>) = pythonCashLower(valuationStrip(ticker)) in bases
    private fun costCurrency(ticker: String, currency: String, bases: Set<String>) = if (isBase(ticker, bases)) valuationUpper(pythonCashLower(valuationStrip(ticker))) else currency
    private fun position(positions: InvestmentObject, ticker: String, currency: String, bases: Set<String>): InvestmentObject {
        val t = pythonCashLower(valuationStrip(ticker)); val c = if (isBase(t, bases)) valuationUpper(t) else currency
        val p = positions.objectDefault(t, buildJsonObject { put("shares", "0"); put("total_cost", "0"); put("cost_currency", c) })
        p["shares"] = InvestmentDecimal.exact(p["shares"] ?: JsonPrimitive(0), "shares").fixed()
        p["total_cost"] = InvestmentDecimal.exact(p["total_cost"] ?: JsonPrimitive(0), "total_cost").fixed()
        if (isBase(t, bases)) { p["cost_currency"] = valuationUpper(t); return p }
        val existing = p["cost_currency"]?.takeIf(::pythonTruthy) ?: JsonPrimitive(c)
        require(existing == JsonPrimitive(c) || p.num("shares").zero() && p.num("total_cost").zero()) { "cost currency conflict for $t: ${pythonString(existing)} != $c" }
        p["cost_currency"] = if (existing == JsonPrimitive(c)) pythonString(existing) else c
        return p
    }
    private fun InvestmentObject.num(field: String) = InvestmentDecimal.exact(this[field], field)
    private fun set(p: InvestmentObject, shares: InvestmentDecimal, cost: InvestmentDecimal) { p["shares"] = shares.fixed(); p["total_cost"] = cost.fixed() }
    private fun setQuantity(p: InvestmentObject, shares: InvestmentDecimal, ticker: String, bases: Set<String>, cost: InvestmentDecimal? = null) {
        if (isBase(ticker, bases)) { set(p, shares, shares); p["cost_currency"] = valuationUpper(pythonCashLower(valuationStrip(ticker))) }
        else set(p, shares, cost ?: throw IllegalArgumentException("cost required for non-base ticker ${pythonCashLower(valuationStrip(ticker))}"))
    }
    private inline fun <T> change(document: CashSnapshotDocument, block: (InvestmentObject) -> T): T {
        val root = InvestmentObject(document.value)
        try { return block(root) } finally { document.value = root.json() }
    }
    private fun trade(positions: InvestmentObject, from: String, to: String, fromAmount: InvestmentDecimal, toAmount: InvestmentDecimal,
        commission: InvestmentDecimal, feeAsset: String, currency: String, bases: Set<String>) {
        val source = position(positions, from, costCurrency(from, currency, bases), bases)
        val target = position(positions, to, costCurrency(to, currency, bases), bases)
        val shares = source.num("shares"); val cost = source.num("total_cost")
        val bothBase = isBase(from, bases) && isBase(to, bases)
        val released = if (bothBase || isBase(from, bases)) fromAmount else if (shares > InvestmentDecimal.ZERO) cost.times(fromAmount).releasedCost(shares) else fromAmount
        val feeFrom = if (feeAsset == from) commission else InvestmentDecimal.ZERO
        setQuantity(source, shares.minus(fromAmount).minus(feeFrom), from, bases, cost.minus(released).minus(feeFrom))
        val targetAmount = toAmount.minus(if (feeAsset == to) commission else InvestmentDecimal.ZERO)
        val targetCost = if (bothBase || isBase(to, bases) || to == pythonCashLower(currency)) targetAmount else released.plus(if (feeAsset == from) commission else InvestmentDecimal.ZERO)
        setQuantity(target, target.num("shares").plus(targetAmount), to, bases, target.num("total_cost").plus(targetCost))
        if (!commission.zero() && feeAsset.isNotEmpty() && feeAsset !in setOf(from, to)) {
            val fee = position(positions, feeAsset, costCurrency(feeAsset, currency, bases), bases)
            val feeShares = fee.num("shares").minus(commission)
            if (isBase(feeAsset, bases)) setQuantity(fee, feeShares, feeAsset, bases)
            else setQuantity(fee, feeShares, feeAsset, bases, fee.num("total_cost").minus(commission))
        }
    }
    fun applyEvent(document: CashSnapshotDocument, row: JsonObject, defaultCurrency: String, baseTickers: Iterable<String>? = null) = change(document) { root ->
        val bases = normalizeInvestmentBaseTickers(baseTickers)
        val currency = valuationUpper(row["currency"]?.takeIf(::pythonTruthy)?.let(::pythonString) ?: defaultCurrency)
        val account = investmentText(row["account_name"]); val date = investmentText(row["date"])
        val (type, _) = validateInvestmentRecordSubtype(row["record_type"], row["record_subtype"])
        val positions = root.objectDefault("accounts").objectDefault("security").objectDefault(account, buildJsonObject { put("currency", defaultCurrency); put("positions", buildJsonObject {}) }).objectDefault("positions")
        val from = pythonCashLower(investmentText(row["from_ticker"])); val to = pythonCashLower(investmentText(row["to_ticker"]))
        val fromAmount = InvestmentDecimal.exact(row["from_amount"] ?: JsonPrimitive(0), "from_amount", true)
        val toAmount = InvestmentDecimal.exact(row["to_amount"] ?: JsonPrimitive(0), "to_amount", true)
        val commission = InvestmentDecimal.exact(row["commission"] ?: JsonPrimitive(0), "commission", true)
        val feeAsset = pythonCashLower(investmentText(row["commission_asset"]))
        when (type) {
            "funding", "income", "expense", "reversal", "subscription", "adjustment" -> {
                if (toAmount > InvestmentDecimal.ZERO && fromAmount.zero()) {
                    val ticker = to.ifEmpty { pythonCashLower(currency) }; val target = position(positions, ticker, costCurrency(ticker, currency, bases), bases)
                    val shares = target.num("shares").plus(toAmount)
                    if (isBase(ticker, bases)) setQuantity(target, shares, ticker, bases)
                    else setQuantity(target, shares, ticker, bases, InvestmentDecimal.exact(target["total_cost"], "cost").plus(if (ticker == pythonCashLower(currency)) toAmount else InvestmentDecimal.ZERO))
                } else {
                    val ticker = from.ifEmpty { pythonCashLower(currency) }; val source = position(positions, ticker, costCurrency(ticker, currency, bases), bases)
                    setQuantity(source, source.num("shares").minus(fromAmount), ticker, bases, InvestmentDecimal.exact(source["total_cost"], "cost").minus(fromAmount))
                }
            }
            "trade" -> trade(positions, from, to, fromAmount, toAmount, commission, feeAsset, currency, bases)
            "snapshot" -> {
                val ticker = to.ifEmpty { from.ifEmpty { pythonCashLower(currency) } }; val target = position(positions, ticker, costCurrency(ticker, currency, bases), bases)
                if (isBase(ticker, bases)) setQuantity(target, toAmount, ticker, bases)
                else {
                    fun present(key: String) = row[key] != null && row[key] != JsonNull && row[key] != JsonPrimitive("")
                    val cost = when { present("total_cost") -> InvestmentDecimal.exact(row["total_cost"], "total_cost"); present("price") -> toAmount.times(InvestmentDecimal.exact(row["price"], "price", true)); else -> InvestmentDecimal.ZERO }
                    setQuantity(target, toAmount, ticker, bases, cost)
                }
            }
        }
        root["updated_at"] = date.codePoints().toArray().take(10).toIntArray().let { String(it, 0, it.size) }
    }

    fun applyCommand(document: CashSnapshotDocument, command: InvestmentCommand, accountType: String, defaultCurrency: String,
        baseTickers: Iterable<String>? = null, now: () -> String = ::investmentUtcNow): JsonObject = change(document) { root ->
        require(accountType in setOf("security", "crypto")) { "investment command requires a security or crypto account" }
        val bases = normalizeInvestmentBaseTickers(baseTickers); val currency = valuationUpper(command.currency?.takeIf { it.isNotEmpty() } ?: defaultCurrency)
        val points = currency.codePoints().toArray()
        require(points.size >= 3 && points.all { code -> (valuationAlnumRanges.indices step 2).any { code in valuationAlnumRanges[it]..valuationAlnumRanges[it + 1] } }) { "currency is required" }
        val date = command.date?.takeIf { it.isNotEmpty() } ?: now()
        val account = root.objectDefault("accounts").objectDefault("security").objectDefault(command.account, buildJsonObject { put("currency", defaultCurrency); put("positions", buildJsonObject {}) })
        account["currency"] = account["currency"]?.takeIf(::pythonTruthy) ?: JsonPrimitive(defaultCurrency)
        val positions = account.objectDefault("positions"); val cashTicker = pythonCashLower(currency); val cash = position(positions, cashTicker, costCurrency(cashTicker, currency, bases), bases)
        val cashShares = InvestmentDecimal.exact(cash["shares"], "cash shares"); val cashCost = InvestmentDecimal.exact(cash["total_cost"], "cash cost")
        fun amount(value: CashAmount?, name: String, default: Boolean = false) = InvestmentDecimal.exact(value?.toString() ?: if (default) "0" else "None", name)
        fun event(type: String, subtype: String, from: String = "", to: String = "", fromAmount: InvestmentDecimal = InvestmentDecimal.ZERO, toAmount: InvestmentDecimal = InvestmentDecimal.ZERO,
            price: InvestmentDecimal = InvestmentDecimal.ZERO, commission: InvestmentDecimal = InvestmentDecimal.ZERO, feeAsset: String = "") = buildJsonObject {
            put("date", date); put("record_type", type); put("record_subtype", subtype); put("from_ticker", from); put("to_ticker", to)
            put("from_amount", fromAmount.fixed()); put("to_amount", toAmount.fixed()); put("price", price.fixed()); put("commission", commission.fixed()); put("commission_asset", feeAsset); put("currency", currency); put("account_name", command.account); put("note", command.note)
        }
        val row = when (val type = command.recordType) {
            "buy", "sell" -> {
                val quantity = amount(command.quantity, "quantity"); val price = amount(command.price, "price"); val commission = amount(command.commission, "commission", true)
                require(listOf(quantity, price, commission).all { it >= InvestmentDecimal.ZERO }) { "$type values must be non-negative" }
                val ticker = pythonCashLower(valuationStrip(command.ticker)); val p = position(positions, ticker, costCurrency(ticker, currency, bases), bases)
                val principal = quantity.times(price)
                if (type == "buy") {
                    val total = principal.plus(commission)
                    setQuantity(cash, cashShares.minus(total), cashTicker, bases, cashCost.minus(total))
                    setQuantity(p, p.num("shares").plus(quantity), ticker, bases, p.num("total_cost").plus(total))
                    event("trade", "security", cashTicker, ticker, principal, quantity, price, commission, cashTicker)
                } else {
                    val shares = p.num("shares"); val cost = p.num("total_cost")
                    val released = if (shares > InvestmentDecimal.ZERO) cost.times(quantity).releasedCost(shares) else principal
                    val proceeds = principal.minus(commission)
                    setQuantity(p, shares.minus(quantity), ticker, bases, cost.minus(released))
                    setQuantity(cash, cashShares.plus(proceeds), cashTicker, bases, cashCost.plus(proceeds))
                    event("trade", "security", ticker, cashTicker, quantity, principal, price, commission, cashTicker)
                }
            }
            "swap" -> {
                val fromAmount = amount(command.quantity, "from_quantity"); val toAmount = amount(command.toQuantity, "to_quantity"); val commission = amount(command.commission, "commission", true)
                require(listOf(fromAmount, toAmount, commission).all { it >= InvestmentDecimal.ZERO }) { "swap values must be non-negative" }
                val from = pythonCashLower(valuationStrip(command.fromTicker)); val to = pythonCashLower(valuationStrip(command.toTicker))
                val feeAsset = pythonCashLower(valuationStrip(command.commissionAsset)).ifEmpty { if (!commission.zero()) from else "" }
                trade(positions, from, to, fromAmount, toAmount, commission, feeAsset, currency, bases)
                event("trade", "security", from, to, fromAmount, toAmount, commission = commission, feeAsset = feeAsset)
            }
            "deposit", "withdraw", "dividend", "fee", "checkin_cash" -> {
                val value = amount(command.amount, "amount")
                require(type == "checkin_cash" || value >= InvestmentDecimal.ZERO) { "$type amount must be non-negative" }
                val outgoing = type in setOf("withdraw", "fee")
                when (type) {
                    "deposit", "dividend" -> setQuantity(cash, cashShares.plus(value), cashTicker, bases, cashCost.plus(value))
                    "withdraw", "fee" -> setQuantity(cash, cashShares.minus(value), cashTicker, bases, cashCost.minus(value))
                    else -> setQuantity(cash, value, cashTicker, bases, value)
                }
                val fact = when (type) { "deposit", "withdraw" -> "funding" to "external"; "dividend" -> "income" to "dividend_cash"; "fee" -> "expense" to "commission"; else -> "snapshot" to "cash" }
                event(fact.first, fact.second, if (type == "dividend") pythonCashLower(command.ticker) else if (outgoing || type == "checkin_cash") cashTicker else "", if (type in setOf("deposit", "dividend")) cashTicker else "",
                    if (outgoing) value else InvestmentDecimal.ZERO, if (!outgoing) value else InvestmentDecimal.ZERO, amount(exactCashAmount("1"), "price"))
            }
            "checkin_ticker" -> {
                val quantity = amount(command.quantity, "quantity"); val price = amount(command.price, "price"); val ticker = pythonCashLower(valuationStrip(command.ticker))
                val p = position(positions, ticker, costCurrency(ticker, currency, bases), bases)
                setQuantity(p, quantity, ticker, bases, quantity.times(price))
                event("snapshot", "position", ticker, toAmount = quantity, price = price)
            }
            else -> throw IllegalArgumentException("unsupported investment record_type: $type")
        }
        root["updated_at"] = date.codePoints().toArray().take(10).toIntArray().let { String(it, 0, it.size) }
        row
    }
}
