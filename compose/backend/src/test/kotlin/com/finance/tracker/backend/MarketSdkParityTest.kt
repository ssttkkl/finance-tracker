package com.finance.tracker.backend

import com.finance.tracker.business.domain.LedgerDecimal
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class MarketSdkParityTest {
    private fun decimal(value: String?): JsonElement {
        if (value == null) return JsonNull
        val r = requireNotNull(LedgerDecimal.parseRepresentationOrNull(value))
        return buildJsonObject { put("negative", r.negative); put("coefficient", r.coefficient); put("exponent", r.exponent.toString()) }
    }
    private fun tick(value: ProviderTick?): JsonElement = if (value == null) JsonNull else buildJsonObject {
        put("price", decimal(value.price)); put("quote_currency", value.quoteCurrency?.let(::JsonPrimitive) ?: JsonNull)
        put("observed_at", value.observedAt?.instant()?.toString()?.replace("Z", "+00:00")?.let(::JsonPrimitive) ?: JsonNull)
        put("provider", value.provider?.let(::JsonPrimitive) ?: JsonNull); put("quote_session", value.quoteSession?.let(::JsonPrimitive) ?: JsonNull)
    }
    @Test fun actualOriginalSdkResultsMatchThroughJvmHttp() {
        val cases = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_MARKET_SDK_CONTRACT"))).readText()).jsonObject["cases"]!!.jsonArray
        assertEquals(27, cases.size)
        val failures = mutableListOf<String>()
        for (raw in cases) {
            val case = raw.jsonObject; val name = case["name"]!!.jsonPrimitive.content
            val requests = CopyOnWriteArrayList<URI>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                requests.add(exchange.requestURI)
                val symbol = URLDecoder.decode(exchange.requestURI.path.substringAfterLast('/'), Charsets.UTF_8)
                val body = case["payloads"]!!.jsonObject.getValue(symbol).toString().toByteArray()
                assertEquals("Mozilla/5.0", exchange.requestHeaders.getFirst("User-Agent"))
                assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
                exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
            }
            server.start()
            try {
                val actualHttp = JvmQuoteHttpFetcher(emptyMap())
                val downloader = YahooSecurityDownloader(QuoteJsonFetcher { url, timeout ->
                    val uri = URI.create(url)
                    assertEquals("https", uri.scheme); assertEquals("query2.finance.yahoo.com", uri.host)
                    actualHttp.fetch("http://127.0.0.1:${server.address.port}${uri.rawPath}?${uri.rawQuery}", timeout)
                })
                val symbols = case["symbols"]!!.jsonArray.map { it.jsonPrimitive.content }
                val at = case["history"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(QuoteTime::parse)
                val actual = if (at == null) {
                    val values = securityDownloadedTicks(symbols, downloader.download(SecurityDownloadRequest(symbols)))
                    buildJsonObject { symbols.forEach { put(it, tick(values[it])) } }
                } else decimal(historicalSecurityPrice(symbols.single(), at, downloader))
                if (case["expected"] != actual) failures.add("$name expected=${case["expected"]} actual=$actual")
                assertEquals(symbols.size, requests.size, name)
                for (request in requests) {
                    val query = request.rawQuery.split('&').associate { item ->
                        val parts = item.split('=', limit = 2); parts[0] to URLDecoder.decode(parts[1], Charsets.UTF_8)
                    }
                    assertEquals(if (at == null) "5m" else "1h", query["interval"], name)
                    assertEquals("false", query["includePrePost"]); assertEquals("div,splits,capitalGains", query["events"])
                    if (at == null) assertEquals("1d", query["range"]) else {
                        val zone = securityMarketZone(symbols.single())
                        assertEquals(at.local.toLocalDate().minusDays(3).atStartOfDay(zone).toEpochSecond().toString(), query["period1"])
                        assertEquals(at.local.toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond().toString(), query["period2"])
                        assertFalse("range" in query)
                    }
                }
            } finally { server.stop(0) }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
