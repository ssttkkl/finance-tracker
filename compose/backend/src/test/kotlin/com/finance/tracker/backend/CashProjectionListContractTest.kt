package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.Base64
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashProjectionListContractTest {
    private fun directory(name: String) = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PROJECTION_LIST_CONTRACT_DIR"))).resolve(name)
    private inner class Fixture(name: String) {
        val config = Json.parseToJsonElement(directory(name).resolve("database.json").readText()).jsonObject
        val context = Json.parseToJsonElement(directory(name).resolve("list-context.json").readText()).jsonObject
        val token = context.getValue("token").jsonPrimitive.content
        val workspace = context.getValue("workspace").jsonPrimitive.content
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val database = database()
        fun read(vararg values: Pair<String, String>): JsonObject {
            val before = snapshot()
            try { return CashProjectionListService(database).list(token, values.toMap()) }
            finally { assertEquals(before, snapshot()) }
        }
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
    }
    private fun checkHttp(name: String) = testApplication {
        val f = Fixture(name); application { financeModule(f.database) }
        val cases = Json.parseToJsonElement(directory(name).resolve("list-cases.json").readText()).jsonArray
        assertTrue(cases.size >= 2)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject; val before = f.snapshot()
            val response = client.get("/api/v1/cash-projections") {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                val params = case.getValue("params")
                if (params is JsonArray) params.forEach { parameter(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }
                else params.jsonObject.forEach { (key, value) -> parameter(key, value.jsonPrimitive.content) }
            }
            val body = response.bodyAsText(); val message = "$name/$index ${case["params"]} $body"
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, message)
            if ("body" in case) assertEquals(case.getValue("body"), Json.parseToJsonElement(body), message)
            else assertEquals(case.getValue("text").jsonPrimitive.content, body, message)
            assertEquals(before, f.snapshot())
            if (name == "base" && case.getValue("params") == buildJsonObject { put("start_index", 0); put("limit", 1) }) {
                System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
                    val path = Path.of(output); Files.createDirectories(path)
                    Files.writeString(path.resolve("list-response.json"), buildJsonObject {
                        put("backend", if (f.database.sqlite) "sqlite" else "postgresql"); put("status", response.status.value)
                        put("body", Json.parseToJsonElement(body))
                    }.toString())
                }
            }
        }
    }
    @Test fun listHttpMatchesOriginalFiltersPagesMetadataAndErrors() = checkHttp("base")
    @Test fun mixedUnknownDraftsPreservePositionsAndExactStatistics() = checkHttp("mixed")
    @Test fun emptyReadyDatasetRemainsAValidPage() = checkHttp("empty")
    @Test fun unreadyDatasetNeverReturnsOldRows() = checkHttp("unready")
    @Test fun deletedMembersDisappearBeforePublication() = checkHttp("deleted")
    @Test fun composedTransfersFundingAndDraftsMatchOriginal() = checkHttp("complex")

    private fun ids(page: JsonObject) = page.apiArray("items").map { it.jsonObject.apiText("projection_id") }
    @Test fun originalBrowserTimezoneBindsLocalCalendarBounds() {
        val input = CashProjectionListInputs.parse(mapOf("date_from" to "2026-07-01", "date_to" to "2026-07-01", "timezone" to "Asia/Tokyo"))
        assertEquals("Asia/Tokyo", input.filters["timezone"])
        assertEquals("2026-06-30T15:00:00Z", input.start.toString()); assertEquals("2026-07-01T15:00:00Z", input.end.toString())
    }
    @Test fun originalBrowserTimezoneRejectsUnknownZone() {
        assertEquals("invalid_filter", assertFailsWith<CashProjectionListFailure> { CashProjectionListInputs.parse(mapOf("date_from" to "2026-07-01", "timezone" to "Not/AZone")) }.code)
    }
    @Test fun maximumCalendarEndDateKeepsOriginalOverflowBoundary() {
        assertEquals("internal", assertFailsWith<CashProjectionListFailure> { CashProjectionListInputs.parse(mapOf("date_to" to "9999-12-31")) }.code)
    }
    @Test fun originalFilteredPagesKeepStableCursorAndBindAllFilters() {
        val f = Fixture("base"); val first = f.read("limit" to "2"); val second = f.read("limit" to "2", "cursor" to first.apiText("next_cursor"))
        assertEquals(listOf("cash:1003", "cash:1002", "cash:1001"), ids(first) + ids(second))
        assertEquals(listOf("cash:1003"), ids(f.read("category_id" to "category-food")))
        assertEquals(listOf("cash:1001"), ids(f.read("economic_type" to "income")))
        for ((key, value) in listOf("category_id" to "category-food", "timezone" to "Asia/Tokyo")) {
            assertEquals("invalid_cursor", assertFailsWith<CashProjectionListFailure> { f.read("cursor" to first.apiText("next_cursor"), key to value) }.code)
        }
    }
    @Test fun originalAbsolutePagesKeepFilteredTotalsAndBoundaryCounts() {
        val f = Fixture("base"); val first = f.read("limit" to "1", "start_index" to "0"); val version = first.apiText("projection_version")
        val pages = listOf(first, f.read("limit" to "1", "start_index" to "1", "projection_version" to version), f.read("limit" to "1", "start_index" to "2", "projection_version" to version))
        assertEquals(listOf("cash:1003", "cash:1002", "cash:1001"), pages.flatMap(::ids))
        pages.forEachIndexed { index, page ->
            assertEquals(3, page.getValue("total_count").jsonPrimitive.int); assertEquals(index, page.getValue("start_index").jsonPrimitive.int)
            assertEquals(index, page.getValue("items_before").jsonPrimitive.int); assertEquals(2 - index, page.getValue("items_after").jsonPrimitive.int)
        }
        val food = f.read("limit" to "5", "start_index" to "0", "category_id" to "category-food")
        assertEquals(listOf("cash:1003"), ids(food)); assertEquals(1, food.getValue("total_count").jsonPrimitive.int)
        val empty = f.read("limit" to "5", "start_index" to "0", "counterparty" to "不存在的交易对方")
        assertEquals(emptyList(), ids(empty)); assertEquals(0, empty.getValue("total_count").jsonPrimitive.int); assertEquals(JsonArray(emptyList()), empty["month_positions"])
    }
    @Test fun originalIndexRejectsCursorCombinationAndNegativeValues() {
        val f = Fixture("base"); val cursor = f.read("limit" to "1").apiText("next_cursor")
        val seek = f.read("limit" to "1", "start_index" to "1")
        assertEquals(listOf("cash:1002"), ids(seek)); assertTrue(seek.getValue("projection_version").jsonPrimitive.long > 0)
        for (values in listOf(arrayOf("limit" to "1", "start_index" to "0", "cursor" to cursor), arrayOf("limit" to "1", "start_index" to "-1", "projection_version" to "1"))) {
            assertEquals("invalid_cursor", assertFailsWith<CashProjectionListFailure> { f.read(*values) }.code)
        }
    }
    @Test fun originalGlobalOptionsDoNotShrinkWithCurrentFilters() {
        val f = Fixture("base"); val page = f.read("limit" to "1")
        val filtered = f.read("account_id" to "101", "counterparty" to "咖啡", "category_id" to "category-food", "currency" to "CNY",
            "date_from" to "2026-07-03", "date_to" to "2026-07-03", "amount_min" to "-20", "amount_max" to "-1", "economic_type" to "expense", "composition" to "single")
        val options = page.getValue("filter_options").jsonObject
        assertEquals(listOf("日用", "餐饮", "收入", "转账"), options.apiArray("categories").map { it.jsonObject.apiText("name") })
        assertEquals(JsonArray(listOf(JsonPrimitive("CNY"))), options["currencies"]); assertEquals(options, filtered["filter_options"])
    }
    @Test fun originalReadFailsClosedWithoutAnActiveBuild() {
        val f = Fixture("unready")
        assertEquals("projection.unavailable", assertFailsWith<CashProjectionListFailure> { f.read() }.code)
    }
    @Test fun originalNonObjectCursorsRejectAllFiveParameters() {
        val f = Fixture("base")
        for (payload in listOf("[]", "\"cursor\"", "0", "true", "null")) {
            val cursor = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            assertEquals("invalid_cursor", assertFailsWith<CashProjectionListFailure> { f.read("cursor" to cursor) }.code)
        }
    }
    @Test fun originalInvalidCursorContractRejectsAllElevenParameters() {
        val f = Fixture("base"); val cursor = f.read("limit" to "1").apiText("next_cursor")
        val payload = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor))).jsonObject
        val variants = listOf("v" to JsonPrimitive(true), "version" to JsonPrimitive(true), "workspace" to JsonPrimitive(1), "filters" to JsonArray(emptyList()),
            "sort_at" to JsonPrimitive(1), "sort_at" to JsonPrimitive("2026-07-03"), "projection_id" to JsonPrimitive(1), "projection_id" to JsonPrimitive(true),
            "projection_id" to JsonNull, "projection_id" to JsonArray(emptyList()), "projection_id" to JsonObject(emptyMap()))
        for ((key, value) in variants) {
            val invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(JsonObject(payload + (key to value)).toString().toByteArray())
            assertEquals("invalid_cursor", assertFailsWith<CashProjectionListFailure> { f.read("cursor" to invalid) }.code)
        }
    }
    @Test fun originalMixedDraftIndexAndCursorAgreeWithoutCountingUnknownStatistics() {
        val f = Fixture("mixed"); val first = f.read("start_index" to "0", "limit" to "1"); val version = first.apiText("projection_version")
        val middle = f.read("start_index" to "1", "limit" to "1", "projection_version" to version)
        val cold = f.read("start_index" to "1", "limit" to "1", "projection_version" to version); assertEquals(middle, cold)
        val empty = f.read("start_index" to "0", "projection_version" to version, "counterparty" to "no-matching-counterparty")
        assertEquals(emptyList(), ids(empty)); assertEquals(0, empty.getValue("total_count").jsonPrimitive.int); assertEquals(0, empty.getValue("pending_item_count").jsonPrimitive.int)
        val beyond = f.read("start_index" to "99", "projection_version" to version)
        assertEquals(emptyList(), ids(beyond)); assertEquals(3, beyond.getValue("total_count").jsonPrimitive.int); assertEquals(1, beyond.getValue("pending_item_count").jsonPrimitive.int)
        val end = f.read("start_index" to "2", "limit" to "1", "projection_version" to version)
        val c1 = f.read("limit" to "1"); val c2 = f.read("limit" to "1", "cursor" to c1.apiText("next_cursor")); val c3 = f.read("limit" to "1", "cursor" to c2.apiText("next_cursor"))
        fun members(page: JsonObject) = page.apiArray("items").map { it.jsonObject.apiText("transaction_id") }
        assertEquals(listOf(first, middle, end).flatMap(::members), listOf(c1, c2, c3).flatMap(::members))
        assertEquals(listOf(Triple("2026-10", 0, 1), Triple("unknown", 1, 2), Triple("2026-10", 2, 3)), first.apiArray("month_positions").map {
            val row = it.jsonObject; Triple(row.apiText("month"), row.getValue("start_index").jsonPrimitive.int, row.getValue("end_index").jsonPrimitive.int) })
        for (page in listOf(first, middle, end)) { assertEquals(3, page.getValue("total_count").jsonPrimitive.int); assertEquals(1, page.getValue("pending_item_count").jsonPrimitive.int) }
        val item = middle.apiArray("items").single().jsonObject; assertEquals(JsonNull, item["occurred_at"]); assertEquals(JsonPrimitive(false), item["statistics_eligible"])
        for (page in listOf(middle, end)) { assertEquals(JsonArray(emptyList()), page["monthly_summaries"]); assertEquals(JsonArray(emptyList()), page["month_positions"]) }
        val currencies = first.apiArray("monthly_summaries").single().jsonObject.apiArray("currencies")
        assertEquals(0, currencies.sumOf { it.jsonObject.apiText("expense").toBigDecimal() }.compareTo(first.apiArray("items").single().jsonObject.apiText("amount").toBigDecimal()))
        assertEquals(0, currencies.sumOf { it.jsonObject.apiText("income").toBigDecimal() }.compareTo(end.apiArray("items").single().jsonObject.apiText("amount").toBigDecimal()))
        val pending = f.read("start_index" to "0", "state" to "draft", "confirmation_status" to "pending", "limit" to "1")
        assertEquals(1, pending.getValue("total_count").jsonPrimitive.int); assertEquals(1, pending.getValue("pending_item_count").jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), pending["monthly_summaries"])
        assertEquals(JsonArray(listOf(buildJsonObject { put("month", "unknown"); put("start_index", 0); put("end_index", 1) })), pending["month_positions"])
    }
    @Test fun listKeepsRowsOptionsAndStatisticsInOneSnapshotAcrossActualRebuild() {
        val f = Fixture("snapshot"); val baseline = f.read("limit" to "2"); var rebuilt = 0
        val service = CashProjectionListService(f.database, afterStateRead = {
            rebuilt++
            f.database.write { connection ->
                val sql = connection.jooq(f.database.sqlite)
                sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.AMOUNT, CashStoredDecimal("-42.230000000000000001"))
                    .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L)).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(1003L)).execute()
                sql.update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal("-42.230000000000000001"))
                    .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTION_COMPONENTS.CASH_TRANSACTION_ID.eq(1003L)).execute()
            }
            CashProjectionService(f.database, f.workspace).rebuild()
        })
        assertEquals(baseline, service.list(f.token, mapOf("limit" to "2"))); assertEquals(1, rebuilt)
        val after = f.read("limit" to "2"); assertTrue(after.getValue("projection_version").jsonPrimitive.long > baseline.getValue("projection_version").jsonPrimitive.long)
        assertEquals("-42.230000000000000001", after.apiArray("items").first().jsonObject.apiText("amount"))
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionListFailure> { f.read("cursor" to baseline.apiText("next_cursor")) }.code)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionListFailure> { f.read("start_index" to "1", "projection_version" to baseline.apiText("projection_version")) }.code)
    }
    @Test fun categoryPathsLoadOneTreeForRowsAndGlobalOptions() {
        val f = Fixture("base"); val queries = mutableListOf<String>()
        val database = f.database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" && (args!![0] as String).contains("cash_categories")) queries += args[0] as String
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection }
        val before = f.snapshot(); val page = CashProjectionListService(database).list(f.token)
        assertEquals(3, page.apiArray("items").size); assertEquals(4, page.getValue("filter_options").jsonObject.apiArray("categories").size)
        assertEquals(1, queries.size, queries.joinToString("\n")); assertEquals(before, f.snapshot())
    }
    @Test fun listRejectsWorkspaceChangeBeforeReturningOldPage() {
        val f = Fixture("revoke"); val before = f.snapshot(); var reached = 0
        val service = CashProjectionListService(f.database, beforeReturn = {
            reached++
            f.database.write { it.jooq(f.database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "other-workspace").where(USER_SESSIONS.ID.eq(f.token)).execute() }
        })
        assertTrue(assertFailsWith<AccessFailure> { service.list(f.token) }.forbidden); assertEquals(1, reached)
        assertEquals(before.filterKeys { it != "user_sessions" }, f.snapshot().filterKeys { it != "user_sessions" })
    }
    @Test fun internalListFailureStopsWithoutPartialPageOrPrivateSql() = testApplication {
        val f = Fixture("failure"); var reached = 0
        val database = f.database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" && (args!![0] as String).replace("`", "").replace("\"", "").lowercase().contains("cash_projections")) { reached++; error("private list SQL body") }
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection }
        application { financeModule(database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-projections") { bearerAuth(f.token) }
        assertEquals(500, response.status.value); assertEquals("Internal Server Error", response.bodyAsText()); assertEquals(1, reached); assertEquals(before, f.snapshot())
    }
}
