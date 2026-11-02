package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class FxRateContractTest {
    private val now = Instant.parse("2026-07-25T01:02:03Z")
    private fun normal(value: String?): JsonElement {
        if (value == null) return JsonNull
        val r = LedgerDecimal.parseRepresentationOrNull(value) ?: return JsonPrimitive(value)
        return buildJsonObject { put("negative", r.negative); put("coefficient", r.coefficient); put("exponent", r.exponent.toString()) }
    }
    @Test fun actualPythonResultsAndRequestsThroughJvmHttp() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_FX_CONTRACT"))).readText()).jsonObject["cases"]!!.jsonArray
        assertEquals(107, cases.size)
        val pool = Executors.newCachedThreadPool { task -> Thread(task, "fx-http-test").apply { isDaemon = true } }
        var payloads = JsonArray(emptyList()); val index = AtomicInteger(); val actualRequests = CopyOnWriteArrayList<JsonElement>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { exchange ->
                actualRequests.add(JsonPrimitive("https://api.frankfurter.dev" + exchange.requestURI))
                val position = index.getAndIncrement(); val payload = payloads[minOf(position, payloads.lastIndex)]
                val error = payload == JsonPrimitive("HTTP_ERROR")
                val body = (if (error) "SYNTHETIC_FX_SECRET" else payload.toString()).toByteArray()
                val correct = exchange.requestHeaders.getFirst("User-Agent") == "ft-fx/1.0" && exchange.requestHeaders.getFirst("Accept") == "application/json"
                exchange.sendResponseHeaders(if (!correct) 400 else if (error) 503 else 200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        val http = JvmQuoteHttpFetcher(emptyMap(), "ft-fx/1.0")
        val failures = mutableListOf<String>()
        try {
            for (raw in cases) {
                val case = raw.jsonObject; val args = case["args"]!!.jsonObject; val op = case["operation"]!!.jsonPrimitive.content
                val calls = mutableListOf<JsonElement>(); actualRequests.clear(); index.set(0)
                payloads = args["payloads"] as? JsonArray ?: JsonArray(emptyList())
                val rates = FxRates(QuoteJsonFetcher { url, timeout ->
                    assertEquals(8.0, timeout)
                    val uri = URI.create(url)
                    http.fetch("http://127.0.0.1:${server.address.port}" + uri.rawPath + "?" + uri.rawQuery, timeout)
                }, clock = { now })
                fun text(key: String) = args[key]!!.jsonPrimitive.content
                val result = try {
                    when (op) {
                        "day" -> JsonPrimitive(rates.businessDayUtc(if (args["datetime"] == JsonPrimitive(true)) QuoteTime.parse(text("value")) else args["value"]))
                        "mid" -> normal(rates.getMidRate(args["day"], args["base"], args["quote"], FxMidFetcher { day, base, quote ->
                            calls.add(JsonArray(listOf(day, base, quote).map(::JsonPrimitive))); text("rate") }))
                        "fetch" -> normal(rates.fetchFrankfurter(text("day"), text("base"), text("quote")))
                        "error" -> normal(fxRateError(text("cash"), text("loan"), text("base"), text("quote"), args["market"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content))
                        else -> error("unknown operation")
                    }
                } catch (_: Exception) { buildJsonObject { put("error", "error") } }
                if (op == "fetch") calls.addAll(actualRequests)
                if (result != case["expected"] || JsonArray(calls) != case["calls"]) failures.add("${case["name"]}: expected=${case["expected"]}, actual=$result, expectedCalls=${case["calls"]}, actualCalls=$calls")
            }
        } finally { server.stop(0); pool.shutdownNow() }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
    @Test fun defaultCacheStoresSuccessAndMissButInjectionBypassesIt() {
        val calls = mutableListOf<String>(); var body: JsonElement = Json.parseToJsonElement("""{"rates":{"CNY":"7.10"}}""")
        val rates = FxRates(QuoteJsonFetcher { url, _ -> calls.add(url); body })
        assertEquals("710E-2", rates.getMidRate("2026-07-25", "usd", "cny"))
        assertEquals("710E-2", rates.getMidRate("2026-07-25-extra", "USD", "CNY")); assertEquals(1, calls.size)
        var injected = 0
        repeat(2) { assertEquals("-3.000", rates.getMidRate("2026-07-25", "USD", "CNY", FxMidFetcher { _, _, _ -> injected++; "-3.000" })) }
        assertEquals(2, injected); assertEquals(1, calls.size)
        body = JsonNull
        assertNull(rates.getMidRate("2026-07-26", "USD", "CNY")); assertEquals(7, calls.size)
        assertNull(rates.getMidRate("2026-07-26", "USD", "CNY")); assertEquals(7, calls.size)
        rates.clearCache(); body = Json.parseToJsonElement("""{"rates":{"CNY":"7.2"}}""")
        assertEquals("72E-1", rates.getMidRate("2026-07-26", "USD", "CNY")); assertEquals(8, calls.size)
    }
    @Test fun injectionErrorsPropagateAndProviderDefaultsUseUtcToday() {
        val rates = FxRates(QuoteJsonFetcher { _, _ -> error("must not download") }, clock = { now }); val calls = mutableListOf<List<String>>()
        val provider = FxRateProvider(FxMidFetcher { day, base, quote -> calls.add(listOf(day, base, quote)); null }, rates)
        assertNull(provider.getMid("usd", "cny")); assertEquals(listOf(listOf("2026-07-25", "USD", "CNY")), calls)
        assertEquals("1", provider.getMid("usd", "USD", "")); assertEquals(1, calls.size)
        assertFailsWith<IllegalStateException> { rates.getMidRate("2026-07-25", "USD", "CNY", FxMidFetcher { _, _, _ -> error("injected failure") }) }
        assertNull(rates.getMidRate("", "USD", "USD")); assertEquals("1", rates.fetchFrankfurter("", "USD", "usd"))
        val field = FxRateProvider::class.java.getDeclaredField("rates").apply { isAccessible = true }
        assertSame(FxRates.process, field.get(FxRateProvider())); assertSame(field.get(FxRateProvider()), field.get(FxRateProvider()))
    }
}
