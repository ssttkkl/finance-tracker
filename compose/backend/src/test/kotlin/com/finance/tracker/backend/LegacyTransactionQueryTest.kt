package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.sql.Connection
import java.sql.Statement
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class LegacyTransactionQueryTest {
    private val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_TRANSACTION_QUERY_CONTRACT_DIR")))
    private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
    private fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content,
        System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
    private val database = database()
    private val token = "original-import-owner"
    private val workspace = config.getValue("workspace").jsonPrimitive.content
    private val accounts = Json.parseToJsonElement(directory.resolve("accounts.json").readText()).jsonObject
    private fun items(body: JsonObject) = body.getValue("items").jsonArray.map { it.jsonObject }
    private fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }

    @Test fun originalHttpContract() = testApplication {
        application { financeModule(database) }
        val cases = Json.parseToJsonElement(directory.resolve("cases.json").readText()).jsonArray
        assertTrue(cases.size > 40)
        val before = snapshot()
        for (raw in cases) {
            val case = raw.jsonObject
            val response = client.get("/api/v1/queries/transactions") {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                val params = case.getValue("params")
                if (params is JsonArray) params.forEach { parameter(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }
                else params.jsonObject.forEach { (key, value) -> parameter(key, value.jsonPrimitive.content) }
            }
            val body = response.bodyAsText()
            val label = case.getValue("params").toString().take(300)
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, "$label $body")
            val expected = case.getValue("body")
            if (expected is JsonPrimitive && expected.isString) assertEquals(expected.content, body, label)
            else assertEquals(expected, Json.parseToJsonElement(body), label)
            assertEquals(before, snapshot(), label)
        }
    }

    @Test fun originalApplicationForwardsCategoryIdRepositoryContract() {
        var called = 0
        val application = LegacyTransactionQueryApplication { filters ->
            called++
            assertEquals(LegacyTransactionFilters(categoryId = "food"), filters)
            listOf(LegacyTransactionRow("2026-06-01", "Cash", "CNY", CashStoredDecimal("-1"), "food", note = "meal"))
                .filter { it.categoryId == filters.categoryId }
        }
        assertEquals(listOf(JsonPrimitive("meal")), items(application.list(LegacyTransactionFilters(categoryId = "food"))).map { it["note"] })
        assertEquals(1, called)
    }

    @Test fun originalApplicationFiltersSortsLimitsAndReturnsExactDtos() {
        val rows = listOf(
            LegacyTransactionRow("2026-06-01", "Cash", "CNY", CashStoredDecimal("-1"), recordType = "consumption", note = "one"),
            LegacyTransactionRow("2026-06-03", "Cash", "CNY", CashStoredDecimal("-3"), recordType = "consumption", note = "three"),
            LegacyTransactionRow("2026-06-02", "Other", "CNY", CashStoredDecimal("-2"), recordType = "consumption", note = "two"))
        var called = 0
        val application = LegacyTransactionQueryApplication { filters ->
            called++
            assertEquals(LegacyTransactionFilters("2026-06", "Cash", limit = 1), filters)
            rows.filter { it.occurredAt!!.startsWith(filters.month!!) && it.accountName == filters.account }
                .sortedByDescending { it.occurredAt }.take(filters.limit)
        }
        val result = items(application.list(LegacyTransactionFilters("2026-06", "Cash", limit = 1)))
        assertEquals(1, result.size)
        assertEquals(JsonPrimitive("three"), result.single()["note"])
        assertEquals("-3".toBigDecimal(), result.single().getValue("amount").jsonPrimitive.content.toBigDecimal())
        assertEquals(1, called)
    }

    @Test fun dtoDefaultsNullsAndDatabaseZeroStayCompatible() {
        val rows = listOf(LegacyTransactionRow(), LegacyTransactionRow(null, null, "", CashStoredDecimal("-0.000"),
            recordType = null, recordSubtype = null, note = null, counterparty = null))
        val result = items(LegacyTransactionQueryApplication { rows }.list())
        assertEquals(JsonPrimitive(""), result[0]["occurred_at"])
        assertEquals(JsonPrimitive("other"), result[0]["record_type"])
        assertEquals(JsonPrimitive("not_applicable"), result[0]["record_subtype"])
        for (row in result) {
            assertEquals(JsonPrimitive("0"), row["amount"]); assertEquals(JsonPrimitive("CNY"), row["currency"])
            assertEquals(JsonPrimitive(""), row["transfer_account"])
        }
        for (field in listOf("occurred_at", "account_name", "record_type", "record_subtype", "note", "counterparty")) assertEquals(JsonNull, result[1][field])
    }

    @Test fun postedFiltersAccountFallbackUtcBoundsAndStableCap() {
        val before = snapshot()
        val service = LegacyTransactionQueryService(database)
        val all = items(service.list(token, mapOf("limit" to "200")))
        assertEquals(200, all.size); assertEquals("query-1", all.first().getValue("note").jsonPrimitive.content)
        assertEquals("query-208", all[1].getValue("note").jsonPrimitive.content)
        assertTrue(all.none { it["note"] in setOf(JsonPrimitive("query-209"), JsonPrimitive("query-210"), JsonPrimitive("foreign")) })
        assertEquals(30, items(service.list(token)).size)
        val july = items(service.list(token, mapOf("month" to "2026-07", "category_id" to "query-food")))
        assertEquals(listOf("query-1", "query-4", "query-3", "query-2"), july.map { it.getValue("note").jsonPrimitive.content })
        assertEquals(listOf(accounts["101"], JsonPrimitive(""), JsonPrimitive(""), accounts["102"]), july.map { it["account_name"] })
        assertEquals(JsonPrimitive("0"), july.first()["amount"])
        assertEquals(JsonPrimitive(if (database.sqlite) "-12.5000" else "-12.5"), july.last()["amount"])
        val inactive = items(service.list(token, mapOf("account" to accounts.getValue("102").jsonPrimitive.content, "category_id" to "query-food")))
        assertEquals(listOf("query-4", "query-3", "query-2"), inactive.map { it.getValue("note").jsonPrimitive.content })
        val june = items(service.list(token, mapOf("month" to "2026-06", "category_id" to "query-food"))).single()
        assertEquals(JsonPrimitive("9007199254740993.000000000000000001"), june["amount"])
        assertEquals(JsonPrimitive("2026-06-30T23:59:59.123456+00:00"), june["occurred_at"])
        assertEquals(setOf("occurred_at", "account_name", "currency", "amount", "category_id", "record_type", "record_subtype", "note", "counterparty", "transfer_account"), june.keys)
        assertEquals(before, snapshot())
    }

    @Test fun pageAndComponentAccountNamesShareOneSnapshot() {
        val before = snapshot(); var reached = 0
        val oldName = accounts.getValue("102").jsonPrimitive.content
        try {
            val service = LegacyTransactionQueryService(database, afterPageRead = {
                reached++
                database.write { it.jooq(database.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "query-changed-name")
                    .where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(102L)).execute() }
            })
            val rows = items(service.list(token, mapOf("category_id" to "query-food")))
            assertEquals(1, reached)
            assertEquals(JsonPrimitive(oldName), rows.single { it["note"] == JsonPrimitive("query-2") }["account_name"])
            assertEquals("query-changed-name", database.read { it.jooq(database.sqlite).select(ACCOUNTS.NAME).from(ACCOUNTS).where(ACCOUNTS.ID.eq(102L)).fetchSingle().value1() })
        } finally {
            database.write { it.jooq(database.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, oldName).where(ACCOUNTS.WORKSPACE_ID.eq(workspace)).and(ACCOUNTS.ID.eq(102L)).execute() }
        }
        assertEquals(before, snapshot())
    }

    @Test fun workspaceChangeBeforeReturnRejectsReadData() {
        val before = snapshot(); var reached = 0
        val id = config.getValue("user").jsonPrimitive.content
        try {
            val service = LegacyTransactionQueryService(database, beforeReturn = {
                reached++
                database.write { it.jooq(database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, "query-other").where(USER_SESSIONS.USER_ID.eq(id)).execute() }
            })
            assertTrue(assertFailsWith<AccessFailure> { service.list(token) }.forbidden)
            assertEquals(1, reached)
            assertEquals(before.filterKeys { it != "user_sessions" }, snapshot().filterKeys { it != "user_sessions" })
        } finally {
            database.write { it.jooq(database.sqlite).update(USER_SESSIONS).set(USER_SESSIONS.ACTIVE_WORKSPACE_ID, workspace).where(USER_SESSIONS.USER_ID.eq(id)).execute() }
        }
        assertEquals(before, snapshot())
    }

    @Test fun componentSqlFailureStopsWholeResponseAndDoesNotWrite() = testApplication {
        val before = snapshot(); var reached = 0
        val failing = database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" && (args?.firstOrNull() as? String)?.replace("`", "")?.replace("\"", "")?.lowercase()?.contains("from cash_transaction_components") == true) {
                reached++; throw java.sql.SQLException("synthetic private query failure")
            }
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection }
        application { financeModule(failing) }
        val response = client.get("/api/v1/queries/transactions") { header("Authorization", "Bearer $token") }
        assertEquals(503, response.status.value); assertFalse(response.bodyAsText().contains("synthetic private"))
        assertEquals(JsonPrimitive("storage.connect"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
        assertEquals(1, reached); assertEquals(before, snapshot())
    }

    @Test fun queryIssuesNoMutationSql() {
        val before = snapshot(); var queries = 0
        fun inspect(query: String) {
            val normalized = query.trim().lowercase()
            assertTrue(normalized.startsWith("select ") || normalized == "set transaction isolation level repeatable read", query)
            queries++
        }
        val observed = database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement") inspect(args!![0] as String)
            assertNotEquals("prepareCall", method.name)
            val result = try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            if (method.name != "createStatement") result else Proxy.newProxyInstance(Statement::class.java.classLoader, arrayOf(Statement::class.java)) { _, action, values ->
                if (action.name.startsWith("execute") || action.name == "addBatch") (values?.firstOrNull() as? String)?.let(::inspect)
                try { action.invoke(result, *(values ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            }
        } as Connection }
        assertEquals(5, items(LegacyTransactionQueryService(observed).list(token, mapOf("category_id" to "query-food"))).size)
        assertTrue(queries >= 4); assertEquals(before, snapshot())
    }

    @Test fun internalFailureStopsWholeResponseWithoutDetails() = testApplication {
        val before = snapshot(); var reached = 0
        application { financeModule(database, transactionQueries = LegacyTransactionQueryService(database, afterPageRead = {
            reached++; error("synthetic private application failure")
        })) }
        val response = client.get("/api/v1/queries/transactions") { header("Authorization", "Bearer $token") }
        assertEquals(500, response.status.value); assertEquals("Internal Server Error", response.bodyAsText())
        assertEquals(1, reached); assertEquals(before, snapshot())
    }
}
