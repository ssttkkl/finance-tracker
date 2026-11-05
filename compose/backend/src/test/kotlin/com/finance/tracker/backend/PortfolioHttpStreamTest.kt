package com.finance.tracker.backend

import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioHttpStreamTest {
    private fun connection(server: PortfolioHttpServer, path: String = "", last: String? = null) = (java.net.URI.create(server.origin + "/api/v1/investment-portfolio/stream" + path).toURL().openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).apply {
        setRequestProperty("Authorization", "Bearer synthetic-admin"); last?.let { setRequestProperty("Last-Event-ID", it) }; readTimeout = 3000; connectTimeout = 3000
    }
    private fun event(reader: java.io.BufferedReader): String = buildString { while (true) { val line = reader.readLine() ?: break; append(line).append('\n'); if (line.isEmpty()) break } }
    private fun await(test: () -> Boolean) { val end = System.nanoTime() + 3_000_000_000; while (!test() && System.nanoTime() < end) Thread.sleep(10); assertTrue(test()) }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun actualStreamHeadersFramesReplayHeartbeatDisconnectAndShutdown() {
        val db = portfolioHttpDatabase(); val release = CountDownLatch(1); val published = CountDownLatch(1)
        val value = portfolioHttpValue()
        val service = PortfolioHttpService(db, factory = { object : PortfolioRead {
            override fun holdings() = value
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject { publish?.invoke(value); published.countDown(); release.await(3, TimeUnit.SECONDS); return value }
        } }, coordinatorFactory = { PortfolioRefreshCoordinator(it, refreshIntervalSeconds = 60.0, heartbeatSeconds = 0.1) })
        val server = PortfolioHttpServer(db, service)
        try {
            val first = connection(server)
            val version: String
            try {
                assertEquals(200, first.responseCode); assertTrue(first.contentType.startsWith("text/event-stream")); assertEquals("no-cache", first.getHeaderField("Cache-Control")); assertEquals("no", first.getHeaderField("X-Accel-Buffering"))
                first.inputStream.bufferedReader().use { reader ->
                    var frame = event(reader); while (frame.startsWith(":")) frame = event(reader)
                    assertTrue(published.await(1, TimeUnit.SECONDS)); assertTrue(frame.contains("event: portfolio\n"), frame); version = frame.lineSequence().first().removePrefix("id: ")
                    assertEquals(value, Json.parseToJsonElement(frame.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject["portfolio"])
                    assertEquals(": keepalive\n\n", event(reader))
                }
            } finally { first.disconnect() }
            await { service.activeSubscriptions() == 0 }
            val replay = connection(server, last = (version.toBigInteger() - BigInteger.ONE).toString())
            try { replay.inputStream.bufferedReader().use { reader ->
                var frame = event(reader); while (frame.startsWith(":")) frame = event(reader)
                assertTrue(frame.startsWith("id: $version\nevent: portfolio\n"), frame)
            } } finally { replay.disconnect() }
            await { service.activeSubscriptions() == 0 }
        } finally { release.countDown(); server.close() }
        assertEquals(0, service.activeWorkers()); assertEquals(0, service.activeSubscriptions())
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun invalidScopeIsAcceptedThenReportedAsBackgroundError() {
        val db = portfolioHttpDatabase(); val service = PortfolioHttpService(db, coordinatorFactory = { PortfolioRefreshCoordinator(it, heartbeatSeconds = 0.1) })
        PortfolioHttpServer(db, service).use { server ->
            val (status, body) = server.request("/api/v1/investment-portfolio/refresh?period=invalid", "POST")
            assertEquals(202, status, body); assertEquals(Json.parseToJsonElement("""{"accepted":true}"""), Json.parseToJsonElement(body))
            val connection = connection(server, "?period=invalid", "invalid")
            try { connection.inputStream.bufferedReader().use { reader ->
                var frame = event(reader); while (frame.startsWith(":")) frame = event(reader)
                assertTrue(frame.contains("event: refresh_error\n"), frame)
                val data = Json.parseToJsonElement(frame.lineSequence().first { it.startsWith("data: ") }.removePrefix("data: ")).jsonObject
                assertEquals(setOf("version"), data.keys)
            } } finally { connection.disconnect() }
        }
        assertEquals(0, service.activeSubscriptions()); assertEquals(0, service.activeWorkers())
    }
    @Test fun lastEventIdUsesPythonIntegerAndFramesHaveExactFields() {
        for ((raw, expected) in listOf(null to null, "" to null, "x" to null, "1.0" to null, "  +1_２  " to BigInteger.valueOf(12), "-99999999999999999999999" to BigInteger("-99999999999999999999999"), "1".repeat(4301) to null)) assertEquals(expected, portfolioLastVersion(raw), raw)
        assertEquals(": keepalive\n\n", portfolioSseFrame(PortfolioStreamUpdate(BigInteger.ZERO, "heartbeat")))
        assertEquals("id: 9\nevent: refresh_error\ndata: {\"version\":9}\n\n", portfolioSseFrame(PortfolioStreamUpdate(BigInteger.valueOf(9), "refresh_error")))
    }
    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
    @Test fun shutdownWaitsForActualInFlightWorkerAndRejectsNewWork() {
        val db = portfolioHttpDatabase(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val service = PortfolioHttpService(db, factory = { object : PortfolioRead {
            override fun holdings() = portfolioHttpValue()
            override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject { entered.countDown(); release.await(); return holdings() }
        } })
        val server = PortfolioHttpServer(db, service); val principal = ReadServices(db).currentContext("synthetic-admin")
        assertEquals(202, server.request("/api/v1/investment-portfolio/refresh", "POST").first); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val shutdown = Thread { server.close(); stopped.countDown() }.apply { isDaemon = true; start() }
        try { assertFalse(stopped.await(100, TimeUnit.MILLISECONDS)); assertEquals(1, service.activeWorkers()) }
        finally { release.countDown(); shutdown.join(5000) }
        assertEquals(0, stopped.count); assertEquals(0, service.activeWorkers())
        assertFailsWith<IllegalStateException> { service.request("synthetic-admin", principal, PortfolioRefreshScope(null, "24h", null)) }
    }
}
