package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class FinanceQueryBoundaryTest {
    private fun invoke(target: Any, method: java.lang.reflect.Method, arguments: Array<out Any?>?): Any? =
        try { method.invoke(target, *(arguments ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }

    @Test fun actualExecutedJdbcFailuresStopWholeQueryWithoutFallbackOrWrites() {
        val fixture = FinanceQueryFixture("fault"); val before = fixture.snapshot()
        for ((path, table) in listOf("accounts" to "ledger_snapshots", "accounts" to "accounts", "report" to "ledger_snapshots",
            "report" to "accounts", "report" to "cash_projection_states", "report" to "cash_projections")) {
            val reached = AtomicInteger(); val fired = AtomicBoolean(); val downloads = CopyOnWriteArrayList<String>()
            val database = fixture.database { connection ->
                Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                    val result = invoke(connection, method, arguments)
                    if (method.name == "prepareStatement" && result is PreparedStatement) {
                        val text = arguments!![0] as String
                        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                            val value = invoke(result, operation, values)
                            if (operation.name.startsWith("execute") && Regex("\\b" + Regex.escape(table) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && fired.compareAndSet(false, true)) {
                                reached.incrementAndGet(); throw SQLException("SYNTHETIC_SQL_SECRET", "08006")
                            }
                            value
                        }
                    } else result
                } as Connection
            }
            val service = FinanceQueryService(database, quoteProvider = fixture.provider(downloads))
            testApplication {
                application { financeModule(database, financeQueries = service) }
                val response = client.get(if (path == "accounts") "/api/v1/queries/accounts" else "/api/v1/reports/finance?month=2026-07") { bearerAuth("finance-original-owner") }
                val body = response.bodyAsText()
                assertEquals(503, response.status.value, "$path/$table: $body")
                assertEquals(JsonPrimitive("storage.connect"), Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"])
                assertFalse("SYNTHETIC_SQL_SECRET" in body); assertFalse("select" in body.lowercase()); assertFalse("jdbc:" in body)
            }
            assertEquals(1, reached.get(), "$path/$table")
            if (table in setOf("ledger_snapshots", "accounts")) assertTrue(downloads.isEmpty()) else assertEquals(listOf("AAPL", "MISSING"), downloads.toList())
            assertEquals(before, fixture.snapshot(), "$path/$table")
        }
    }
    @Test fun currentWorkspaceIsRecheckedAndForeignSnapshotNeverLeaks() {
        val fixture = FinanceQueryFixture("context"); val database = fixture.database(); val downloads = CopyOnWriteArrayList<String>()
        val other = "finance-boundary-other"
        database.write { connection ->
            val sql = connection.jooq(database.sqlite); val now = Instant.now()
            sql.insertInto(WORKSPACES).set(WORKSPACES.ID, other).set(WORKSPACES.NAME, "Foreign only").set(WORKSPACES.CREATED_AT, now).execute()
            sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, other)
                .set(WORKSPACE_MEMBERSHIPS.USER_ID, "finance-original-owner").set(WORKSPACE_MEMBERSHIPS.ROLE, "admin")
                .set(WORKSPACE_MEMBERSHIPS.CREATED_AT, now).execute()
            sql.insertInto(ACCOUNTS).set(ACCOUNTS.ID, 9901L).set(ACCOUNTS.WORKSPACE_ID, other).set(ACCOUNTS.NAME, "FOREIGN_ONLY_ACCOUNT")
                .set(ACCOUNTS.TYPE, "cash").set(ACCOUNTS.ACTIVE, true).set(ACCOUNTS.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD"))))
                .set(ACCOUNTS.METADATA_JSON, JsonObject(emptyMap())).set(ACCOUNTS.CREATED_AT, now).set(ACCOUNTS.UPDATED_AT, now).execute()
            sql.insertInto(LEDGER_SNAPSHOTS).set(LEDGER_SNAPSHOTS.WORKSPACE_ID, other)
                .set(LEDGER_SNAPSHOTS.PAYLOAD, Json.parseToJsonElement("""{"accounts":{"cash":{"9901":{"USD":"999999.99"}}}}"""))
                .set(LEDGER_SNAPSHOTS.VERSION, 1L).set(LEDGER_SNAPSHOTS.UPDATED_AT, now).execute()
        }
        val before = fixture.snapshot()
        val own = FinanceQueryService(database, quoteProvider = fixture.provider(downloads)).accounts("finance-original-owner")
        assertEquals(fixture.expected("/api/v1/queries/accounts"), own); assertFalse("FOREIGN_ONLY_ACCOUNT" in own.toString()); assertEquals(before, fixture.snapshot())
        var changed = 0
        val service = FinanceQueryService(database, quoteProvider = fixture.provider(downloads), beforeReturn = {
            database.write { connection ->
                assertEquals(1, connection.jooq(database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, other)
                    .where(USER_SESSIONS.TOKEN_DIGEST.eq(tokenDigest("finance-original-owner"))).execute())
            }; changed++
        })
        assertTrue(assertFailsWith<AccessFailure> { service.accounts("finance-original-owner") }.forbidden); assertEquals(1, changed)
        val after = fixture.snapshot(); assertEquals(before.filterKeys { it != "user_sessions" }, after.filterKeys { it != "user_sessions" })
    }
    @Test fun accountsAndCurrentReportRemainOneSnapshotAcrossRealFinancialPublication() {
        val fixture = FinanceQueryFixture("snapshot"); val database = fixture.database()
        if (database.sqlite) DriverManager.getConnection(fixture.config["jdbc_url"]!!.jsonPrimitive.content).use { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        }
        val downloads = CopyOnWriteArrayList<String>(); val changed = AtomicBoolean()
        val beforeVersion = database.read { connection -> connection.jooq(database.sqlite).select(CASH_PROJECTION_STATES.PROJECTION_VERSION)
            .from(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(fixture.workspace)).fetchSingle(CASH_PROJECTION_STATES.PROJECTION_VERSION)!! }
        val service = FinanceQueryService(database, quoteProvider = fixture.provider(downloads), afterAccountsRead = {
            if (changed.compareAndSet(false, true)) LegacyCashflowService(database).addTransaction("finance-original-owner",
                LegacyCashInput(account = "日常账户", value = "1.00", currency = "CNY", date = "2026-07-15T12:00:00+00:00", recordType = "income", note = "snapshot publication"))
        })
        val result = service.report("finance-original-owner", "2026-07")
        assertTrue(changed.get()); assertEquals(fixture.expected("/api/v1/reports/finance", "2026-07"), result)
        val next = FinanceQueryService(database, quoteProvider = fixture.provider(downloads)).report("finance-original-owner", "2026-07")
        assertEquals("2001".toBigDecimal(), next["income"]!!.jsonObject["CNY"]!!.jsonPrimitive.content.toBigDecimal().stripTrailingZeros())
        val cash = next["accounts"]!!.jsonObject["accounts"]!!.jsonArray.map { it.jsonObject }
        assertTrue(cash.any { it["name"] == JsonPrimitive("日常账户") && it["currency"] == JsonPrimitive("CNY") && it["balance"]!!.jsonPrimitive.content.toBigDecimal().compareTo("1".toBigDecimal()) == 0 })
        val afterVersion = database.read { connection -> connection.jooq(database.sqlite).select(CASH_PROJECTION_STATES.PROJECTION_VERSION)
            .from(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(fixture.workspace)).fetchSingle(CASH_PROJECTION_STATES.PROJECTION_VERSION)!! }
        assertTrue(afterVersion > beforeVersion)
    }
}
