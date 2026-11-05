package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.sun.net.httpserver.HttpServer
import io.github.ccxt.exchanges.Kraken
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.server.application.*
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class SyncHttpCancellationTest {
    @Test fun cancellingServerRequestInterruptsSdkAndPreventsFinancialWrite() = testApplication {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
        val spaces = WorkspaceService(db); val name = "同步取消验证"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        val account = db.write { c ->
            deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "Broker", "type" to "crypto", "active" to true,
                "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
            c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).fetchSingle(ACCOUNTS.ID)!!
        }
        val arrived = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
        val requests = AtomicInteger(); val owner = AtomicReference<Kraken>(); val requestJob = AtomicReference<Job>()
        val pool = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = pool
            createContext("/") { request ->
                request.requestBody.readAllBytes(); requests.incrementAndGet(); arrived.countDown(); release.await(15, TimeUnit.SECONDS)
                try { request.sendResponseHeaders(200, 0); request.responseBody.close() } catch (_: java.io.IOException) { request.close() }
            }
            start()
        }
        val factory = InvestmentConnectorFactory(credentials = { mapOf("api_key" to "synthetic-key", "api_secret" to "c3ludGhldGljLXNlY3JldA==") }, ccxt = { _, values ->
            val sdk = Kraken(mapOf("apiKey" to values.getValue("api_key"), "secret" to values.getValue("api_secret"), "enableRateLimit" to false)).apply {
                val url = "http://127.0.0.1:${server.address.port}"; urls["api"] = mapOf("public" to url, "private" to url); markets = emptyMap<String, Any>()
            }
            owner.set(sdk); CcxtSdkClient(sdk)
        })
        application {
            intercept(ApplicationCallPipeline.Call) { requestJob.set(currentCoroutineContext()[Job]); proceed() }
            financeModule(db, syncOperations = SyncHttpService(db, factory = { provider ->
            val connector = factory.create(provider)
            object : InvestmentConnector {
                override val sourceType = connector.sourceType
                override fun fetch(since: String?): ConnectorResult = try { connector.fetch(since) } finally { exited.countDown() }
            }
        })) }
        try {
            coroutineScope {
                val request = launch { client.post("/api/v1/operations/sync") {
                    header(HttpHeaders.Authorization, "Bearer synthetic-admin"); contentType(ContentType.Application.Json)
                    setBody("""{"source":"kraken","account":"Broker"}""")
                } }
                assertTrue(withContext(Dispatchers.IO) { arrived.await(5, TimeUnit.SECONDS) })
                requestJob.get().cancel()
                request.cancelAndJoin()
                assertTrue(withContext(Dispatchers.IO) { exited.await(7, TimeUnit.SECONDS) }, "route cancellation must reach SDK")
            }
            assertTrue(owner.get().httpClient.isTerminated); assertEquals(1, requests.get())
            db.read { c ->
                assertEquals(0, c.jooq(db.sqlite).fetchCount(INVESTMENT_EVENTS, INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)))
                val context = CashSqlContext(c, workspace, db.sqlite)
                assertNull(SyncCursorRepository(context).get(account, "kraken_api"))
                assertEquals(JsonObject(emptyMap()), context.snapshot.load().value.getValue("accounts").jsonObject.getValue("security"))
            }
        } finally {
            release.countDown(); server.stop(0); pool.shutdownNow(); owner.get()?.httpClient?.shutdownNow()
            spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a")
        }
    }
}
