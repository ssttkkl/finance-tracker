package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.sun.net.httpserver.HttpServer
import io.github.ccxt.exchanges.Kraken
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class SyncNettySdkTest {
    @Test fun realHttpThroughCredentialsSdkMapperAndTransactionIsIdempotent() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
        val spaces = WorkspaceService(db); val name = "真实同步接口"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        val directory = Files.createTempDirectory("ft-sync-synthetic-")
        directory.resolve("credentials.yaml").writeText("kraken:\n  api_key: synthetic-key\n  api_secret: c3ludGhldGljLXNlY3JldA==\n")
        val keys = CopyOnWriteArrayList<String>(); val owners = CopyOnWriteArrayList<Kraken>()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request ->
                request.requestBody.readAllBytes(); keys.add(request.requestHeaders.getFirst("API-Key"))
                val body = if (request.requestURI.path.endsWith("TradesHistory")) """{"error":[],"result":{"trades":{}}}"""
                    else """{"error":[],"result":{"ledger":{"deposit":{"refid":"r","time":1700000000,"type":"deposit","asset":"ZUSD","amount":"100","fee":"0","balance":"100"}}}}"""
                val bytes = body.toByteArray(); request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val credentials = ConnectorCredentials(mapOf("FT_CREDENTIALS_DIR" to directory.toString()))
        val factory = InvestmentConnectorFactory(credentials::exchange, ccxt = { _, values ->
            val sdk = Kraken(mapOf("apiKey" to values.getValue("api_key"), "secret" to values.getValue("api_secret"), "enableRateLimit" to false)).apply {
                val url = "http://127.0.0.1:${upstream.address.port}"; urls["api"] = mapOf("public" to url, "private" to url)
                markets = emptyMap<String, Any>(); currencies_by_id = mapOf("ZUSD" to mapOf("id" to "ZUSD", "code" to "USD"))
            }
            owners.add(sdk); CcxtSdkClient(sdk)
        })
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { financeModule(db, syncOperations = SyncHttpService(db, factory = factory::create)) }
        try {
            val account = db.write { c ->
                deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "Broker", "type" to "crypto", "active" to true,
                    "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).fetchSingle(ACCOUNTS.ID)!!
            }
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            HttpClient.newHttpClient().use { client ->
                for (count in listOf(1, 0)) {
                    val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/api/v1/operations/sync"))
                        .header("Authorization", "Bearer synthetic-admin").header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""{"source":"kraken","account":"Broker","full":true}""")).build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                    assertEquals(200, response.statusCode(), response.body())
                    val body = Json.parseToJsonElement(response.body()).jsonObject
                    assertEquals(JsonPrimitive(true), body["ok"]); assertEquals(JsonPrimitive(count), body["count"])
                    assertFalse("synthetic-key" in response.body()); assertTrue(owners.all { it.httpClient.isTerminated })
                }
            }
            assertEquals(List(4) { "synthetic-key" }, keys); assertEquals(2, owners.size)
            db.read { c ->
                assertEquals(1, c.jooq(db.sqlite).fetchCount(INVESTMENT_EVENTS, INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)))
                val context = CashSqlContext(c, workspace, db.sqlite)
                assertEquals("1700000000001", SyncCursorRepository(context).get(account, "kraken_api"))
                val position = context.snapshot.load().value.getValue("accounts").jsonObject.getValue("security").jsonObject.getValue("Broker").jsonObject.getValue("positions").jsonObject.getValue("usd").jsonObject
                assertEquals(JsonPrimitive("100"), position["shares"])
            }
        } finally {
            server.stop(100, 1000); upstream.stop(0); owners.forEach { it.httpClient.shutdownNow() }; directory.toFile().deleteRecursively()
            spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a")
        }
    }

    @Test fun polymarketFactoryUsesDefaultActivityAndPinnedBalanceTransport() {
        val wallet = "0x" + "a".repeat(40); val methods = CopyOnWriteArrayList<String>(); val paths = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { request ->
                paths.add(request.requestURI.path)
                val payload = if (request.requestURI.path == "/activity") {
                    assertEquals("user=$wallet&limit=500&offset=0", request.requestURI.rawQuery); "[]"
                } else {
                    val body = Json.parseToJsonElement(request.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
                    val method = body.getValue("method").jsonPrimitive.content; methods.add(method)
                    val result = when (method) {
                        "eth_blockNumber" -> JsonPrimitive("0xc")
                        "eth_getBlockByNumber" -> { assertEquals(Json.parseToJsonElement("[\"0xc\",false]"), body["params"]); buildJsonObject { put("timestamp", "0x1") } }
                        "eth_call" -> {
                            assertEquals(JsonArray(listOf(buildJsonObject { put("to", PolymarketConnector.PUSD_TOKEN); put("data", "0x70a08231" + "0".repeat(24) + wallet.drop(2)) }, JsonPrimitive("0xc"))), body["params"])
                            JsonPrimitive("0x12d687")
                        }
                        else -> error("unexpected RPC")
                    }
                    buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("result", result) }.toString()
                }
                val bytes = payload.toByteArray(); request.sendResponseHeaders(200, bytes.size.toLong()); request.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val factory = InvestmentConnectorFactory(credentials = { assertEquals("polymarket", it); mapOf("proxy_wallet" to wallet) },
                ccxt = { _, _ -> error("wrong factory branch") },
                polymarket = { values -> PolymarketConnector(values, transport = PolymarketHttp(base, "$base/profile", listOf("$base/rpc"), emptyMap())) })
            val connector = factory.create("polymarket"); assertEquals("polymarket_api", connector.sourceType)
            val result = connector.fetch(null)
            assertEquals(listOf("/activity", "/rpc", "/rpc", "/rpc"), paths)
            assertEquals(listOf("eth_blockNumber", "eth_getBlockByNumber", "eth_call"), methods)
            assertEquals(JsonPrimitive("1.234567"), result.events.single()["to_amount"]); assertNull(result.nextCursor)
        } finally { server.stop(0) }
    }
}
