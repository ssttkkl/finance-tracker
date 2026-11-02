package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.serialization.json.*

internal class PortfolioHttpFixture {
    val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PORTFOLIO_CONTRACT_DIR")))
    val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
    val workspace = config.getValue("workspace").jsonPrimitive.content
    val now = QuoteTime.parse(config.getValue("now").jsonPrimitive.content)
    val token = config.getValue("owner_token").jsonPrimitive.content
    fun database(decorate: (java.sql.Connection) -> java.sql.Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), decorateConnection = decorate)
    fun quotes() = object : HistoricalQuoteProvider {
        override fun rawQuote(identity: String, kind: AssetKind) = ProviderTick("11.00", "USD", now, "fixture", "post_market")
        override fun rawQuoteAt(identity: String, kind: AssetKind, at: QuoteTime) = ProviderTick("9.00", "USD", at, "fixture", "regular")
    }
    fun fx() = FxRateProvider(FxMidFetcher { _, base, quote -> if (base == quote) "1" else if (base == "USD" && quote == "CNY") "7" else null })
    fun snapshot(database: Database = database()) = database.read { c -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { t -> t.name to c.createStatement().use { s -> s.executeQuery("SELECT * FROM \"${t.name}\"").use { rows -> buildList { while (rows.next()) add((1..rows.metaData.columnCount).map { rows.getString(it) }) }.sortedBy { it.toString() } } } } }
}
class PortfolioHttpContractTest {
    @Test fun completeOriginalAuthenticatedHttpBodiesStatusesAndNoWritesMatch() {
        val f = PortfolioHttpFixture(); val db = f.database(); val before = f.snapshot()
        val requested = java.util.concurrent.CopyOnWriteArrayList<PortfolioRefreshScope>()
        val service = PortfolioHttpService(db, now = { f.now.instant() }, quotes = f.quotes(), fx = f.fx(), beforeRefresh = { requested.add(it) })
        PortfolioHttpServer(db, service).use { server ->
            val cases = Json.parseToJsonElement(f.root.resolve("cases.json").readText()).jsonArray; assertEquals(30, cases.size)
            for ((index, raw) in cases.withIndex()) {
                val c = raw.jsonObject; requested.clear()
                val params = when (val value = c["params"]) {
                    is JsonArray -> value.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.content }
                    is JsonObject -> value.map { it.key to it.value.jsonPrimitive.content }
                    else -> emptyList()
                }
                val query = params.joinToString("&") { (key, value) -> java.net.URLEncoder.encode(key, Charsets.UTF_8) + "=" + java.net.URLEncoder.encode(value, Charsets.UTF_8) }
                val path = c.getValue("path").jsonPrimitive.content + if (query.isEmpty()) "" else "?$query"
                val (status, body) = server.request(path, c.getValue("method").jsonPrimitive.content, c["token"]?.jsonPrimitive?.contentOrNull)
                val label = "$index $path ${c["token"]}"
                assertEquals(c.getValue("status").jsonPrimitive.int, status, "$label $body")
                val expected = c.getValue("body")
                if (expected is JsonPrimitive && expected.isString) assertEquals(expected.content, body, label) else assertEquals(expected, Json.parseToJsonElement(body), label)
                val rawScope = c["requested"]
                if (rawScope == JsonNull) assertTrue(requested.isEmpty(), label) else {
                    val expectedScope = rawScope!!.jsonObject
                    assertEquals(listOf(PortfolioRefreshScope(expectedScope["display_currency"]?.jsonPrimitive?.contentOrNull, expectedScope.getValue("period").jsonPrimitive.content, expectedScope["timezone"]?.jsonPrimitive?.contentOrNull)), requested, label)
                }
                assertEquals(before, f.snapshot(), label)
                if (index in setOf(1, 2, 26)) f.root.resolve("native-response-$index.json").writeText(body)
            }
        }
        assertEquals(0, service.activeWorkers()); assertEquals(0, service.activeSubscriptions())
    }
    @Test fun actualValuationSseFrameMatchesOriginalCompleteUnicodeJson() {
        val f = PortfolioHttpFixture(); val db = f.database(); val service = PortfolioHttpService(db, now = { f.now.instant() }, quotes = f.quotes(), fx = f.fx())
        service.use {
            val value = service.get(f.token, ReadServices(db).currentContext(f.token), PortfolioRefreshScope("CNY", "24h", null), "valuation")
            val frames = listOf(portfolioSseFrame(PortfolioStreamUpdate(BigInteger.valueOf(7), "portfolio", value)), portfolioSseFrame(PortfolioStreamUpdate(BigInteger.valueOf(8), "refresh_error")), portfolioSseFrame(PortfolioStreamUpdate(BigInteger.valueOf(8), "heartbeat")))
            assertEquals(Json.parseToJsonElement(f.root.resolve("frames.json").readText()).jsonArray.map { it.jsonPrimitive.content }, frames)
            f.root.resolve("native-frames.json").writeText(JsonArray(frames.map(::JsonPrimitive)).toString())
        }
    }
    @Test fun authenticatedWorkspaceExposesInvestmentAccountsEventsHoldingsAndRefresh() {
        val f = PortfolioHttpFixture(); val db = f.database(); val email = "portfolio-" + java.util.UUID.randomUUID() + "@example.invalid"
        PortfolioHttpServer(db, PortfolioHttpService(db, now = { f.now.instant() }, quotes = f.quotes(), fx = f.fx())).use { server ->
            val (registered, registration) = server.request("/api/v1/auth/register", "POST", null,
                buildJsonObject { put("email", email); put("password", "anonymous-test-password") }.toString())
            assertEquals(200, registered, registration)
            val token = Json.parseToJsonElement(registration).jsonObject.getValue("access_token").jsonPrimitive.content
            val user = db.read { c -> c.jooq(db.sqlite).select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchSingle(USERS.ID)!! }
            try {
                db.write { c ->
                    c.jooq(db.sqlite).insertInto(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID, f.workspace)
                        .set(WORKSPACE_MEMBERSHIPS.USER_ID, user).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor").set(WORKSPACE_MEMBERSHIPS.CREATED_AT, f.now.instant()).execute()
                    c.jooq(db.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, f.workspace).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                }
                val (accountStatus, accounts) = server.request("/api/v1/accounts?view=investment", token = token)
                val (eventStatus, events) = server.request("/api/v1/investment-events", token = token)
                val holdings = server.request("/api/v1/investment-portfolio?phase=holdings", token = token)
                val refresh = server.request("/api/v1/investment-portfolio/refresh", "POST", token)
                assertEquals(200, accountStatus); assertEquals(Json.parseToJsonElement("""[{"id":103,"name":"投资账户","type":"security","active":true}]"""), Json.parseToJsonElement(accounts).jsonObject["items"])
                assertEquals(200, eventStatus); assertEquals(listOf("investment-003", "investment-002", "investment-001"), Json.parseToJsonElement(events).jsonObject.getValue("items").jsonArray.map { it.jsonObject.getValue("record_id").jsonPrimitive.content })
                assertEquals(200, holdings.first); assertEquals(202, refresh.first)
            } finally { db.write { c ->
                c.jooq(db.sqlite).deleteFrom(USER_SESSIONS).where(USER_SESSIONS.USER_ID.eq(user)).execute()
                c.jooq(db.sqlite).deleteFrom(WORKSPACE_MEMBERSHIPS).where(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute()
                c.jooq(db.sqlite).deleteFrom(USERS).where(USERS.ID.eq(user)).execute()
            } }
        }
    }
}
