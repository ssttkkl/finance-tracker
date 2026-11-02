package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class SyncServiceTest {
    private fun fixture(block: (Database, String, Long) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val spaces = WorkspaceService(db); val name = "同步合同验证"; val workspace = spaces.create("synthetic-admin", name).activeWorkspaceId!!
        try {
            val id = db.write { c ->
                for ((account, type) in listOf("TestCrypto" to "crypto", "CashAccount" to "cash", "Other" to "crypto"))
                    deletionInsert(c, db, "accounts", linkedMapOf("workspace_id" to workspace, "name" to account, "type" to type, "active" to true,
                        "revision" to 1L, "currencies" to JsonArray(emptyList()), "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime, "updated_at" to deletionTime))
                c.jooq(db.sqlite).select(ACCOUNTS.ID).from(ACCOUNTS).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.NAME.eq("TestCrypto")).fetchSingle(ACCOUNTS.ID)!!
            }
            block(db, workspace, id)
        } finally { spaces.select("synthetic-admin", workspace); spaces.delete("synthetic-admin", name); spaces.select("synthetic-admin", "fixture-a") }
    }
    private fun event(id: String, ticker: String = "eth", from: String = "usdt", time: String = "2024-01-01T00:00:00Z") = buildJsonObject {
        put("record_type", "trade"); put("record_subtype", "security"); put("account", ""); put("currency", "USD"); put("occurred_at", time)
        put("from_ticker", from); put("from_amount", "100"); put("to_ticker", ticker); put("to_amount", "1"); put("commission", "0"); put("commission_asset", "")
        put("note", "test trade $id"); put("record_id", id); put("source_payload", buildJsonObject { put("id", id) })
    }
    private class Fake(val events: List<JsonObject> = emptyList(), val cursor: String? = null, val raw: Int = 0, val failure: ConnectorFailure? = null) : InvestmentConnector {
        override val sourceType = "test_api"
        val calls = mutableListOf<String?>()
        override fun fetch(since: String?): ConnectorResult { calls += since; failure?.let { throw it }; return ConnectorResult(events, cursor, raw) }
    }
    private fun sync(db: Database, connector: InvestmentConnector? = Fake(), full: Boolean = false, provider: String = "binance", account: String = "TestCrypto", size: Int = 500) =
        SyncService(db).sync("synthetic-admin", provider, account, full, size, connector)
    private fun ok(value: JsonObject, count: Int? = null) { assertEquals(JsonPrimitive(true), value["ok"], value.toString()); if (count != null) assertEquals(JsonPrimitive(count), value["count"]) }
    private fun cursor(db: Database, workspace: String, id: Long, source: String = "binance_api") = db.read { SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).get(id, source) }
    private fun state(db: Database) = db.read { c -> com.finance.tracker.backend.persistence.generated.DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to c.createStatement().use { s -> s.executeQuery("SELECT * FROM \"" + table.name + "\"").use { rows ->
            buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }.sortedBy { it.toString() }
        } }
    } }
    @Test fun originalHappyEmptyAndDuplicateCounts() = fixture { db, _, _ ->
        val result = sync(db, Fake(listOf(event("t1"), event("t2", "btc")), "12345", 2)); ok(result, 2); assertTrue("2" in result.getValue("message").jsonPrimitive.content)
        ok(sync(db), 0)
        ok(sync(db, Fake(listOf(event("third")), "100", 1)), 1); ok(sync(db, Fake(listOf(event("third")), "100", 1)), 0)
    }
    @Test fun originalProviderAccountTypeAndConnectorFailures() = fixture { db, _, _ ->
        val before = state(db)
        for ((result, text) in listOf(sync(db, provider = "unknown", account = "x") to "未知的同步数据源", sync(db, account = "NoSuch") to "找不到账户",
            sync(db, account = "CashAccount") to "crypto", sync(db, Fake(failure = ConnectorAuthFailure("bad key"))) to "认证失败",
            sync(db, Fake(failure = ConnectorFailure("timeout"))) to "连接失败", sync(db, null) to "没有为数据源", sync(db, size = 0) to "batch_size")) {
            assertEquals(JsonPrimitive(false), result["ok"]); assertTrue(text in result.getValue("message").jsonPrimitive.content); assertEquals(before, state(db))
        }
    }
    @Test fun originalCursorSaveReadAndFullIgnore() = fixture { db, workspace, id ->
        ok(sync(db, Fake(listOf(event("t1")), "99999", 1))); assertEquals("99999", cursor(db, workspace, id))
        ok(sync(db, Fake(listOf(event("t1")), "1000", 1)))
        val second = Fake(); ok(sync(db, second)); assertEquals(listOf<String?>("1000"), second.calls)
        val full = Fake(); ok(sync(db, full, full = true)); assertEquals(listOf<String?>(null), full.calls)
        val incremental = Fake(listOf(event("two", "btc", "usd", "2026-07-26T00:00:00Z")), "200", 1); ok(sync(db, incremental)); assertEquals(listOf<String?>("1000"), incremental.calls); assertEquals("200", cursor(db, workspace, id))
    }
    @Test fun originalLaterChunkFailureRollsBackEventsSnapshotAndCursor() = fixture { db, workspace, id ->
        val before = state(db); val reached = mutableListOf<String>()
        val service = SyncService(db, applyEvent = { snapshot, row, currency ->
            val key = row.getValue("record_id").jsonPrimitive.content; reached += key
            require(key != "second") { "invalid upstream record" }; InvestmentProjection.applyEvent(snapshot, row, currency)
        })
        val result = service.sync("synthetic-admin", "binance", "TestCrypto", batchSize = 1, connector = Fake(listOf(event("first"), event("second", "btc")), "42", 2))
        assertEquals(JsonPrimitive(false), result["ok"]); assertEquals(listOf("first", "second"), reached); assertEquals(before, state(db)); assertNull(cursor(db, workspace, id))
        val snapshot = db.read { CashSqlContext(it, workspace, db.sqlite).snapshot.load().value }
        assertEquals(JsonObject(emptyMap()), snapshot.getValue("accounts").jsonObject.getValue("security"))
    }
    @Test fun originalIncrementalSqliteAndPostgresInputs() = fixture { db, workspace, id ->
        for ((firstId, secondId) in listOf("one" to "two", "100" to "200")) {
            fun original(identifier: String) = JsonObject(event(identifier, "btc", "usd", "2026-07-26T00:00:00Z").filterKeys { it !in setOf("account", "note") })
            ok(sync(db, Fake(listOf(original(firstId)), "100", 1)))
            val second = Fake(listOf(original(secondId)), "200", 1)
            ok(sync(db, second)); assertEquals(listOf<String?>("100"), second.calls); assertEquals("200", cursor(db, workspace, id))
            val full = Fake(); ok(sync(db, full, full = true)); assertEquals(listOf<String?>(null), full.calls)
        }
    }
    @Test fun originalStaleCursorRetriesFullOnlyOnce() = fixture { db, _, _ ->
        ok(sync(db, Fake(listOf(event("seed")), "100", 1)))
        val calls = mutableListOf<String?>()
        val connector = object : InvestmentConnector {
            override val sourceType = "binance_api"
            override fun fetch(since: String?): ConnectorResult { calls += since; if (since == "100") throw ConnectorFailure("cursor expired"); return ConnectorResult() }
        }
        ok(sync(db, connector)); assertEquals(listOf("100", null), calls)
    }
    @Test fun originalCursorRepositoryCreateUpdateAndSourceIsolation() = fixture { db, workspace, id ->
        assertNull(cursor(db, workspace, id))
        fun put(source: String, value: String) = db.write { SyncCursorRepository(CashSqlContext(it, workspace, db.sqlite)).upsert(id, source, value) }
        put("binance_api", "12345"); assertEquals("12345", cursor(db, workspace, id))
        put("binance_api", "100"); assertEquals("100", cursor(db, workspace, id)); put("binance_api", "200"); assertEquals("200", cursor(db, workspace, id))
        put("binance_api", "AAA"); put("polymarket_api", "BBB"); assertEquals("AAA", cursor(db, workspace, id)); assertEquals("BBB", cursor(db, workspace, id, "polymarket_api"))
    }
    @Test fun emptyScanSavesCursorAndAuthDataErrorsNeverFallback() = fixture { db, workspace, id ->
        ok(sync(db, Fake(cursor = "empty", raw = 7)), 0); assertEquals("empty", cursor(db, workspace, id))
        val before = state(db)
        for (failure in listOf(ConnectorAuthFailure("bad"), ConnectorDataFailure("invalid"))) {
            val connector = Fake(failure = failure); val result = sync(db, connector); assertEquals(JsonPrimitive(false), result["ok"])
            assertEquals(listOf<String?>("empty"), connector.calls); assertEquals(before, state(db))
        }
    }
    @Test fun sourcePayloadInfiniteKeysUseOriginalJsonEncoding() = fixture { db, workspace, _ ->
        for ((number, key) in listOf("1e400" to "Infinity", "-1e400" to "-Infinity")) {
            val row = JsonObject(event(number) + ("source_payload" to Json.parseToJsonElement("[[$number,\"value\"]]")))
            ok(sync(db, Fake(listOf(row), raw = 1)), 1)
            val stored = db.read { c -> c.jooq(db.sqlite).select(INVESTMENT_EVENTS.SOURCE_PAYLOAD).from(INVESTMENT_EVENTS)
                .where(INVESTMENT_EVENTS.WORKSPACE_ID.eq(workspace)).and(INVESTMENT_EVENTS.RECORD_ID.eq(number)).fetchSingle(INVESTMENT_EVENTS.SOURCE_PAYLOAD) }
            assertEquals(buildJsonObject { put(key, "value") }, stored)
        }
    }
    @Test fun networkBoundaryRechecksPermissionsAndAccountIdentity() = fixture { db, workspace, _ ->
        var fetched = 0
        val connector = object : InvestmentConnector { override val sourceType = "binance_api"; override fun fetch(since: String?): ConnectorResult { fetched++; return ConnectorResult(listOf(event("t1")), "1", 1) } }
        assertFailsWith<AccessFailure> { SyncService(db).sync("synthetic-viewer", "binance", "TestCrypto", connector = connector) }; assertEquals(0, fetched)
        val before = state(db)
        val service = SyncService(db, afterFetch = { db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() } })
        try { assertFailsWith<AccessFailure> { service.sync("synthetic-admin", "binance", "TestCrypto", connector = connector) }; assertEquals(1, fetched); assertEquals(before.filterKeys { it != "workspace_memberships" }, state(db).filterKeys { it != "workspace_memberships" }) }
        finally { db.write { c -> c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.eq("admin")).execute() } }
    }
    @Test fun currentAccountAndWorkspaceAreRecheckedAfterFetch() = fixture { db, workspace, id ->
        val original = state(db)
        val renamed = SyncService(db, afterFetch = { db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "Renamed").where(ACCOUNTS.ID.eq(id)).and(ACCOUNTS.WORKSPACE_ID.eq(workspace)).execute() } })
        try {
            val result = renamed.sync("synthetic-admin", "binance", "TestCrypto", connector = Fake(listOf(event("t1")), "1", 1))
            assertEquals(JsonPrimitive(false), result["ok"]); assertEquals(original.filterKeys { it != "accounts" }, state(db).filterKeys { it != "accounts" })
        } finally { db.write { c -> c.jooq(db.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "TestCrypto").where(ACCOUNTS.ID.eq(id)).execute() } }
        val switched = SyncService(db, afterFetch = { WorkspaceService(db).select("synthetic-admin", "fixture-a") })
        try { assertFailsWith<AccessFailure> { switched.sync("synthetic-admin", "binance", "TestCrypto", connector = Fake(listOf(event("t1")), "1", 1)) }; assertEquals(original.filterKeys { it != "user_sessions" }, state(db).filterKeys { it != "user_sessions" }) }
        finally { WorkspaceService(db).select("synthetic-admin", workspace) }
    }
    @Test fun cursorAndCommitFaultsRollbackAllFinancialState() = fixture { db, _, _ ->
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        for (stage in listOf("cursor", "commit")) {
            val reached = java.util.concurrent.atomic.AtomicInteger()
            val observed = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
                fun fail(sql: String, action: () -> Any?): Any? {
                    val normalized = sql.replace("\"", "").replace(96.toChar().toString(), "").trim().lowercase()
                    if (stage == "commit" && normalized == "commit") { reached.incrementAndGet(); throw java.sql.SQLException("synthetic commit failure", "XX000") }
                    val result = action()
                    if (stage == "cursor" && normalized.startsWith("insert into sync_cursors")) { reached.incrementAndGet(); throw java.sql.SQLException("synthetic cursor failure", "XX000") }
                    return result
                }
                java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                    fun call(): Any? = try { method.invoke(connection, *(args ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                    if (method.name == "commit") fail("commit", ::call) else {
                        val result = call()
                        if (method.name in setOf("prepareStatement", "createStatement") && result is java.sql.Statement) {
                            val sql = if (method.name == "prepareStatement") args!![0] as String else null
                            val type = if (result is java.sql.PreparedStatement) java.sql.PreparedStatement::class.java else java.sql.Statement::class.java
                            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, operation, values ->
                                fun invoke(): Any? = try { operation.invoke(result, *(values ?: emptyArray())) } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                                if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch")) fail(sql ?: values?.firstOrNull() as? String ?: "", ::invoke) else invoke()
                            }
                        } else result
                    }
                } as java.sql.Connection
            }
            val before = state(db)
            assertFailsWith<StorageFailure> { sync(observed, Fake(listOf(event(stage)), "10", 1)) }
            assertEquals(1, reached.get(), stage); assertEquals(before, state(db), stage)
        }
        val before = state(db)
        assertFailsWith<IllegalStateException> { SyncService(db, beforeReturn = { error("synthetic internal failure") }).sync("synthetic-admin", "binance", "TestCrypto", connector = Fake(listOf(event("late")), "11", 1)) }
        assertEquals(before, state(db))
    }

}
