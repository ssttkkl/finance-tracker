package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class FinanceQueryParityTest {
    @Test fun actualOriginalFinanceOutputsAndDependencyOrderMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_FINANCE_APPLICATION_CONTRACT"))).readText()).jsonObject["cases"]!!.jsonArray
        assertEquals(67, cases.size)
        val failures = mutableListOf<String>(); val clock = QuoteTime.parse("2026-07-25T00:00:00+00:00")
        for (raw in cases) {
            val case = raw.jsonObject; val args = case["args"]!!.jsonObject; val calls = mutableListOf<JsonElement>()
            fun called(vararg values: Any?) { calls.add(sourceJson(values.toList())) }
            val valuation = if (args["valuation"] == JsonPrimitive(true)) ValuationService(object : QuoteProvider {
                override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? {
                    called("quote", identity, kind.value)
                    val tick = args["ticks"]?.jsonObject?.get(identity)?.takeUnless { it == JsonNull } ?: return null
                    if (tick == JsonPrimitive("unsupported")) throw UnsupportedQuote(identity)
                    if (tick == JsonPrimitive("error")) throw IllegalStateException("SYNTHETIC_PROVIDER_SECRET")
                    val value = tick.jsonObject
                    val currency = if ("currency" in value) value["currency"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content else "USD"
                    val at = value["at"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(QuoteTime::parse)
                    return ProviderTick(value["price"]!!.jsonPrimitive.content, currency, at, "fixture")
                }
            }, clock = { clock }) else null
            val market: ((List<String>, String) -> Map<String, String>)? = if ("prices" in args) { tickers, currency ->
                called("prices", tickers, currency); args["prices"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
            } else null
            val reporter: ((String?) -> JsonObject)? = if ("reporter" in args) { month ->
                called("reporter", month)
                when (args["reporter"]!!.jsonPrimitive.content) {
                    "value_error" -> throw IllegalArgumentException("SYNTHETIC_INVALID_MONTH")
                    "internal_error" -> throw IllegalStateException("SYNTHETIC_READER_ERROR")
                }
                Json.parseToJsonElement("""{"income":{"USD":"20.000"},"expenses":{"CNY":"5.00"},"flows":[{"note":"投资","currency":"CNY","amount":"2.00"}]}""").jsonObject
            } else null
            val app = FinanceQueryApplication(accounts = {
                called("accounts")
                args["accounts"]!!.jsonArray.map { item ->
                    val value = item.jsonObject
                    FinanceQueryAccount(value["name"]!!.jsonPrimitive.content, value["type"]!!.jsonPrimitive.content,
                        value["active"]?.jsonPrimitive?.boolean ?: true, value["currencies"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
                }
            }, snapshot = { called("snapshot"); args["snapshot"]!!.jsonObject }, transactions = { month ->
                called("transactions", month)
                args["rows"]?.jsonArray.orEmpty().map { it.jsonObject }.filter {
                    month.isNullOrEmpty() || it["occurred_at"]!!.jsonPrimitive.content.startsWith(month)
                }.sortedWith { a, b -> pythonStringOrder(b["occurred_at"]!!.jsonPrimitive.content, a["occurred_at"]!!.jsonPrimitive.content) }
            }, valuation = valuation, marketData = market, cashReporter = reporter)
            val actual = try {
                val value = if (case["operation"] == JsonPrimitive("accounts")) app.listAccounts() else
                    app.report(args["month"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                buildJsonObject { put("value", value) }
            } catch (failure: Exception) {
                buildJsonObject { put("error", if (failure is IllegalArgumentException) "value" else "internal") }
            }
            if (case["expected"] != actual) failures.add("${case["name"]} expected=${case["expected"]} actual=$actual")
            if (case["calls"] != JsonArray(calls)) failures.add("${case["name"]} calls expected=${case["calls"]} actual=${JsonArray(calls)}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
