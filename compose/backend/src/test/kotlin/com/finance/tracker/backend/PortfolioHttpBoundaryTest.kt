package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class PortfolioHttpBoundaryTest {
    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun read(scope: String) = object : PortfolioRead {
        override fun holdings() = JsonObject(portfolioHttpValue() + ("scope" to JsonPrimitive(scope)))
        override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?) = holdings()
    }
    @Test fun noDatabaseTransactionRemainsOpenDuringQuoteOrHistoryRequests() {
        val f = PortfolioHttpFixture(); val active = AtomicInteger(); val quotes = AtomicInteger(); val histories = AtomicInteger()
        val db = f.database { connection ->
            val open = AtomicBoolean()
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = invoke(connection, method, args)
                if (method.name == "setAutoCommit" && args?.get(0) == false && open.compareAndSet(false, true)) active.incrementAndGet()
                if (method.name in setOf("commit", "rollback") && open.compareAndSet(true, false)) active.decrementAndGet()
                result
            } as Connection
        }
        val provider = object : HistoricalQuoteProvider {
            override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { assertEquals(0, active.get()); quotes.incrementAndGet(); return f.quotes().rawQuote(identity, kind) }
            override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime): ProviderTick? { assertEquals(0, active.get()); histories.incrementAndGet(); return f.quotes().rawQuoteAt(identity, kind, at) }
        }
        val before = f.snapshot()
        PortfolioHttpService(db, now = { f.now.instant() }, quotes = provider, fx = f.fx()).use { service ->
            val value = service.get(f.token, ReadServices(db).currentContext(f.token), PortfolioRefreshScope(null, "24h", null), "valuation")
            assertTrue(value.getValue("accounts").jsonArray.isNotEmpty()); assertEquals(1, quotes.get()); assertTrue(histories.get() > 0); assertEquals(0, active.get())
        }
        assertEquals(before, f.snapshot())
    }
    @Test fun realJdbcFailureStopsBeforeQuotesAndDoesNotExposeSqlOrWriteData() {
        val f = PortfolioHttpFixture(); val before = f.snapshot()
        for (table in listOf("accounts", "ledger_snapshots", "investment_events")) {
            val fired = AtomicBoolean(); val reached = AtomicInteger(); val quotes = AtomicInteger()
            val db = f.database { connection ->
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                    val result = invoke(connection, method, args)
                    if (method.name == "prepareStatement" && result is PreparedStatement) {
                        val sql = args!![0] as String
                        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                            val value = invoke(result, operation, values)
                            if (operation.name.startsWith("execute") && Regex("\\b$table\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) && fired.compareAndSet(false, true)) { reached.incrementAndGet(); throw SQLException("SYNTHETIC_PORTFOLIO_SECRET", "08006") }
                            value
                        }
                    } else result
                } as Connection
            }
            val provider = object : QuoteProvider { override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { quotes.incrementAndGet(); return null } }
            PortfolioHttpServer(db, PortfolioHttpService(db, now = { f.now.instant() }, quotes = provider, fx = f.fx())).use { server ->
                val (status, body) = server.request("/api/v1/investment-portfolio", token = f.token)
                assertEquals(503, status, body); assertEquals(JsonPrimitive("storage.connect"), Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject["code"])
                assertFalse("SYNTHETIC_PORTFOLIO_SECRET" in body); assertFalse("select" in body.lowercase()); assertFalse("jdbc:" in body)
            }
            assertEquals(1, reached.get(), table); assertEquals(0, quotes.get(), table); assertEquals(before, f.snapshot())
        }
    }
    @Test fun currentWorkspaceAndMembershipAreRecheckedAfterReadAndBeforeQueue() {
        val f = PortfolioHttpFixture(); val db = f.database(); val principal = ReadServices(db).currentContext(f.token); val before = f.snapshot()
        for (change in listOf("workspace", "membership")) {
            var reached = 0
            fun restore() = db.write { c ->
                if (change == "workspace") c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, f.workspace).where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(f.token))).execute()
                else c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.token)).execute()
            }
            try {
                val service = PortfolioHttpService(db, factory = { read(it) }, beforeReturn = {
                    db.write { c ->
                        if (change == "workspace") c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "other-workspace").where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest(f.token))).execute()
                        else c.jooq(db.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "viewer").where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(f.token)).execute()
                    }; reached++
                })
                service.use {
                    if (change == "workspace") assertFailsWith<AccessFailure> { service.get(f.token, principal, PortfolioRefreshScope(null, "24h", null), "holdings") }
                    else assertNotNull(service.get(f.token, principal, PortfolioRefreshScope(null, "24h", null), "holdings"))
                    assertEquals(1, reached)
                    assertFailsWith<AccessFailure> { service.request(f.token, principal, PortfolioRefreshScope(null, "24h", null)) }
                    assertEquals(0, service.activeWorkers())
                }
            } finally { restore() }
        }
        assertEquals(before, f.snapshot())
    }
    @Test fun coordinatorBindsWorkspaceBeforeWorkerAndNeverSharesOtherSnapshot() {
        val f = PortfolioHttpFixture(); val db = f.database(); val bound = CopyOnWriteArrayList<String>(); val mainThread = Thread.currentThread().id
        val service = PortfolioHttpService(db, factory = { workspace ->
            assertEquals(mainThread, Thread.currentThread().id); bound.add(workspace)
            object : PortfolioRead {
                override fun holdings() = read(workspace).holdings()
                override fun portfolio(scope: PortfolioRefreshScope, publish: ((JsonObject) -> Unit)?): JsonObject { assertNotEquals(mainThread, Thread.currentThread().id); return holdings() }
            }
        }, coordinatorFactory = { PortfolioRefreshCoordinator(it, heartbeatSeconds = 0.1) })
        service.use {
            fun next(stream: PortfolioHttpSubscription): JsonObject { repeat(20) { val result = stream.next(); if (result.kind == "portfolio") return result.portfolio!! }; error("missing update") }
            val own = ReadServices(db).currentContext(f.token); val other = ReadServices(db).currentContext("portfolio-other"); val scope = PortfolioRefreshScope(null, "24h", null)
            service.subscribe(f.token, own, scope, null).use { a -> service.subscribe("portfolio-other", other, scope, null).use { b ->
                assertEquals(JsonPrimitive(f.workspace), next(a)["scope"]); assertEquals(JsonPrimitive("other-workspace"), next(b)["scope"])
            } }
            assertEquals(listOf(f.workspace, "other-workspace"), bound); assertEquals(0, service.activeSubscriptions())
        }
    }
    @Test fun accountsAndHoldingsUseOneReadSnapshotAcrossCommittedSnapshotChange() {
        val f = PortfolioHttpFixture(); val db = f.database(); val changed = AtomicBoolean(); val original = f.snapshot()
        val payload = db.read { c -> c.prepareStatement("SELECT payload FROM ledger_snapshots WHERE workspace_id=?").use { q -> q.setString(1, f.workspace); q.executeQuery().use { rows -> check(rows.next()); rows.getString(1) } } }
        val service = PortfolioHttpService(db, now = { f.now.instant() }, quotes = f.quotes(), fx = f.fx())
        val expected = service.get(f.token, ReadServices(db).currentContext(f.token), PortfolioRefreshScope(null, "24h", null), "holdings")
        val observed = f.database { connection ->
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = invoke(connection, method, args)
                if (method.name == "prepareStatement" && result is PreparedStatement) {
                    val sql = args!![0] as String
                    Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                        val value = invoke(result, operation, values)
                        if (operation.name.startsWith("execute") && Regex("\\baccounts\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) && changed.compareAndSet(false, true)) {
                            db.write { c ->
                                c.jooq(db.sqlite).update(LEDGER_SNAPSHOTS).set(LEDGER_SNAPSHOTS.PAYLOAD, JsonObject(emptyMap())).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(f.workspace)).execute()
                            }
                        }; value
                    }
                } else result
            } as Connection
        }
        try {
            PortfolioHttpService(observed, now = { f.now.instant() }, quotes = f.quotes(), fx = f.fx()).use {
                assertEquals(expected, it.get(f.token, ReadServices(db).currentContext(f.token), PortfolioRefreshScope(null, "24h", null), "holdings")); assertTrue(changed.get())
            }
            assertTrue(service.get(f.token, ReadServices(db).currentContext(f.token), PortfolioRefreshScope(null, "24h", null), "holdings").getValue("accounts").jsonArray.isEmpty())
        } finally { db.write { c -> c.prepareStatement("UPDATE ledger_snapshots SET payload=" + (if (db.sqlite) "?" else "?::json") + " WHERE workspace_id=?").use { q -> q.setString(1, payload); q.setString(2, f.workspace); q.executeUpdate() } }; service.close() }
        assertEquals(original, f.snapshot())
    }
}
