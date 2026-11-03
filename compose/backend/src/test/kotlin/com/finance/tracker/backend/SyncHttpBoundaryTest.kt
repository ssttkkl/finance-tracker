package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.Role
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncHttpBoundaryTest {
    private fun database(): Database {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"))
    }
    private fun event() = Json.parseToJsonElement("""{"record_type":"funding","record_subtype":"external","from_ticker":"","from_amount":"0","to_ticker":"usd","to_amount":"100","commission":"0","commission_asset":"","occurred_at":"2026-07-26T00:00:00Z","record_id":"deposit","source_payload":{}}""").jsonObject
    private fun fixture(block: (Database, String, Long, Principal) -> Unit) {
        val db = database(); val spaces = WorkspaceService(db); val name = "同步接口边界"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            val id = db.write { c ->
                deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to "Broker", "type" to "crypto", "active" to true,
                    "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).fetchSingle(ACCOUNTS.ID)!!
            }
            block(db, workspace, id, ReadServices(db).requireContext("synthetic-admin"))
        } finally {
            Thread.interrupted()
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
            spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a")
        }
    }
    private fun empty(db: Database, workspace: String, account: Long) = db.read { c ->
        assertEquals(0, c.jooq(db.sqlite).fetchCount(INVESTMENT_EVENTS, INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)))
        val context = CashSqlContext(c, workspace, db.sqlite)
        assertEquals(JsonObject(emptyMap()), context.snapshot.load().value.getValue("accounts").jsonObject.getValue("security"))
        assertNull(SyncCursorRepository(context).get(account, "kraken_api"))
    }
    @Test fun editorIsRejectedBeforeMalformedBodyOrFactory() = testApplication {
        val db = database(); var calls = 0
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq("fixture-a")).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("viewer")).execute() }
        application { financeModule(db, syncOperations = SyncHttpService(db, factory = { calls++; error("unreachable") })) }
        try {
            val response = client.post("/api/v1/operations/sync") { header(HttpHeaders.Authorization, "Bearer synthetic-viewer"); setBody("broken") }
            assertEquals(403, response.status.value); assertEquals(0, calls)
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"workspace_forbidden","message":"只有管理员可以执行该操作。"}}"""), Json.parseToJsonElement(response.bodyAsText()))
        } finally { db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq("fixture-a")).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("viewer")).execute() } }
    }
    @Test fun demotionAndWorkspaceSwitchAfterFetchPreventEveryWrite() = fixture { db, workspace, account, principal ->
        for (change in listOf("demotion", "workspace")) {
            val connector = object : InvestmentConnector {
                override val sourceType = "kraken_api"
                override fun fetch(since: String?): ConnectorResult {
                    if (change == "demotion") db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
                    else WorkspaceService(db).select("synthetic-admin", "fixture-a")
                    return ConnectorResult(listOf(event()), "42", 1)
                }
            }
            assertFailsWith<AccessFailure> { SyncHttpService(db, factory = { connector }).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 500)) }
            empty(db, workspace, account)
            db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
            WorkspaceService(db).select("synthetic-admin", workspace)
        }
    }
    @Test fun interruptedBeforeCommitRollsBackEventsSnapshotAndCursor() = fixture { db, workspace, account, principal ->
        var reached = false
        val service = SyncService(db, beforeReturn = { reached = true; Thread.currentThread().interrupt() })
        val connector = object : InvestmentConnector { override val sourceType = "kraken_api"; override fun fetch(since: String?) = ConnectorResult(listOf(event()), "42", 1) }
        try {
            assertFailsWith<InterruptedException> { SyncHttpService(db, service, { connector }).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 1)) }
        } finally { Thread.interrupted() }
        assertTrue(reached); empty(db, workspace, account)
    }
    @Test fun unauthorizedServicePrincipalDoesNotReadCredentials() = fixture { db, _, _, principal ->
        var calls = 0
        assertFailsWith<SyncHttpFailure> { SyncHttpService(db, factory = { calls++; error("unreachable") }).sync("synthetic-admin", principal.copy(role = Role.EDITOR), SyncHttpService.Request("kraken", "Broker", false, 1)) }
        assertEquals(0, calls)
    }

    @Test fun cleanupFailureAfterFetchFailureNeverFallsBackOrWrites() = fixture { db, workspace, account, principal ->
        db.write { SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).upsert(account, "kraken_api", "100") }
        var owners = 0
        val cleanup = IllegalStateException("synthetic cleanup failure")
        val factory = InvestmentConnectorFactory(credentials = { mapOf("api_key" to "synthetic", "api_secret" to "synthetic") }, ccxt = { _, _ ->
            owners++
            CcxtSdkClient(object : io.github.ccxt.exchanges.Kraken(mapOf("enableRateLimit" to false)) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.Trade>> =
                    if (owners == 1) java.util.concurrent.CompletableFuture.failedFuture(io.github.ccxt.errors.ExchangeError("400 invalid cursor"))
                    else java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                override fun fetchLedger(code: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.LedgerEntry>> = java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                override fun close(clean: Boolean): java.util.concurrent.CompletableFuture<Any> {
                    if (owners == 1) throw cleanup
                    return super.close(clean)
                }
            })
        })
        val failure = assertFailsWith<SyncCleanupFailure> { SyncHttpService(db, factory = factory::create).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 1)) }
        assertSame(cleanup, failure.cause); assertIs<ConnectorFailure>(failure.suppressed.single())
        assertEquals(1, owners)
        db.read { c ->
            assertEquals("100", SyncCursorRepository(CashSqlContext(c, workspace, db.sqlite)).get(account, "kraken_api"))
            assertEquals(0, c.jooq(db.sqlite).fetchCount(INVESTMENT_EVENTS, INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)))
        }
    }

    @Test fun cancellationAndFatalErrorsDuringCloseKeepTheirIdentity() {
        for (error in listOf(InterruptedException("closing interrupted"), java.util.concurrent.CancellationException("closing cancelled"), AssertionError("closing invariant"))) {
            val factory = InvestmentConnectorFactory(credentials = { emptyMap() }, ccxt = { _, _ ->
                CcxtSdkClient(object : io.github.ccxt.exchanges.Kraken(mapOf("enableRateLimit" to false)) {
                    override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.Trade>> = java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                    override fun fetchLedger(code: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.LedgerEntry>> = java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                    override fun close(clean: Boolean): java.util.concurrent.CompletableFuture<Any> = throw error
                })
            })
            try { assertSame(error, assertFails { factory.create("kraken").fetch(null) }) } finally { Thread.interrupted() }
        }
    }

    @Test fun successfulFetchClosesBeforeCommitAndStaleCursorUsesNewOwner() = fixture { db, workspace, account, principal ->
        db.write { SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).upsert(account, "kraken_api", "100") }
        val owners = mutableListOf<io.github.ccxt.exchanges.Kraken>(); val closed = mutableListOf<Int>(); val cursors = mutableListOf<Long?>()
        val factory = InvestmentConnectorFactory(credentials = { emptyMap() }, ccxt = { _, _ ->
            val index = owners.size
            val sdk = object : io.github.ccxt.exchanges.Kraken(mapOf("enableRateLimit" to false)) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.Trade>> {
                    cursors.add(since)
                    return if (index == 0) java.util.concurrent.CompletableFuture.failedFuture(io.github.ccxt.errors.ExchangeError("400 invalid cursor"))
                    else java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                }
                override fun fetchLedger(code: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.LedgerEntry>> = java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                override fun close(clean: Boolean): java.util.concurrent.CompletableFuture<Any> {
                    closed.add(index); return super.close(clean)
                }
            }
            owners.add(sdk); CcxtSdkClient(sdk)
        })
        var checked = false
        val service = SyncService(db, afterFetch = { checked = true; assertEquals(listOf(0, 1), closed); assertTrue(owners.all { it.httpClient.isTerminated }) })
        val result = SyncHttpService(db, service, factory::create).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 500))
        assertEquals(JsonPrimitive(true), result["ok"]); assertTrue(checked); assertEquals(listOf<Long?>(100, null), cursors)
        db.read { assertEquals("100", SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).get(account, "kraken_api")) }
    }
    @Test fun closeFailureAfterSuccessfulFetchDoesNotWrite() = fixture { db, workspace, account, principal ->
        val failure = IllegalStateException("close failed")
        val factory = InvestmentConnectorFactory(credentials = { emptyMap() }, ccxt = { _, _ ->
            CcxtSdkClient(object : io.github.ccxt.exchanges.Kraken(mapOf("enableRateLimit" to false)) {
                override fun fetchMyTrades(symbol: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.Trade>> = java.util.concurrent.CompletableFuture.completedFuture(emptyList())
                override fun fetchLedger(code: String?, since: Long?, limit: Long?, parameters: Map<String, Any>?): java.util.concurrent.CompletableFuture<List<io.github.ccxt.types.LedgerEntry>> = java.util.concurrent.CompletableFuture.completedFuture(listOf(io.github.ccxt.types.LedgerEntry(mapOf("id" to "deposit", "timestamp" to 1000, "currency" to "USD", "amount" to 1.0, "info" to mapOf("type" to "deposit")))))
                override fun close(clean: Boolean): java.util.concurrent.CompletableFuture<Any> = throw failure
            })
        })
        assertSame(failure, assertFailsWith<SyncCleanupFailure> { SyncHttpService(db, factory = factory::create).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 500)) }.cause)
        empty(db, workspace, account)
    }

    @Test fun nonHttpSyncRetainsOriginalEditorPermission() = fixture { db, workspace, _, _ ->
        db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() }
        val connector = object : InvestmentConnector { override val sourceType = "kraken_api"; override fun fetch(since: String?) = ConnectorResult() }
        assertEquals(JsonPrimitive(true), SyncService(db).sync("synthetic-admin", "kraken", "Broker", connector = connector)["ok"])
    }
    @Test fun sessionExpiringDuringFetchCannotWrite() = fixture { db, workspace, account, principal ->
        val time = java.util.concurrent.atomic.AtomicReference(java.time.Instant.parse("2026-11-03T00:00:00Z"))
        val connector = object : InvestmentConnector {
            override val sourceType = "kraken_api"
            override fun fetch(since: String?): ConnectorResult {
                time.set(java.time.Instant.parse("2100-01-01T00:00:00Z")); return ConnectorResult(listOf(event()), "42", 1)
            }
        }
        assertFailsWith<AccessFailure> { SyncHttpService(db, SyncService(db, now = time::get), { connector }).sync("synthetic-admin", principal, SyncHttpService.Request("kraken", "Broker", false, 1)) }
        empty(db, workspace, account)
    }
}
