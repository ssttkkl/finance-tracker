package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FinanceReportRetirementTest(private val backend: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun backends(): List<Array<String>> = listOf(arrayOf("sqlite"), arrayOf("postgres"))
    }
    private val at = Instant.parse("2026-11-17T00:00:00Z")
    private fun state(db: Database) = db.read { c -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to c.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }
    private fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? =
        try { method.invoke(target, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.targetException }
    private fun workspace(decorate: (Connection) -> Connection = { it }, action: (Database, String) -> Unit) =
        withCashWriteSetTestWorkspace(backend, decorate) { db, ws, _ ->
            val user = UUID.randomUUID().toString(); val token = "finance-retirement-$user"
            db.write { c ->
                val sql = c.jooq(db.sqlite)
                sql.insertInto(USERS).set(USERS.ID, user).set(USERS.EMAIL, "$user@example.invalid")
                    .set(USERS.PASSWORD_HASH, "unused-test-hash").set(USERS.CREATED_AT, at).execute()
                sql.insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, ws)
                    .set(WORKSPACE_MEMBERSHIPS.USER_ID, user).set(WORKSPACE_MEMBERSHIPS.ROLE, "admin").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, at).execute()
                sql.insertInto(USER_SESSIONS).set(USER_SESSIONS.ID, UUID.randomUUID().toString()).set(USER_SESSIONS.USER_ID, user)
                    .set(USER_SESSIONS.TOKEN_DIGEST, tokenDigest(token)).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, ws)
                    .set(USER_SESSIONS.CREATED_AT, at).set(USER_SESSIONS.EXPIRES_AT, Instant.parse("2099-01-01T00:00:00Z")).execute()
            }
            try { action(db, token) } finally { db.write { c ->
                val sql = c.jooq(db.sqlite)
                sql.deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                sql.deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                sql.deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
            } }
        }

    @Test fun retiredReportNeverReadsFinancialFactsQuotesOrWrites() {
        val observe = AtomicBoolean(); val reads = CopyOnWriteArrayList<String>(); val quotes = AtomicInteger()
        val financialTables = DefaultSchema.DEFAULT_SCHEMA.tables.map { it.name }.filter { it !in setOf(
            "users", "user_sessions", "workspaces", "workspace_memberships", "workspace_invitations") }
        workspace(decorate = { connection ->
            Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                val result = invoke(connection, method, args)
                if (result is Statement && method.name in setOf("prepareStatement", "createStatement")) {
                    val prepared = args?.firstOrNull() as? String
                    val type = if (result is PreparedStatement) PreparedStatement::class.java else Statement::class.java
                    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, operation, values ->
                        if (observe.get() && operation.name.startsWith("execute")) {
                            val sql = (prepared ?: values?.firstOrNull() as? String).orEmpty()
                            if (financialTables.any { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql) }) reads.add(sql)
                        }
                        invoke(result, operation, values)
                    }
                } else result
            } as Connection
        }) { db, token ->
            val provider = object : QuoteProvider {
                override fun rawQuote(identity: String, kind: AssetKind): ProviderTick? { quotes.incrementAndGet(); return null }
            }
            val before = state(db)
            testApplication {
                application { financeModule(db, clock = { at }, financeQueries = FinanceQueryService(db, now = { at }, quoteProvider = provider)) }
                observe.set(true)
                try {
                    for (query in listOf("", "?month=2026-07", "?month=invalid", "?month=2026-07&month=invalid")) {
                        val response = client.get("/api/v1/reports/finance$query") { bearerAuth(token) }
                        assertEquals(404, response.status.value, response.bodyAsText())
                    }
                } finally { observe.set(false) }
            }
            assertEquals(emptyList(), reads.toList()); assertEquals(0, quotes.get()); assertEquals(before, state(db))
        }
    }

    @Test fun accountsAndIndependentInvestmentReadsRemainAvailable() = workspace { db, token ->
        val before = state(db)
        testApplication {
            application { financeModule(db, clock = { at }) }
            for (path in listOf("/api/v1/queries/accounts", "/api/v1/investment-events",
                "/api/v1/investment-portfolio?phase=holdings", "/api/v1/investment-portfolio?phase=valuation")) {
                val response = client.get(path) { bearerAuth(token) }
                assertEquals(200, response.status.value, "$path ${response.bodyAsText()}")
                assertTrue(Json.parseToJsonElement(response.bodyAsText()) is JsonObject)
            }
        }
        assertEquals(before, state(db))
    }
}
