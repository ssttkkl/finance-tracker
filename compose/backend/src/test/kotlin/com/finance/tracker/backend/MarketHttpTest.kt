package com.finance.tracker.backend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class MarketHttpTest {
    private class Server(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val pool = Executors.newCachedThreadPool { task -> Thread(task, "quote-http-test").apply { isDaemon = true } }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { executor = pool; createContext("/", handler); start() }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() { server.stop(0); pool.shutdownNow() }
    }
    private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
    @Test fun realHttpKeepsPythonJsonNumbersAndHeaders() {
        val requests = CopyOnWriteArrayList<String>()
        Server { exchange ->
            assertEquals("Mozilla/5.0", exchange.requestHeaders.getFirst("User-Agent"))
            assertEquals("application/json", exchange.requestHeaders.getFirst("Accept"))
            assertNull(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            requests.add(exchange.requestURI.toString())
            respond(exchange, """{"integer":123456789012345678901234567890,"float":12.1234567890123456,"zero":-0.0,"text":"行情"}""")
        }.use { server ->
            val value = JvmQuoteHttpFetcher(emptyMap()).fetch(server.url + "/chart?x=1", 1.0).jsonObject
            assertEquals("123456789012345678901234567890E0", marketDecimal(value["integer"]))
            assertEquals("12123456789012346E-15", marketDecimal(value["float"]))
            assertEquals("-0E-1", marketDecimal(value["zero"]))
            assertEquals("行情", value["text"]!!.jsonPrimitive.content)
            assertEquals(listOf("/chart?x=1"), requests)
        }
    }
    @Test fun actualProxyPriorityCredentialsAndNoProxyMatchSource() {
        val proxyPaths = CopyOnWriteArrayList<String>(); val auth = CopyOnWriteArrayList<String?>()
        Server { exchange -> proxyPaths.add(exchange.requestURI.toString()); auth.add(exchange.requestHeaders.getFirst("Proxy-Authorization")); respond(exchange, "{\"via\":\"proxy\"}") }.use { proxy ->
            val credentialProxy = proxy.url.replace("http://", "http://user:p%2Bss%3Aword@")
            val http = JvmQuoteHttpFetcher(mapOf("HTTPS_PROXY" to credentialProxy, "HTTP_PROXY" to "http://127.0.0.1:1"))
            assertEquals("proxy", http.fetch("http://finance-fixture.invalid/quote", 1.0).jsonObject["via"]!!.jsonPrimitive.content)
            assertEquals(listOf("http://finance-fixture.invalid/quote"), proxyPaths)
            assertEquals("Basic " + Base64.getEncoder().encodeToString("user:p+ss:word".toByteArray()), auth.single())
            JvmQuoteHttpFetcher(mapOf("HTTPS_PROXY" to "", "HTTP_PROXY" to proxy.url)).fetch("http://finance-fixture.invalid/fallback", 1.0)
            assertEquals(2, proxyPaths.size)
            Server { exchange -> respond(exchange, "{\"via\":\"direct\"}") }.use { direct ->
                for (bypass in listOf("*", "127.0.0.1", ".127.0.0.1", "127.0.0.1:${direct.server.address.port}")) {
                    val value = JvmQuoteHttpFetcher(mapOf("HTTPS_PROXY" to proxy.url, "NO_PROXY" to bypass)).fetch(direct.url + "/direct", 1.0)
                    assertEquals("direct", value.jsonObject["via"]!!.jsonPrimitive.content)
                }
                JvmQuoteHttpFetcher(mapOf("HTTPS_PROXY" to proxy.url, "NO_PROXY" to "127.0.0.1", "no_proxy" to "mismatch.invalid")).fetch(direct.url + "/lowercase-wins", 1.0)
                assertEquals(3, proxyPaths.size)
                JvmQuoteHttpFetcher(mapOf("HTTPS_PROXY" to proxy.url, "NO_PROXY" to "127.0.0.1:1")).fetch(direct.url + "/port-mismatch", 1.0)
                assertEquals(4, proxyPaths.size)
            }
        }
    }
    @Test fun lowercaseProxyDiscoveryIsSchemeSpecificWhenNoUppercaseOverrideExists() {
        val paths = CopyOnWriteArrayList<String>()
        Server { exchange -> paths.add(exchange.requestURI.toString()); respond(exchange, "{\"via\":\"proxy\"}") }.use { proxy ->
            val value = JvmQuoteHttpFetcher(mapOf("http_proxy" to proxy.url)).fetch("http://finance-fixture.invalid/lowercase", 1.0)
            assertEquals("proxy", value.jsonObject["via"]!!.jsonPrimitive.content)
            assertEquals(listOf("http://finance-fixture.invalid/lowercase"), paths)
            Server { exchange -> respond(exchange, "{\"via\":\"direct\"}") }.use { direct ->
                val other = JvmQuoteHttpFetcher(mapOf("https_proxy" to proxy.url)).fetch(direct.url, 1.0)
                assertEquals("direct", other.jsonObject["via"]!!.jsonPrimitive.content)
                assertEquals(1, paths.size)
            }
        }
    }
    @Test fun realHttpTimeoutAndFailuresBecomeMissingQuotes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        Server { exchange -> entered.countDown(); release.await(3, TimeUnit.SECONDS); runCatching { respond(exchange, "{}") } }.use { slow ->
            try { assertFailsWith<java.net.SocketTimeoutException> { JvmQuoteHttpFetcher(emptyMap()).fetch(slow.url, 0.02) } }
            finally { release.countDown() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        }
        for ((body, status) in listOf("SYNTHETIC_PROVIDER_SECRET" to 503, "not-json" to 200)) {
            Server { exchange -> respond(exchange, body, status) }.use { server ->
                val actualHttp = JvmQuoteHttpFetcher(emptyMap())
                val provider = YahooChartQuoteProvider(QuoteJsonFetcher { _, timeout -> actualHttp.fetch(server.url, timeout) })
                assertNull(provider.rawQuote("aapl.us", AssetKind.SECURITY, 1.0))
            }
        }
    }
}
