package com.finance.tracker.backend

import com.sun.net.httpserver.HttpServer
import io.github.ccxt.Exchange
import io.github.ccxt.exchanges.Binance
import io.github.ccxt.exchanges.Kraken
import io.github.ccxt.exchanges.Okx
import java.math.BigInteger
import java.net.URI
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CcxtSdkLoopbackTest {
    private fun native(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { native(it.value) }.toMutableMap()
        is JsonArray -> value.map(::native)
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.double
    }
    private fun exchange(provider: String): Exchange {
        val options = mapOf("apiKey" to "synthetic-key", "secret" to "c3ludGhldGljLXNlY3JldA==", "password" to "synthetic-pass", "enableRateLimit" to false, "timeout" to 2000)
        return when (provider) {
            "kraken" -> object : Kraken(options) {
                override fun nonce(): Long = 1700000000123L
                override fun incrementingNonce(): Any = 1700000000123L
            }
            "binance" -> object : Binance(options) {
                override fun nonce(): Long = 1700000000123L
                override fun milliseconds(): Long = 1700000000123L
            }
            else -> object : Okx(options) { override fun nonce(): Long = 1700000000123L }
        }
    }
    private fun normalizedCalls(calls: JsonArray): JsonArray = JsonArray(calls.map { item ->
        val row = item.jsonObject; val headers = row.getValue("headers").jsonObject
        val path = row.getValue("path").jsonPrimitive.content
        if ("ok-access-sign" in headers) {
            val message = headers.getValue("ok-access-timestamp").jsonPrimitive.content + row.getValue("method").jsonPrimitive.content + path +
                row["body"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content.orEmpty()
            val hmac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("c3ludGhldGljLXNlY3JldA==".toByteArray(), "HmacSHA256")) }
            assertEquals(Base64.getEncoder().encodeToString(hmac.doFinal(message.toByteArray())), headers.getValue("ok-access-sign").jsonPrimitive.content)
            val uri = URI.create(path)
            JsonObject(row + mapOf("path" to JsonPrimitive(uri.rawPath + (uri.rawQuery?.split('&')?.sorted()?.joinToString("&", prefix = "?") ?: "")),
                "headers" to JsonObject(headers - "ok-access-sign")))
        } else row
    })
    @Suppress("UNCHECKED_CAST")
    @Test fun realSdkRequestsSignaturesAndFullResultsMatchPython() {
        val fixture = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CCXT_SDK_CONTRACT"))).readText()).jsonObject
        assertEquals("4.5.90", fixture.getValue("version").jsonPrimitive.content)
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(13, cases.size)
        val problems = mutableListOf<String>()
        for (element in cases) {
            val case = element.jsonObject; val name = case.getValue("name").jsonPrimitive.content
            val provider = case.getValue("provider").jsonPrimitive.content
            val calls = CopyOnWriteArrayList<JsonObject>()
            val responses = case.getValue("responses").jsonObject.mapValues { it.value.jsonArray.toMutableList() }
            val pool = Executors.newCachedThreadPool()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = pool
            server.createContext("/") { request ->
                val body = request.requestBody.readAllBytes().toString(Charsets.UTF_8).takeIf { it.isNotEmpty() }
                val headers = request.requestHeaders.entries.filter { it.key.lowercase() in setOf("api-key", "api-sign", "x-mbx-apikey", "ok-access-key", "ok-access-sign", "ok-access-timestamp", "ok-access-passphrase") }
                    .associate { it.key.lowercase() to JsonPrimitive(it.value.single()) }
                calls.add(buildJsonObject {
                    put("path", request.requestURI.toString()); put("method", request.requestMethod)
                    put("body", body?.let(::JsonPrimitive) ?: JsonNull); put("headers", JsonObject(headers))
                })
                val queue = responses[request.requestURI.path]
                val payload = synchronized(responses) { if (queue.isNullOrEmpty()) null else queue.removeAt(0) }
                val status = if (payload == null) 500 else case["status"]?.jsonPrimitive?.int ?: 200
                val bytes = (payload ?: buildJsonObject { put("unexpected_endpoint", request.requestURI.path) }).toString().toByteArray()
                request.responseHeaders.set("Content-Type", "application/json")
                request.sendResponseHeaders(status, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
            }
            server.start()
            val sdk = exchange(provider)
            val base = "http://127.0.0.1:${server.address.port}"
            val api = sdk.urls["api"]
            sdk.urls["api"] = if (api is Map<*, *>) api.keys.associateWith { base + if (provider == "binance") "/api/v3" else "" }.toMutableMap() else base
            if (provider == "binance") {
                sdk.options["fetchMarkets"] = mapOf("types" to listOf("spot")); sdk.has["fetchCurrencies"] = false
            }
            if (case["cold"]?.jsonPrimitive?.boolean != true) sdk.setMarkets(listOf(native(fixture.getValue("market"))), native(fixture.getValue("currencies")))
            val client = CcxtSdkClient(sdk)
            try {
                val expected = case.getValue("expected")
                val actual = try {
                    val since = case["since"]?.jsonPrimitive?.content
                    when (case.getValue("mode").jsonPrimitive.content) {
                        "connector" -> {
                            val result = CcxtExchangeConnector(provider, client, sleep = { }).fetch(since)
                            buildJsonObject { put("events", JsonArray(result.events)); put("next_cursor", result.nextCursor?.let(::JsonPrimitive) ?: JsonNull); put("raw_count", result.rawCount) }
                        }
                        "trades" -> JsonArray(client.trades(since?.let(::BigInteger), 500))
                        "ledger" -> JsonArray(client.ledger(since?.let(::BigInteger), 500, case["params"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap()))
                        else -> sourceJson((sdk as Binance).privateGetMyTrades(mapOf("symbol" to "BTCUSDT", "limit" to 500)).get())
                    }
                } catch (error: CcxtCallFailure) {
                    buildJsonObject { put("error", error.originalType); put("message", error.message.orEmpty().replace(base, "<origin>").replace("503 null ", "503 Service Unavailable ")) }
                } catch (error: ConnectorFailure) {
                    buildJsonObject {
                        put("error", when (error) { is ConnectorDataFailure -> "ConnectorDataError"; is ConnectorAuthFailure -> "ConnectorAuthError"; else -> "ConnectorError" })
                        put("message", error.message.orEmpty().replace(base, "<origin>").replace("503 null ", "503 Service Unavailable "))
                    }
                }
                if (expected != actual) problems.add("$name result expected=$expected actual=$actual")
                val expectedCalls = normalizedCalls(case.getValue("calls").jsonArray)
                val actualCalls = normalizedCalls(JsonArray(calls))
                val requestsMatch = if (name == "binance-cold-default-spot") {
                    expectedCalls.size == actualCalls.size && expectedCalls.toSet() == actualCalls.toSet()
                } else expectedCalls == actualCalls
                if (!requestsMatch) problems.add("$name requests expected=${case["calls"]} actual=${JsonArray(calls)}")
            } finally {
                client.close(); assertTrue(sdk.httpClient.isTerminated)
                server.stop(0); pool.shutdownNow()
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
