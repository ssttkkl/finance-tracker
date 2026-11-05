package com.finance.tracker.backend

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.*
import kotlinx.serialization.json.*

class PolymarketHttpTest {
    private val wallet = "0x" + "a".repeat(40)
    private fun server(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0); server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}") } finally { server.stop(0) }
    }
    private fun respond(exchange: HttpExchange, text: String, code: Int = 200) {
        val bytes = text.toByteArray(Charsets.UTF_8); exchange.sendResponseHeaders(code, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }; exchange.close()
    }
    @Test fun malformedJsonAndUtf8BomRetryAsProtocolFailures() = server { server, base ->
        var calls = 0; var body = "not-json"; val delays = mutableListOf<Long>()
        server.createContext("/activity") { calls++; respond(it, body) }
        for (invalid in listOf("not-json", "\uFEFF[]", "[\u0000]\u0000")) {
            calls = 0; body = invalid; delays.clear()
            assertFailsWith<ConnectorFailure> { PolymarketConnector(mapOf("proxy_wallet" to wallet), transport = PolymarketHttp(api = base, environment = emptyMap()), sleep = { delays += it }).fetch(null) }
            assertEquals(3, calls); assertEquals(listOf(1000L, 2000L), delays)
        }
    }
    @Test fun defaultTransportReadsProfileActivityAndPinnedBalance() = server { server, base ->
        val requests = mutableListOf<String>(); val rpcCalls = mutableListOf<JsonObject>()
        server.createContext("/profile") { assertEquals(PolymarketHttp.USER_AGENT, it.requestHeaders.getFirst("User-Agent")); requests += it.requestURI.path; respond(it, """{\"proxyAddress\":\"${wallet.uppercase().replace("0X", "0x")}\"}""") }
        server.createContext("/activity") { assertEquals("GET", it.requestMethod); assertEquals("user=$wallet&limit=500&offset=0", it.requestURI.rawQuery); requests += "activity"; respond(it, "[]") }
        server.createContext("/rpc") {
            assertEquals("POST", it.requestMethod); assertEquals("application/json", it.requestHeaders.getFirst("Content-Type")); assertEquals(PolymarketHttp.USER_AGENT, it.requestHeaders.getFirst("User-Agent"))
            val body = Json.parseToJsonElement(it.requestBody.readBytes().toString(Charsets.UTF_8)).jsonObject; rpcCalls += body
            assertEquals(JsonPrimitive("2.0"), body["jsonrpc"]); assertEquals(JsonPrimitive(1), body["id"])
            val result = when (body.getValue("method").jsonPrimitive.content) {
                "eth_blockNumber" -> { assertEquals(JsonArray(emptyList()), body["params"]); JsonPrimitive("0xc") }
                "eth_getBlockByNumber" -> { assertEquals(JsonArray(listOf(JsonPrimitive("0xc"), JsonPrimitive(false))), body["params"]); buildJsonObject { put("timestamp", "0x1") } }
                "eth_call" -> {
                    assertEquals(JsonArray(listOf(buildJsonObject { put("to", PolymarketConnector.PUSD_TOKEN); put("data", "0x70a08231" + "0".repeat(24) + wallet.drop(2)) }, JsonPrimitive("0xc"))), body["params"])
                    JsonPrimitive("0x12d687")
                }
                else -> error("unexpected method")
            }
            respond(it, buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("result", result) }.toString())
        }
        val result = PolymarketConnector(mapOf("wallet" to wallet), transport = PolymarketHttp(base, "$base/profile", listOf("$base/rpc"), emptyMap())).fetch(null)
        assertEquals(listOf("/profile/$wallet", "activity"), requests); assertEquals(3, rpcCalls.size)
        assertEquals("1.234567", result.events.single().getValue("to_amount").jsonPrimitive.content); assertNull(result.nextCursor)
    }
    @Test fun rpcMalformedMissingNullAndErrorFallbackThenStop() = server { server, base ->
        var first = "not-json"; var count = 0; val paths = mutableListOf<String>()
        for (path in listOf("/one", "/two", "/three")) server.createContext(path) { paths += path; count++; respond(it, if (path == "/one") first else """{"result":"0xc"}""") }
        val http = PolymarketHttp(endpoints = listOf("$base/one", "$base/two", "$base/three"), environment = emptyMap())
        for (body in listOf("bad-json", "{}", "{\"result\":null}", "{\"error\":{\"message\":\"synthetic-secret\"}}")) {
            count = 0; paths.clear(); first = body
            assertEquals(JsonPrimitive("0xc"), http.rpc("eth_blockNumber", JsonArray(emptyList()))); assertEquals(2, count); assertEquals(listOf("/one", "/two"), paths)
        }
    }
    @Test fun authStopsImmediatelyAndServiceFailureRetriesThreeTimes() = server { server, base ->
        var status = 401; var calls = 0; val delays = mutableListOf<Long>()
        server.createContext("/activity") { calls++; respond(it, "synthetic-secret", status) }
        val transport = PolymarketHttp(api = base, environment = emptyMap())
        for (code in listOf(401, 403, 500)) {
            status = code; calls = 0; delays.clear()
            val failure = assertFailsWith<ConnectorFailure> { PolymarketConnector(mapOf("proxy_wallet" to wallet), transport = transport, sleep = { delays += it }).fetch(null) }
            assertEquals(code != 500, failure is ConnectorAuthFailure); assertEquals(if (code == 500) 3 else 1, calls); assertFalse(failure.toString().contains("synthetic-secret")); assertNull(failure.cause)
        }
    }
    @Test fun authenticatedHttpsProxyUsesConnectWithoutGlobalAuthenticator() {
        val original = java.net.Authenticator.getDefault()
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { proxy ->
            proxy.soTimeout = 5000
            val captured = java.util.concurrent.CompletableFuture.supplyAsync {
                proxy.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val lines = mutableListOf<String>()
                    while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; lines += line }
                    socket.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    lines
                }
            }
            val http = PolymarketHttp(environment = mapOf("https_proxy" to "http://synthetic_user:synthetic_pass@127.0.0.1:${proxy.localPort}"))
            assertFailsWith<java.io.IOException> { http.json("https://upstream.invalid/activity") }
            val lines = captured.get(6, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals("CONNECT upstream.invalid:443 HTTP/1.1", lines.first())
            assertEquals("Proxy-Authorization: Basic " + java.util.Base64.getEncoder().encodeToString("synthetic_user:synthetic_pass".toByteArray()), lines.single { it.startsWith("Proxy-Authorization:", true) })
            assertFalse(lines.any { it.startsWith("Authorization:", true) }); assertSame(original, java.net.Authenticator.getDefault())
        }
    }
    @Test fun noProxyBypassesCredentialedProxyAndNeverSendsProxyHeaderToOrigin() = server { server, base ->
        server.createContext("/activity") { assertNull(it.requestHeaders.getFirst("Proxy-Authorization")); assertNull(it.requestHeaders.getFirst("Authorization")); respond(it, "[]") }
        val http = PolymarketHttp(environment = mapOf("http_proxy" to "http://synthetic_user:synthetic_pass@127.0.0.1:1", "no_proxy" to "127.0.0.1"))
        assertEquals(JsonArray(emptyList()), http.json("$base/activity"))
    }
    @Test fun allRpcEndpointsFailThreeRoundsWithoutLeakingResponse() = server { server, base ->
        val paths = mutableListOf<String>(); val delays = mutableListOf<Long>()
        server.createContext("/activity") { respond(it, "[]") }
        for (path in listOf("/one", "/two", "/three")) server.createContext(path) { paths += path; respond(it, """{"error":{"message":"synthetic-secret"}}""") }
        val transport = PolymarketHttp(api = base, endpoints = listOf("$base/one", "$base/two", "$base/three"), environment = emptyMap())
        val failure = assertFailsWith<ConnectorFailure> { PolymarketConnector(mapOf("proxy_wallet" to wallet), transport = transport, sleep = { delays += it }).fetch(null) }
        assertEquals(List(3) { listOf("/one", "/two", "/three") }.flatten(), paths); assertEquals(listOf(1000L, 2000L), delays); assertContains(failure.message!!, "eth_blockNumber"); assertFalse(failure.toString().contains("synthetic-secret")); assertNull(failure.cause)
    }
}
