package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.*

internal typealias PortfolioKey = Pair<String, String>
internal fun portfolioPeriodBounds(now: QuoteTime, period: String, timezone: String?): Pair<QuoteTime, QuoteTime> {
    val zone = timezone?.takeIf { it.isNotEmpty() }?.let(::PortfolioTimezone)
    val local = zone?.local(now.instant()) ?: now.local
    val start = when (period) {
        "24h" -> now.copy(local = now.local.minusHours(24))
        "30d", "90d", "365d" -> now.copy(local = now.local.minusDays(period.removeSuffix("d").toLong()))
        "week_to_date", "month_to_date", "year_to_date" -> {
            val date = when (period) {
                "week_to_date" -> local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toLocalDate()
                "month_to_date" -> local.withDayOfMonth(1).toLocalDate()
                else -> local.withDayOfYear(1).toLocalDate()
            }.atStartOfDay()
            zone?.time(date, if (period == "week_to_date") 0 else zone.fold(now.instant())) ?: now.copy(local = date)
        }
        else -> throw IllegalArgumentException("invalid investment period")
    }
    return start to now
}

internal data class PortfolioHistoryRequest(val key: PortfolioKey, val ticker: String, val quantity: String, val at: QuoteTime)
internal class PortfolioHistorical(valuation: ValuationService, requests: List<PortfolioHistoryRequest>, deadline: Double?, private val monotonic: () -> Double) {
    private val expected = requests.map { it.key }.toSet()
    private val results = mutableMapOf<PortfolioKey, QuoteResult?>()
    private val completed = LinkedBlockingQueue<Pair<PortfolioKey, QuoteResult?>>()
    init {
        val pending = LinkedBlockingQueue(requests)
        repeat(minOf(8, requests.size)) {
            portfolioThread {
                while (true) {
                    val request = pending.poll() ?: break
                    val result = try {
                        if (deadline != null && monotonic() >= deadline) null else inferAssetKind(request.ticker)?.let { valuation.quoteAt(AssetRef(request.ticker, it, request.quantity), request.at) }
                    } catch (_: Exception) { null }
                    completed.put(request.key to result)
                }
            }
        }
    }
    fun result(key: PortfolioKey, deadline: Double?): QuoteResult? {
        if (key !in expected) return null
        while (key !in results) {
            val (next, value) = portfolioPoll(completed, deadline, monotonic) ?: return null
            results[next] = value
        }
        return results[key]
    }
}
internal class PortfolioPeriodContext(raw: JsonObject, now: QuoteTime, period: String, timezone: String?) {
    private data class Event(val row: JsonObject, val at: QuoteTime, val sequence: Int)
    private data class Baseline(val event: Event, val quantity: BigDecimal)
    private val bounds = portfolioPeriodBounds(now, period, timezone)
    private val start = bounds.first
    private val end = bounds.second
    private fun eventAt(row: JsonObject): QuoteTime {
        val text = portfolioText(row["occurred_at"])
        val instant = try { pythonCashTimestamp(text, preserveZSeparator = true) } catch (_: InvalidCashValue) {
            try { pythonCashTimestamp(text, allowNaive = true) } catch (_: InvalidCashValue) { throw IllegalArgumentException("Invalid isoformat string: ${pythonStringRepr(text)}") }
            throw IllegalArgumentException("investment event timestamp must be timezone-aware")
        }
        val offsetText = Regex("[+-][0-9]{2}(?::?[0-9]{2})?(?::?[0-9]{2})?(?:[.,][0-9]+)?$").find(text)?.value
        if (offsetText == null) return QuoteTime.from(instant)
        val fields = offsetText.drop(1).substringBefore('.').substringBefore(',').replace(":", "").chunked(2).map(String::toLong)
        val seconds = fields[0] * 3600 + (fields.getOrNull(1) ?: 0) * 60 + (fields.getOrNull(2) ?: 0)
        val fraction = offsetText.substringAfter('.', offsetText.substringAfter(',', "")).take(6).padEnd(6, '0').toLong()
        val micros = (seconds * 1000000 + if (seconds == 0L) 0 else fraction) * if (offsetText.startsWith('-')) -1 else 1
        val local = java.time.LocalDateTime.ofInstant(instant.plusNanos(micros * 1000), java.time.ZoneOffset.UTC)
        val offset = if (kotlin.math.abs(micros) <= 18L * 3600 * 1000000 && micros % 1000000 == 0L) java.time.ZoneOffset.ofTotalSeconds((micros / 1000000).toInt()) else null
        return if (offset != null) QuoteTime(local, offset) else QuoteTime(local, java.time.ZoneOffset.UTC, micros)
    }
    private val events = raw["investment_events"]?.jsonArray.orEmpty().map { it.jsonObject to eventAt(it.jsonObject) }
        .filter { (_, at) -> at.instant() >= start.instant() && at.instant() < end.instant() }.sortedBy { it.second.instant() }
        .mapIndexed { index, (row, at) -> Event(row, at, index) }
    private val cashKeys = mutableSetOf<PortfolioKey>()
    private val changes = mutableMapOf<PortfolioKey, BigDecimal>()
    private val baselines = mutableMapOf<PortfolioKey, Baseline>()
    private fun account(row: JsonObject) = portfolioText(row["account"].takeIf(::pythonTruthy) ?: row["account_name"])
    private fun lower(value: JsonElement?) = pythonCashLower(portfolioText(value))
    private fun amount(row: JsonObject, field: String) = portfolioNumber(row[field].takeIf(::pythonTruthy) ?: JsonPrimitive(0), field)
    init {
        for ((name, value) in raw["accounts"]?.jsonObject.orEmpty()) {
            val bases = raw["base_currencies"]?.jsonObject?.get(name)?.jsonArray.orEmpty().ifEmpty { listOf(value.jsonObject["currency"].takeIf(::pythonTruthy) ?: JsonPrimitive("")) }
            for (ticker in bases) if (pythonTruthy(ticker)) cashKeys += name to lower(ticker)
        }
        fun change(key: PortfolioKey, value: BigDecimal) { changes[key] = (changes[key] ?: BigDecimal.ZERO).add(value, portfolioMath) }
        for (event in events) {
            val row = event.row; val name = account(row); val from = lower(row["from_ticker"]); val to = lower(row["to_ticker"])
            val paid = amount(row, "from_amount"); val received = amount(row, "to_amount")
            when (portfolioText(row["record_type"])) {
                "snapshot" -> { val ticker = to.ifEmpty { from }.ifEmpty { lower(row["currency"]) }; if (ticker.isNotEmpty()) baselines[name to ticker] = Baseline(event, received) }
                "trade" -> {
                    if (from.isNotEmpty()) change(name to from, paid.negate())
                    if (to.isNotEmpty()) change(name to to, received)
                    val fee = amount(row, "commission"); val asset = lower(row["commission_asset"])
                    if (fee.signum() != 0 && asset.isNotEmpty()) change(name to asset, fee.negate())
                }
                "income" -> if (to.isNotEmpty()) change(name to to, received)
                "funding", "expense", "reversal", "subscription", "adjustment" -> {
                    if (from.isNotEmpty()) change(name to from, paid.negate())
                    if (to.isNotEmpty()) change(name to to, received)
                }
            }
        }
    }
    private fun current(result: JsonObject): Map<PortfolioKey, JsonObject> = result.getValue("accounts").jsonArray.flatMap { a ->
        val account = a.jsonObject
        account.getValue("positions").jsonArray.map { p -> (portfolioText(account["name"]) to lower(p.jsonObject["ticker"])) to p.jsonObject }
    }.toMap()
    private fun keys(current: Map<PortfolioKey, JsonObject>) = (current.keys + changes.keys + baselines.keys).sortedWith { a, b -> cashRelationStringOrder.compare(a.first, b.first).takeIf { it != 0 } ?: cashRelationStringOrder.compare(a.second, b.second) }
    private fun cash(current: Map<PortfolioKey, JsonObject>, key: PortfolioKey) = current[key]?.get("is_cash") == JsonPrimitive(true) || key in cashKeys
    private fun opening(current: Map<PortfolioKey, JsonObject>, key: PortfolioKey) = baselines[key]?.quantity ?: (current[key]?.get("shares")?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO).subtract(changes[key] ?: BigDecimal.ZERO, portfolioMath)
    fun historyRequests(result: JsonObject): List<PortfolioHistoryRequest> {
        val current = current(result)
        return keys(current).mapNotNull { key ->
            val quantity = opening(current, key)
            if (quantity.signum() <= 0 || cash(current, key)) null else PortfolioHistoryRequest(key, key.second, quantity.toPlainString(), baselines[key]?.event?.at ?: start)
        }
    }
    fun finish(result: JsonObject, history: PortfolioHistorical, deadline: Double?, monotonic: () -> Double): JsonObject {
        val current = current(result); var openingAssets = BigDecimal.ZERO; var closingAssets = BigDecimal.ZERO
        val positions = mutableMapOf<PortfolioKey, JsonObject>(); val allBaselines = mutableListOf<JsonElement>(); var complete = true
        for (key in keys(current)) {
            if (deadline != null && monotonic() >= deadline) { complete = false; break }
            val position = current[key]; val quantity = opening(current, key); val baseline = baselines[key]
            val baselineJson = baseline?.let { buildJsonObject { put("account", key.first); put("ticker", key.second); put("occurred_at", portfolioTimeText(it.event.at)) } }
            if (baselineJson != null) allBaselines += baselineJson
            val closing = position?.get("market_value")?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toBigDecimal()
            if (closing != null) closingAssets = closingAssets.add(closing, portfolioMath)
            if (cash(current, key)) { openingAssets = openingAssets.add(quantity, portfolioMath); continue }
            if (quantity.signum() < 0) { complete = false; continue }
            var opening = BigDecimal.ZERO
            if (quantity.signum() != 0) {
                val quote = history.result(key, deadline)
                val currency = position?.get("cost_currency")?.jsonPrimitive?.content
                if (quote == null || quote.status != QuoteStatus.COMPLETE || quote.marketValue == null || !currency.isNullOrEmpty() && !quote.quoteCurrency.isNullOrEmpty() && valuationUpper(currency) != valuationUpper(quote.quoteCurrency)) { complete = false; continue }
                opening = quote.marketValue.toBigDecimal()
            }
            openingAssets = openingAssets.add(opening, portfolioMath)
            if (position == null) continue
            val flows = mutableListOf<InvestmentInstrumentFlow>(); var income = BigDecimal.ZERO; var costs = BigDecimal.ZERO
            for (event in events) {
                val row = event.row
                if (account(row) != key.first || baseline != null && event.sequence <= baseline.event.sequence) continue
                val from = lower(row["from_ticker"]); val to = lower(row["to_ticker"])
                when (portfolioText(row["record_type"])) {
                    "trade" -> {
                        val commission = amount(row, "commission")
                        if (to == key.second && cash(current, key.first to from)) { flows += InvestmentInstrumentFlow(event.at, amount(row, "from_amount").toPlainString()); costs = costs.add(commission, portfolioMath) }
                        else if (from == key.second && cash(current, key.first to to)) { flows += InvestmentInstrumentFlow(event.at, amount(row, "to_amount").negate(portfolioMath).toPlainString()); costs = costs.add(commission, portfolioMath) }
                    }
                    "income" -> if (from == key.second) income = income.add(amount(row, "to_amount"), portfolioMath)
                }
            }
            if (closing == null) { complete = false; continue }
            val pnl = calculateInstrumentPeriodPnl(opening.toPlainString(), closing.toPlainString(), flows, income.toPlainString(), costs.toPlainString())
            val rate = calculateInvestmentPeriodReturn(pnl, opening.toPlainString(), flows.map { InvestmentPeriodFlow(it.occurredAt, it.capitalChange) }, baseline?.event?.at ?: start, end)
            positions[key] = buildJsonObject { put("period_profit", JsonPrimitive(pnl.toBigDecimal().toPlainString())); put("period_profit_rate", rate?.let { JsonPrimitive(it.toBigDecimal().toPlainString()) } ?: JsonNull); put("period_baselines", JsonArray(listOfNotNull(baselineJson))) }
        }
        val external = events.filter { portfolioText(it.row["record_type"]) == "funding" }.map { event ->
            val received = amount(event.row, "to_amount"); val flow = if (received.signum() > 0) portfolioNumber(event.row["to_amount"], "external_flow") else amount(event.row, "from_amount").negate(portfolioMath)
            InvestmentPeriodFlow(event.at, flow.toPlainString())
        }
        val pnl = if (complete) calculateTotalInvestmentPeriodPnl(openingAssets.toPlainString(), closingAssets.toPlainString(), external.map { it.amount }) else null
        val rate = if (pnl != null && allBaselines.isEmpty()) calculateInvestmentPeriodReturn(pnl, openingAssets.toPlainString(), external, start, end) else null
        val accounts = result.getValue("accounts").jsonArray.map { a ->
            val account = a.jsonObject; val name = portfolioText(account["name"])
            JsonObject(account + ("positions" to JsonArray(account.getValue("positions").jsonArray.map { p -> JsonObject(p.jsonObject + positions[name to lower(p.jsonObject["ticker"])].orEmpty()) })))
        }
        return JsonObject(result + buildJsonObject { put("accounts", JsonArray(accounts)); put("period_profit", pnl?.let { JsonPrimitive(it.toBigDecimal().toPlainString()) } ?: JsonNull); put("period_profit_rate", rate?.let { JsonPrimitive(it.toBigDecimal().toPlainString()) } ?: JsonNull); put("period_baselines", JsonArray(allBaselines)) })
    }
}
