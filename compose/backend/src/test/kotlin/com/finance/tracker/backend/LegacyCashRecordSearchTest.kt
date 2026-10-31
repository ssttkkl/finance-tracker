package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LegacyCashRecordSearchTest {
    private fun directory(name: String) = Path.of(requireNotNull(System.getenv("FT_KOTLIN_LEGACY_SEARCH_CONTRACT_DIR"))).resolve(name)
    private inner class Fixture(name: String) {
        private val config = Json.parseToJsonElement(directory(name).resolve("database.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val token = "original-import-owner"
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val database = database()
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
    }
    private fun checkHttp(name: String) = testApplication {
        val f = Fixture(name); application { financeModule(f.database) }
        val cases = Json.parseToJsonElement(directory(name).resolve("cases.json").readText()).jsonArray
        assertTrue(cases.size >= 4)
        val before = f.snapshot()
        for (raw in cases) {
            val case = raw.jsonObject
            val response = client.get("/api/v1/cash-records") {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                val params = case.getValue("params")
                if (params is JsonArray) params.forEach { parameter(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }
                else params.jsonObject.forEach { (key, value) -> parameter(key, value.jsonPrimitive.content) }
            }
            val body = response.bodyAsText(); val label = case["params"].toString()
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, "$label $body")
            val expected = case.getValue("body")
            if (expected is JsonPrimitive && expected.isString) assertEquals(expected.content, body, label)
            else assertEquals(expected, Json.parseToJsonElement(body), label)
            assertEquals(before, f.snapshot(), label)
        }
    }
    @Test fun originalSearchHttpContract() = checkHttp("search")
    @Test fun originalCapHttpContract() = checkHttp("cap")
    @Test fun originalDateHttpContract() = checkHttp("date")
    @Test fun originalTimezoneHttpContract() = checkHttp("timezone")
    @Test fun fullFieldsErrorsEscapingDraftsAndReadOnlyHttpMatchOriginal() = checkHttp("contract")

    @Test fun originalSearchIsFilteredBoundedAndStablyPaginated() {
        val f = Fixture("search"); val before = f.snapshot()
        val input = Json.parseToJsonElement(directory("search").resolve("input.json").readText()).jsonObject
        val filters = input.mapValues { it.value.jsonPrimitive.content }
        val service = LegacyCashRecordSearchService(f.database)
        val first = service.list(f.token, filters)
        val second = service.list(f.token, filters + ("cursor" to first.getValue("next_cursor").jsonPrimitive.content))
        assertEquals(listOf("候选商户 3", "候选商户 2"), names(first))
        assertEquals(listOf("候选商户 1"), names(second))
        assertTrue(first.getValue("next_cursor").jsonPrimitive.content.isNotEmpty())
        assertEquals(JsonNull, second["next_cursor"])
        val ids = first.getValue("items").jsonArray.map { it.jsonObject.getValue("id") }.toSet()
        assertTrue(second.getValue("items").jsonArray.none { it.jsonObject.getValue("id") in ids })
        assertEquals(before, f.snapshot())
    }
    @Test fun originalApiRejectsInvalidCursorAndCapsPageSize() = testApplication {
        val f = Fixture("cap"); application { financeModule(f.database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-records") { header("Authorization", "Bearer ${f.token}"); parameter("query", "分页候选"); parameter("limit", 200) }
        assertEquals(200, response.status.value)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(20, body.getValue("items").jsonArray.size); assertTrue(body.getValue("next_cursor").jsonPrimitive.content.isNotEmpty())
        val invalid = client.get("/api/v1/cash-records") { header("Authorization", "Bearer ${f.token}"); parameter("cursor", "not-a-cursor") }
        assertEquals(400, invalid.status.value); assertEquals("invalid_cursor", Json.parseToJsonElement(invalid.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(before, f.snapshot())
    }
    @Test fun originalApiFiltersInclusiveDateRange() = testApplication {
        val f = Fixture("date"); application { financeModule(f.database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-records") {
            header("Authorization", "Bearer ${f.token}"); parameter("query", "日期候选"); parameter("date_from", "2026-07-03"); parameter("date_to", "2026-07-05")
        }
        assertEquals(200, response.status.value); assertEquals(listOf("日期候选 5", "日期候选 4", "日期候选 3"), names(Json.parseToJsonElement(response.bodyAsText()).jsonObject))
        val invalid = client.get("/api/v1/cash-records") { header("Authorization", "Bearer ${f.token}"); parameter("date_from", "2026-07-06"); parameter("date_to", "2026-07-05") }
        assertEquals(400, invalid.status.value); assertEquals("invalid_filter", Json.parseToJsonElement(invalid.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(before, f.snapshot())
    }
    @Test fun originalApiAppliesDateRangeInRequestedTimezone() = testApplication {
        val f = Fixture("timezone"); application { financeModule(f.database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-records") {
            header("Authorization", "Bearer ${f.token}"); parameter("query", "本地日期"); parameter("date_from", "2026-07-03"); parameter("date_to", "2026-07-03"); parameter("timezone", "Asia/Shanghai")
        }
        assertEquals(200, response.status.value); assertEquals(listOf("本地日期 3 日"), names(Json.parseToJsonElement(response.bodyAsText()).jsonObject))
        assertEquals(before, f.snapshot())
    }
    private fun names(body: JsonObject) = body.getValue("items").jsonArray.map { it.jsonObject.getValue("counterparty").jsonPrimitive.content }

    @Test fun changedWorkspaceBeforeReturnRejectsOldData() {
        val f = Fixture("revoke"); val before = f.snapshot(); var reached = 0
        val service = LegacyCashRecordSearchService(f.database, beforeReturn = {
            reached++
            f.database.write { it.jooq(f.database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "legacy-other")
                .where(USER_SESSIONS.USER_ID.eq(f.user)).execute() }
        })
        assertTrue(assertFailsWith<AccessFailure> { service.list(f.token) }.forbidden); assertEquals(1, reached)
        assertEquals(before.filterKeys { it != "user_sessions" }, f.snapshot().filterKeys { it != "user_sessions" })
    }
    @Test fun pageComponentsAndAccountsShareOriginalSnapshot() {
        val f = Fixture("snapshot"); val before = f.snapshot(); var changed = 0
        val service = LegacyCashRecordSearchService(f.database, afterPageRead = {
            changed++
            f.database.write { connection ->
                connection.jooq(f.database.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "snapshot-new-account")
                    .where(ACCOUNTS.WORKSPACE_ID.eq(f.workspace)).and(ACCOUNTS.ID.eq(101L)).execute()
                connection.jooq(f.database.sqlite).update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.LABEL, "snapshot-new-part")
                    .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(f.workspace)).execute()
            }
        })
        val items = service.list(f.token, mapOf("query" to "候选")).getValue("items").jsonArray
        assertEquals(1, changed); assertEquals(4, items.size)
        for (item in items) {
            assertEquals("日常账户", item.jsonObject.getValue("account_name").jsonPrimitive.content)
            for (part in item.jsonObject.getValue("components").jsonArray) {
                assertEquals("日常账户", part.jsonObject.getValue("account_name").jsonPrimitive.content)
                assertNotEquals("snapshot-new-part", part.jsonObject.getValue("label").jsonPrimitive.content)
            }
        }
        assertEquals("snapshot-new-account", f.database.read { it.jooq(f.database.sqlite).select(ACCOUNTS.NAME).from(ACCOUNTS)
            .where(ACCOUNTS.WORKSPACE_ID.eq(f.workspace)).and(ACCOUNTS.ID.eq(101L)).fetchSingle().value1() })
        assertEquals(before.filterKeys { it !in setOf("accounts", "cash_transaction_components") }, f.snapshot().filterKeys { it !in setOf("accounts", "cash_transaction_components") })
    }
    @Test fun componentReadFailureStopsWholePageWithoutWrites() = testApplication {
        val f = Fixture("failure"); val before = f.snapshot(); var reached = 0
        val database = f.database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.replace("`", "")?.replace("\"", "")?.lowercase()?.contains("from cash_transaction_components") == true) {
                reached++; throw java.sql.SQLException("synthetic private connection failure")
            }
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection }
        application { financeModule(database) }
        val response = client.get("/api/v1/cash-records") { header("Authorization", "Bearer ${f.token}") }
        assertEquals(503, response.status.value); assertFalse(response.bodyAsText().contains("synthetic private"))
        assertEquals("storage.connect", Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals(1, reached); assertEquals(before, f.snapshot())
    }
}
