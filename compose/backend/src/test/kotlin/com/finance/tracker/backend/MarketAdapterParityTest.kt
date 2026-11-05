package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class MarketAdapterParityTest {
    private fun normal(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Exception -> buildJsonObject { put("error", if (value is UnsupportedQuote) "unsupported" else "provider_error") }
        is Map<*, *> -> buildJsonObject { value.forEach { (key, item) -> put(key.toString(), normal(item)) } }
        is ProviderTick -> buildJsonObject {
            val r = requireNotNull(LedgerDecimal.parseRepresentationOrNull(value.price))
            put("price", buildJsonObject { put("negative", r.negative); put("coefficient", r.coefficient); put("exponent", r.exponent.toString()) })
            put("quote_currency", value.quoteCurrency?.let(::JsonPrimitive) ?: JsonNull)
            put("observed_at", value.observedAt?.instant()?.toString()?.replace("Z", "+00:00")?.let(::JsonPrimitive) ?: JsonNull)
            put("provider", value.provider?.let(::JsonPrimitive) ?: JsonNull); put("quote_session", value.quoteSession?.let(::JsonPrimitive) ?: JsonNull)
        }
        else -> error("unexpected market result")
    }
    @Test fun actualOriginalAdapterResultsAndRequestsMatch() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_MARKET_ADAPTER_CONTRACT"))).readText()).jsonObject["cases"]!!.jsonArray
        assertEquals(189, cases.size)
        val failures = mutableListOf<String>()
        val now = QuoteTime.parse("2026-08-12T15:42:31+00:00")
        for (raw in cases) {
            val case = raw.jsonObject; val name = case["name"]!!.jsonPrimitive.content
            val requests = mutableListOf<JsonElement>(); val payloads = case["payloads"]!!.jsonArray
            val fetcher = QuoteJsonFetcher { url, timeout ->
                val index = requests.size
                requests.add(buildJsonObject { put("url", url); put("timeout", timeout?.let(::JsonPrimitive) ?: JsonNull) })
                val body = payloads[minOf(index, payloads.lastIndex)]
                if (body == JsonPrimitive("HTTP_ERROR")) throw IllegalStateException("SYNTHETIC_PROVIDER_SECRET")
                body
            }
            val provider: QuoteProvider = when (case["provider"]!!.jsonPrimitive.content) {
                "yahoo" -> YahooChartQuoteProvider(fetcher)
                "cboe" -> CboeQuoteProvider(fetcher)
                "nasdaq" -> NasdaqQuoteProvider(fetcher)
                "finnhub" -> FinnhubQuoteProvider(fetcher, case["key"]?.jsonPrimitive?.content.orEmpty())
                "crypto" -> CryptoQuoteProvider(fetcher) { now }
                "prediction" -> PredictionMarketQuoteProvider(fetcher, clock = { now }, monotonic = { 0.0 })
                "cash" -> CashQuoteProvider { now }
                else -> error("unrecognized provider")
            }
            val timeout = case["timeout"]?.jsonPrimitive?.double
            fun kind(value: JsonElement) = AssetKind.entries.first { it.value == value.jsonPrimitive.content }
            val actual = try {
                if (case["operation"] == JsonPrimitive("batch")) {
                    val refs = case["refs"]!!.jsonArray.map { AssetRef(it.jsonObject["identity"]!!.jsonPrimitive.content, kind(it.jsonObject["kind"]!!)) }
                    (provider as BatchQuoteProvider).rawQuoteMany(refs, timeout)
                } else {
                    val identity = case["identity"]!!.jsonPrimitive.content; val assetKind = kind(case["kind"]!!)
                    if (provider is TimedQuoteProvider) provider.rawQuote(identity, assetKind, timeout) else provider.rawQuote(identity, assetKind)
                }
            } catch (failure: Exception) { failure }
            if (case["expected"] != normal(actual)) failures.add("$name expected=${case["expected"]} actual=${normal(actual)}")
            if (case["requests"] != JsonArray(requests)) failures.add("$name requests expected=${case["requests"]} actual=${JsonArray(requests)}")
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
