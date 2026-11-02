package com.finance.tracker.backend

import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioValuationContractTest {
    @Test fun completePythonOutputsAndProviderCallsMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_VALUATION_CONTRACT"))).readText()).jsonArray
        for (raw in cases) {
            val case = raw.jsonObject; val now = QuoteTime.parse(case["now"]?.jsonPrimitive?.content ?: "2026-08-02T12:00:00+00:00"); val calls = Collections.synchronizedList(mutableListOf<JsonArray>())
            fun call(vararg values: String) { calls += JsonArray(values.map(::JsonPrimitive)) }
            fun tick(identity: String, history: Boolean = false, at: QuoteTime? = null): ProviderTick? {
                val value = case[if (history) "history" else "ticks"]?.jsonObject?.get(identity) ?: return null
                if (value == JsonNull) return null
                if (value == JsonPrimitive("unsupported")) throw UnsupportedQuote(identity)
                if (value == JsonPrimitive("error")) error("synthetic provider error")
                val data = value.jsonObject
                return ProviderTick(data.getValue("price").jsonPrimitive.content, if ("currency" in data) data["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content else "USD",
                    data["observed_at"]?.jsonPrimitive?.content?.let(QuoteTime::parse) ?: at ?: now, "oracle", data["session"]?.jsonPrimitive?.content ?: "unknown")
            }
            val provider = object : HistoricalQuoteProvider {
                override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { call("current", identity, kind.value); return tick(identity) }
                override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? { call("history", identity, kind.value, portfolioTimeText(at)); return tick(identity, true, at) }
            }
            val fx = FxRateProvider(FxMidFetcher { _, base, quote -> call("fx", base, quote); case["fx"]?.jsonObject?.get("$base/$quote")?.jsonPrimitive?.content })
            val service = PortfolioQueryService(object : PortfolioRepository { override fun loadPortfolio() = case.getValue("raw").jsonObject }, ValuationService(provider, clock = { now }), fxRates = fx, queryDeadlineSeconds = null, clock = { now })
            val output = try { buildJsonObject { put("value", service.getPortfolio(case["display"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content, case["period"]?.jsonPrimitive?.content ?: "24h", case["timezone"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)) } }
                catch (failure: Exception) { buildJsonObject { put("error", if (failure is IllegalArgumentException) failure.message ?: "" else "internal") } }
            assertEquals(case["expected"], output, case.getValue("name").jsonPrimitive.content)
            assertEquals(case.getValue("calls").jsonArray.sortedBy { it.toString() }, calls.sortedBy { it.toString() }, "calls ${case["name"]}")
        }
    }
}
