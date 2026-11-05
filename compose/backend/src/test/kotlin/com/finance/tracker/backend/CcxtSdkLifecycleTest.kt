package com.finance.tracker.backend

import com.sun.net.httpserver.HttpServer
import io.github.ccxt.exchanges.Kraken
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class CcxtSdkLifecycleTest {
    private class Server(private val blocked: Boolean = false, val status: Int = 200) : AutoCloseable {
        val arrived = CountDownLatch(1); val release = CountDownLatch(1)
        val keys = CopyOnWriteArrayList<String>()
        private val pool = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { request ->
                request.requestBody.readAllBytes()
                keys.add(request.requestHeaders.getFirst("API-Key").orEmpty()); arrived.countDown()
                if (blocked) release.await(10, TimeUnit.SECONDS)
                val bytes = "{\"error\":[],\"result\":{\"trades\":{}}}".toByteArray()
                try { request.sendResponseHeaders(status, bytes.size.toLong()); request.responseBody.use { it.write(bytes) } }
                catch (_: java.io.IOException) { request.close() }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}"
        override fun close() { release.countDown(); server.stop(0); pool.shutdownNow() }
    }
    private fun sdk(url: String, key: String = "synthetic-one"): Kraken = Kraken(mapOf("apiKey" to key,
        "secret" to "c3ludGhldGljLXNlY3JldA==", "enableRateLimit" to false, "timeout" to 10000)).apply {
        urls["api"] = mapOf("public" to url, "private" to url); markets = linkedMapOf<String, Any>()
    }
    @Test fun interruptStopsRealRequestRestoresFlagAndDoesNotRetry() {
        Server(blocked = true).use { server ->
            val sdk = sdk(server.url); val client = CcxtSdkClient(sdk)
            val failed = AtomicReference<Throwable>(); val interrupted = AtomicBoolean()
            val worker = Thread {
                try { client.use { CcxtExchangeConnector("kraken", it, sleep = { error("must not retry") }).fetch(null) } }
                catch (error: Throwable) { failed.set(error); interrupted.set(Thread.currentThread().isInterrupted) }
            }
            worker.start()
            try {
                assertTrue(server.arrived.await(5, TimeUnit.SECONDS)); worker.interrupt(); worker.join(7000)
                assertFalse(worker.isAlive); assertIs<InterruptedException>(failed.get()); assertTrue(interrupted.get())
                assertEquals(1, server.keys.size); assertTrue(sdk.httpClient.isTerminated)
                assertNull(sdk.apiKey); assertNull(sdk.secret); assertNull(sdk.password)
                client.close()
            } finally { server.release.countDown(); worker.interrupt(); worker.join(3000); client.close() }
        }
    }
    @Test fun closingOneOwnerDoesNotStopAnotherOrMixCredentials() {
        Server().use { server ->
            val first = sdk(server.url, "synthetic-one"); val second = sdk(server.url, "synthetic-two")
            CcxtSdkClient(first).use { a ->
                CcxtSdkClient(second).use { b ->
                    assertTrue(a.trades(null, 500).isEmpty()); assertTrue(b.trades(null, 500).isEmpty())
                    a.close(); assertTrue(first.httpClient.isTerminated)
                    assertTrue(b.trades(null, 500).isEmpty())
                    assertEquals(listOf("synthetic-one", "synthetic-two", "synthetic-two"), server.keys)
                }
            }
            assertTrue(second.httpClient.isTerminated)
        }
    }
    @Test fun actual503KeepsOriginalThreeAttemptsAndBackoff() {
        Server(status = 503).use { server ->
            val sdk = sdk(server.url); val waits = mutableListOf<Long>()
            CcxtSdkClient(sdk).use { client ->
                val failure = assertFailsWith<ConnectorFailure> { CcxtExchangeConnector("kraken", client, sleep = waits::add).fetch(null) }
                assertTrue(failure.message.orEmpty().startsWith("kraken failed after 3 retries:"))
                assertEquals(listOf(1000L, 2000L), waits); assertEquals(3, server.keys.size)
            }
            assertTrue(sdk.httpClient.isTerminated)
        }
    }
    @Test fun trackedTransportCancelsActualFutureForBothAsyncOverloads() {
        for (push in listOf(false, true)) Server(blocked = true).use { server ->
            val client = CcxtHttpClient(HttpClient.newHttpClient())
            try {
                val request = HttpRequest.newBuilder(URI.create(server.url)).build()
                val future = if (push) client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray(), HttpResponse.PushPromiseHandler { _, _, _ -> })
                    else client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                assertTrue(server.arrived.await(5, TimeUnit.SECONDS))
                client.shutdownNow()
                val cancelled = assertFails { future.get(2, TimeUnit.SECONDS) }
                val cause = if (cancelled is java.util.concurrent.ExecutionException) cancelled.cause else cancelled
                assertIs<java.util.concurrent.CancellationException>(cause, "push=$push")
                assertTrue(client.awaitTermination(Duration.ofSeconds(5)), "transport termination push=$push")
                assertFailsWith<IllegalStateException> { client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()) }
            } finally { client.shutdownNow() }
        }
    }

    @org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)

    @Test fun realSdkHttpsValidatesTrustedLoopbackCertificate() {
        val store = java.security.KeyStore.getInstance("PKCS12")
        java.nio.file.Files.newInputStream(java.nio.file.Path.of(requireNotNull(System.getenv("FT_KOTLIN_CCXT_TLS_KEYSTORE")))).use {
            store.load(it, "synthetic-only".toCharArray())
        }
        val keys = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, "synthetic-only".toCharArray())
        }
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val serverTls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        val clientTls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        val requests = CopyOnWriteArrayList<String>()
        val server = com.sun.net.httpserver.HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = com.sun.net.httpserver.HttpsConfigurator(serverTls)
            createContext("/") { request ->
                requests.add(request.requestURI.path); request.requestBody.readAllBytes()
                val body = "{\"error\":[],\"result\":{\"trades\":{}}}".toByteArray()
                request.sendResponseHeaders(200, body.size.toLong()); request.responseBody.use { it.write(body) }
            }
            start()
        }
        val sdk = sdk("https://127.0.0.1:${server.address.port}")
        sdk.httpClient.shutdownNow()
        sdk.httpClient = HttpClient.newBuilder().sslContext(clientTls).build()
        try {
            CcxtSdkClient(sdk).use { assertTrue(it.trades(null, 500).isEmpty()) }
            assertEquals(listOf("/0/private/TradesHistory"), requests); assertTrue(sdk.httpClient.isTerminated)
        } finally { server.stop(0); sdk.httpClient.shutdownNow() }
    }
}
