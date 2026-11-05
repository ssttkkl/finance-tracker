package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class InvestmentLedgerReadContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_INVESTMENT_READ_CONTRACT_DIR")))
    private fun config(group: String? = null) = Json.parseToJsonElement(
        (if (group == null) root() else root().resolve(group)).resolve("database.json").readText()).jsonObject
    private fun database(group: String? = null): Database {
        val value = config(group).getValue("jdbc_url").jsonPrimitive.content
        return Database(value, if (value.startsWith("jdbc:postgresql:")) System.getProperty("user.name") else null)
    }
    private fun now(group: String? = null) = Instant.parse(config(group).getValue("now").jsonPrimitive.content)
    private fun token(group: String? = null) = config(group).getValue("owner_token").jsonPrimitive.content
    private fun service(group: String) = InvestmentLedgerReadService(database(group), { now(group) })
    private fun JsonElement.field(vararg path: String): JsonElement = path.fold(this) { value, key -> value.jsonObject.getValue(key) }
    private fun JsonObject.items() = getValue("items").jsonArray
    private fun ids(page: JsonObject, name: String = "record_id") = page.items().map { it.field(name).jsonPrimitive.content }
    private fun snapshot(database: Database) = database.read { connection ->
        DefaultSchema.DEFAULT_SCHEMA.tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"" + table.name + "\"").use { rows ->
                buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map {
                    rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } }
    }
    private fun versionTwo(database: Database) = database.write { connection ->
        val table = LEDGER_SNAPSHOTS
        val sql = connection.jooq(database.sqlite)
        assertNotNull(sql.select(table.VERSION).from(table).where(table.WORKSPACE_ID.eq("cash-web-workspace")).fetchOne())
        assertEquals(1, sql.update(table).set(table.VERSION, 2L).where(table.WORKSPACE_ID.eq("cash-web-workspace")).execute())
    }
    private fun addUnshown(database: Database) = database.write { connection ->
        val e = INVESTMENT_EVENTS
        assertEquals(1, connection.jooq(database.sqlite).insertInto(e).set(e.ID, 2010L).set(e.WORKSPACE_ID, "cash-web-workspace")
            .set(e.ACCOUNT_ID, 103L).set(e.SOURCE_TYPE, "fixture").set(e.RECORD_ID, "unshown-large").set(e.SOURCE_PAYLOAD, buildJsonObject {})
            .set(e.OCCURRED_AT, Instant.parse("2026-07-03T08:59:59Z")).set(e.RECORD_TYPE, "trade").set(e.RECORD_SUBTYPE, "security")
            .set(e.CURRENCY, "USD").set(e.NOTE, "anonymous").set(e.FROM_TICKER, "USD").set(e.FROM_AMOUNT, CashStoredDecimal("12345678901"))
            .set(e.TO_TICKER, "AAPL.US").set(e.TO_AMOUNT, CashStoredDecimal("1")).set(e.COMMISSION, CashStoredDecimal("0"))
            .set(e.COMMISSION_ASSET, "").set(e.PAYLOAD, buildJsonObject {}).set(e.CREATED_AT, now()).execute())
    }
    private fun http(group: String?, action: suspend ApplicationTestBuilder.(Database) -> Unit) = testApplication {
        val database = database(group)
        application { financeModule(database, clock = { now(group) }) }
        action(database)
    }

    @Test fun allLegacyReadRequestsMatchOriginalAndRemainReadOnly() = http(null) { database ->
        val contracts = Json.parseToJsonElement(root().resolve("contracts.json").readText()).jsonObject
        for (element in contracts.getValue("cases").jsonArray) {
            val case = element.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            if (case["action"] == JsonPrimitive("version2")) versionTwo(database)
            if (case["action"] == JsonPrimitive("large_unshown")) addUnshown(database)
            val before = snapshot(database)
            val response = client.get(case.getValue("path").jsonPrimitive.content) {
                url { case.getValue("params").jsonObject.forEach { (key, value) -> parameters.append(key, value.jsonPrimitive.content) } }
                (case["token"] as? JsonPrimitive)?.takeIf { it != JsonNull }?.let { header(HttpHeaders.Authorization, "Bearer " + it.content) }
            }
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, name + " " + response.bodyAsText())
            val expected = case.getValue("body")
            if (expected is JsonObject) assertEquals(expected, Json.parseToJsonElement(response.bodyAsText()), name)
            else assertEquals(expected.jsonPrimitive.content, response.bodyAsText(), name)
            assertEquals(before, snapshot(database), name + " must not write any physical table")
            if (name == "all_events") root().resolve("native-page-response.json").writeText(response.bodyAsText())
            if (case["path"] == JsonPrimitive("/api/v1/evidence/investment-events/fixture%3Ainvestment-003") &&
                case["token"] == JsonPrimitive("invest-owner")) root().resolve("native-evidence-response.json").writeText(response.bodyAsText())
        }
        val actual = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject.getValue("paths").jsonObject
        for ((path, original) in contracts.getValue("openapi").jsonObject) assertEquals(original.jsonObject["get"], actual[path]?.jsonObject?.get("get"))
        assertTrue("post" in actual.getValue("/api/v1/investment-events").jsonObject)
    }

    @Test fun originalApplicationFiltersAndCursorPreserveDecimalValues() {
        val group = "application_cursor"; val service = service(group); val token = token(group)
        val first = service.page(token, mapOf("limit" to "2", "record_type" to "trade"))
        assertEquals(listOf("fixture:investment-003"), ids(first, "event_id"))
        val event = first.items().first()
        assertEquals(JsonPrimitive("1011.530000000000000000"), event.field("from_asset", "amount"))
        assertEquals(JsonPrimitive("10.000000000000000001"), event.field("to_asset", "amount"))
        assertEquals(JsonPrimitive("11.530000000000000000"), event.field("commission", "amount"))
        assertTrue(event.field("occurred_at").jsonPrimitive.content.endsWith("+00:00"))
        assertEquals(JsonArray(emptyList()), event.field("relations"))
        val allFirst = service.page(token, mapOf("limit" to "2"))
        val cursor = allFirst.getValue("next_cursor").jsonPrimitive.content
        val second = service.page(token, mapOf("limit" to "2", "cursor" to cursor))
        assertEquals(listOf("investment-003", "investment-002", "investment-001"), ids(allFirst) + ids(second))
        assertEquals("invalid_cursor", assertFailsWith<InvestmentReadFailure> { service.page(token, mapOf("cursor" to cursor, "ticker" to "AAPL.US")) }.code)
        versionTwo(database(group))
        assertEquals("investment.updated", assertFailsWith<InvestmentReadFailure> { service.page(token, mapOf("cursor" to cursor)) }.code)
    }

    @Test fun originalApplicationTickerUsesCaseInsensitiveLiteralFragments() {
        val group = "application_ticker"; val service = service(group); val token = token(group)
        assertEquals(listOf("investment-003"), ids(service.page(token, mapOf("ticker" to "Pl.Us"))))
        assertEquals(emptyList(), ids(service.page(token, mapOf("ticker" to "AAPL%"))))
        assertEquals(emptyList(), ids(service.page(token, mapOf("ticker" to "AAPL_"))))
        assertEquals(listOf("investment-004"), ids(service.page(token, mapOf("ticker" to "pl\\u"))))
        val first = service.page(token, mapOf("ticker" to "USD", "limit" to "1"))
        val cursor = first.getValue("next_cursor")
        assertNotEquals(JsonNull, cursor)
        assertEquals("invalid_cursor", assertFailsWith<InvestmentReadFailure> {
            service.page(token, mapOf("ticker" to "sd", "cursor" to cursor.jsonPrimitive.content)) }.code)
    }

    @Test fun originalApplicationIncludesBatchFundingRelationSummary() {
        val group = "application_relation"
        val item = service(group).page(token(group)).items().last()
        assertEquals(JsonPrimitive("investment-001"), item.field("record_id"))
        val relation = item.field("relations").jsonArray.first()
        assertEquals(JsonPrimitive("cash_investment_funding"), relation.field("kind"))
        assertEquals(JsonPrimitive("accepted"), relation.field("status"))
        assertEquals(JsonPrimitive("日常账户"), relation.field("cash_account", "name"))
        assertEquals(JsonPrimitive("2000"), relation.field("cash_amount"))
    }

    @Test fun originalApplicationEvidenceIsWorkspaceBoundAndRedacted() {
        val group = "application_evidence"; val service = service(group); val token = token(group)
        val evidence = service.evidence(token, "fixture:investment-003")
        assertEquals(JsonPrimitive("fixture:investment-003"), evidence.field("event", "event_id"))
        assertEquals(buildJsonObject { put("action", "BUY") }, evidence.field("source_snapshot"))
        assertEquals(JsonArray(emptyList()), evidence.field("relations"))
        assertEquals("not_found", assertFailsWith<InvestmentReadFailure> { service.evidence(token, "fixture:missing") }.code)
    }

    @Test fun originalHttpReturnsEventsEvidenceAndInvestmentAccounts() = http("http_events") {
        val headers = token("http_events")
        val page = client.get("/api/v1/investment-events?limit=2") { header(HttpHeaders.Authorization, "Bearer " + headers) }
        val accounts = client.get("/api/v1/accounts?view=investment") { header(HttpHeaders.Authorization, "Bearer " + headers) }
        val evidence = client.get("/api/v1/evidence/investment-events/fixture%3Ainvestment-003") { header(HttpHeaders.Authorization, "Bearer " + headers) }
        assertEquals(200, page.status.value)
        val body = Json.parseToJsonElement(page.bodyAsText()).jsonObject
        assertEquals(listOf("investment-003", "investment-002"), ids(body))
        assertEquals(JsonPrimitive("1011.530000000000000000"), body.items().first().field("from_asset", "amount"))
        assertEquals(200, accounts.status.value)
        assertEquals(listOf("投资账户"), Json.parseToJsonElement(accounts.bodyAsText()).jsonObject.items().map { it.field("name").jsonPrimitive.content })
        assertEquals(200, evidence.status.value)
        assertEquals(buildJsonObject { put("action", "BUY") }, Json.parseToJsonElement(evidence.bodyAsText()).field("source_snapshot"))
    }

    @Test fun originalHttpDoesNotLeakCrossWorkspaceEvent() = http("http_isolation") {
        val response = client.get("/api/v1/evidence/investment-events/fixture%3Aother-event") {
            header(HttpHeaders.Authorization, "Bearer " + token("http_isolation")) }
        assertEquals(404, response.status.value)
        assertEquals(JsonPrimitive("not_found"), Json.parseToJsonElement(response.bodyAsText()).field("error", "code"))
        assertFalse("investment-003" in response.bodyAsText())
    }

    @Test fun originalHttpTickerUsesCaseInsensitiveLiteralFragments() = http("http_ticker") {
        val response = client.get("/api/v1/investment-events?ticker=Pl.Us") { header(HttpHeaders.Authorization, "Bearer " + token("http_ticker")) }
        assertEquals(200, response.status.value)
        assertEquals(listOf("investment-003"), ids(Json.parseToJsonElement(response.bodyAsText()).jsonObject))
    }

    @Test fun originalHttpRejectsCursorFromOlderDataVersion() = http("http_version") { database ->
        val response = client.get("/api/v1/investment-events?limit=2") { header(HttpHeaders.Authorization, "Bearer " + token("http_version")) }
        val cursor = Json.parseToJsonElement(response.bodyAsText()).field("next_cursor").jsonPrimitive.content
        versionTwo(database)
        val updated = client.get("/api/v1/investment-events") {
            url { parameters.append("cursor", cursor) }
            header(HttpHeaders.Authorization, "Bearer " + token("http_version"))
        }
        assertEquals(409, updated.status.value)
        assertEquals(JsonPrimitive("investment.updated"), Json.parseToJsonElement(updated.bodyAsText()).field("error", "code"))
    }
    private class ReadTrace(val after: (ReadTrace, String) -> Unit = { _, _ -> }) {
        var versions = 0; var events = 0; var relations = 0; var writes = 0
        fun executed(raw: String) {
            val sql = raw.replace("\"", "").replace(96.toChar().toString(), "").replace("public.", "").lowercase().replace(Regex("\\s+"), " ").trim()
            if (Regex("^(insert|update|delete|replace|truncate|alter|create|drop)\\b").containsMatchIn(sql)) writes++
            if (sql.startsWith("select")) {
                if ("from ledger_snapshots" in sql) versions++
                if ("from investment_events" in sql) events++
                if ("from cash_investment_funding_relations" in sql) relations++
            }
            after(this, sql)
        }
    }
    private fun observedDatabase(trace: ReadTrace): Database {
        val url = config("fault").getValue("jdbc_url").jsonPrimitive.content
        fun invoke(target: Any, method: java.lang.reflect.Method, args: Array<out Any?>?): Any? = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.cause ?: failure }
        fun statement(target: java.sql.Statement, prepared: String?): java.sql.Statement {
            val type = if (target is java.sql.PreparedStatement) java.sql.PreparedStatement::class.java else java.sql.Statement::class.java
            return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val result = invoke(target, method, args)
                if (method.name.startsWith("execute")) (prepared ?: args?.firstOrNull() as? String)?.let(trace::executed)
                result
            } as java.sql.Statement
        }
        return Database(url, if (url.startsWith("jdbc:postgresql:")) System.getProperty("user.name") else null,
            decorateConnection = { connection ->
                java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader, arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                    val result = invoke(connection, method, args)
                    if (result is java.sql.Statement && method.name in setOf("prepareStatement", "createStatement"))
                        statement(result, if (method.name == "prepareStatement") args!![0] as String else null)
                    else result
                } as java.sql.Connection
            })
    }

    @Test fun actualReadFaultsStopEveryStageWithoutRetryOrPartialResponse() {
        val base = database("fault")
        for (path in listOf("/api/v1/investment-events", "/api/v1/evidence/investment-events/fixture%3Ainvestment-001")) {
            for (stage in listOf("ledger_snapshots", "investment_events", "cash_investment_funding_relations")) {
                val before = snapshot(base)
                val trace = ReadTrace { _, sql -> if (sql.startsWith("select") && "from $stage" in sql)
                    throw java.sql.SQLException("SYNTHETIC_PRIVATE_READ_FAILURE") }
                val observed = observedDatabase(trace)
                testApplication {
                    application { financeModule(observed, clock = { now("fault") }) }
                    val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer " + token("fault")) }
                    assertEquals(503, response.status.value, stage + " " + response.bodyAsText())
                    assertEquals(buildJsonObject { put("error", buildJsonObject { put("code", "storage.connect")
                        put("message", "无法连接本机账本，请检查 API 和数据库连接后重试。") }) }, Json.parseToJsonElement(response.bodyAsText()))
                    assertFalse("SYNTHETIC_PRIVATE" in response.bodyAsText())
                }
                assertEquals(1, trace.versions)
                assertEquals(if (stage == "ledger_snapshots") 0 else 1, trace.events)
                assertEquals(if (stage == "cash_investment_funding_relations") 1 else 0, trace.relations)
                assertEquals(0, trace.writes)
                assertEquals(before, snapshot(base), path + " " + stage)
            }
        }
    }

    @Test fun currentIdentityIsRecheckedAfterActualEventRead() {
        val base = database("fault"); val m = WORKSPACE_MEMBERSHIPS; val s = USER_SESSIONS
        val member = base.read { it.jooq(base.sqlite).selectFrom(m).where(m.WORKSPACE_ID.eq("cash-web-workspace")).and(m.USER_ID.eq("invest-owner")).fetchSingle() }
        val session = base.read { it.jooq(base.sqlite).selectFrom(s).where(s.ID.eq("invest-owner")).fetchSingle() }
        for (path in listOf("/api/v1/investment-events", "/api/v1/evidence/investment-events/fixture%3Ainvestment-003")) {
            for (mode in listOf("membership", "workspace", "expiry")) {
                if (mode == "workspace") base.write { connection -> connection.jooq(base.sqlite).insertInto(m)
                    .set(m.WORKSPACE_ID, "other-workspace").set(m.USER_ID, "invest-owner").set(m.ROLE, "admin").set(m.CREATED_AT, now("fault")).execute() }
                var expectedAfterRace: Map<String, List<String>>? = null
                var raceApplied = false
                val trace = ReadTrace { trace, sql -> if (sql.startsWith("select") && "from investment_events" in sql && trace.events == 1) {
                    base.write { connection ->
                        val dsl = connection.jooq(base.sqlite)
                        when (mode) {
                            "membership" -> assertEquals(1, dsl.deleteFrom(m).where(m.WORKSPACE_ID.eq("cash-web-workspace")).and(m.USER_ID.eq("invest-owner")).execute())
                            "workspace" -> assertEquals(1, dsl.update(s).set(s.ACTIVE_WORKSPACE_ID, "other-workspace").where(s.ID.eq("invest-owner")).execute())
                            else -> assertEquals(1, dsl.update(s).set(s.EXPIRES_AT, Instant.EPOCH).where(s.ID.eq("invest-owner")).execute())
                        }
                    }
                    raceApplied = true
                    expectedAfterRace = snapshot(base)
                } }
                try {
                    val observed = observedDatabase(trace)
                    testApplication {
                        application { financeModule(observed, clock = { now("fault") }) }
                        val response = client.get(path) { header(HttpHeaders.Authorization, "Bearer " + token("fault")) }
                        assertEquals(if (mode == "expiry") 401 else 403, response.status.value, mode + " " + response.bodyAsText())
                        assertFalse("fixture:investment" in response.bodyAsText())
                    }
                    assertEquals(1, trace.events); assertEquals(0, trace.writes)
                    assertEquals(assertNotNull(expectedAfterRace), snapshot(base))
                } finally {
                    base.write { connection ->
                        val dsl = connection.jooq(base.sqlite)
                        when (mode) {
                            "membership" -> if (raceApplied) dsl.insertInto(m).set(m.WORKSPACE_ID, member[m.WORKSPACE_ID]).set(m.USER_ID, member[m.USER_ID])
                                .set(m.ROLE, member[m.ROLE]).set(m.CREATED_AT, member[m.CREATED_AT]).execute()
                            "workspace" -> {
                                dsl.update(s).set(s.ACTIVE_WORKSPACE_ID, session[s.ACTIVE_WORKSPACE_ID]).where(s.ID.eq("invest-owner")).execute()
                                dsl.deleteFrom(m).where(m.WORKSPACE_ID.eq("other-workspace")).and(m.USER_ID.eq("invest-owner")).execute()
                            }
                            else -> dsl.update(s).set(s.EXPIRES_AT, session[s.EXPIRES_AT]).where(s.ID.eq("invest-owner")).execute()
                        }
                    }
                }
            }
        }
    }

    @Test fun pageAndFundingAccountsUseOneActualReadSnapshot() {
        val base = database("fault"); val a = ACCOUNTS
        val names = base.read { connection -> connection.jooq(base.sqlite).select(a.ID, a.NAME).from(a)
            .where(a.WORKSPACE_ID.eq("cash-web-workspace")).and(a.ID.`in`(101L, 103L)).fetch().associate { it[a.ID]!! to it[a.NAME]!! } }
        var expectedAfterRace: Map<String, List<String>>? = null
        val trace = ReadTrace { trace, sql -> if (sql.startsWith("select") && "from investment_events" in sql && trace.events == 1) {
            base.write { connection -> for ((id, _) in names) assertEquals(1, connection.jooq(base.sqlite).update(a)
                .set(a.NAME, "changed outside read " + id).where(a.WORKSPACE_ID.eq("cash-web-workspace")).and(a.ID.eq(id)).execute()) }
            expectedAfterRace = snapshot(base)
        } }
        try {
            val observed = observedDatabase(trace)
            testApplication {
                application { financeModule(observed, clock = { now("fault") }) }
                val response = client.get("/api/v1/investment-events") { header(HttpHeaders.Authorization, "Bearer " + token("fault")) }
                assertEquals(200, response.status.value, response.bodyAsText())
                val page = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(listOf("investment-003", "investment-002", "investment-001"), ids(page))
                assertEquals(JsonPrimitive(names.getValue(103L)), page.items().first().field("account", "name"))
                assertEquals(JsonPrimitive(names.getValue(101L)), page.items().last().field("relations").jsonArray.first().field("cash_account", "name"))
            }
            assertEquals(1, trace.versions); assertEquals(1, trace.events); assertEquals(1, trace.relations); assertEquals(0, trace.writes)
            assertEquals(assertNotNull(expectedAfterRace), snapshot(base))
        } finally {
            base.write { connection -> for ((id, name) in names) connection.jooq(base.sqlite).update(a).set(a.NAME, name)
                .where(a.WORKSPACE_ID.eq("cash-web-workspace")).and(a.ID.eq(id)).execute() }
        }
    }
}
