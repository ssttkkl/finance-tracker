package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashAssociationContractTest {
    private fun directory(name: String) = Path.of(requireNotNull(System.getenv("FT_KOTLIN_ASSOCIATION_CONTRACT_DIR"))).resolve(name)
    private inner class Fixture(name: String) {
        private val config = Json.parseToJsonElement(directory(name).resolve("database.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val token = "original-import-owner"
        fun database(decorate: (Connection) -> Connection = { it }) = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        val database = database()
        fun snapshot() = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
            table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val metadata = rows.metaData
                buildList { while (rows.next()) add(JsonArray((1..metadata.columnCount).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            } }
        } }
    }
    private fun fixture(name: String) = Fixture(name)
    private fun checkHttp(name: String) = testApplication {
        val f = fixture(name); application { financeModule(f.database) }
        val cases = Json.parseToJsonElement(directory(name).resolve("association-cases.json").readText()).jsonArray
        assertTrue(cases.size >= 6)
        for (raw in cases) {
            val case = raw.jsonObject; val before = f.snapshot()
            val response = client.get(case.getValue("path").jsonPrimitive.content) {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                val params = case.getValue("params")
                if (params is JsonArray) params.forEach { parameter(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.content) }
                else params.jsonObject.forEach { (key, value) -> parameter(key, value.jsonPrimitive.content) }
            }
            val body = response.bodyAsText()
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, "${case["params"]} $body")
            assertEquals(case.getValue("body"), Json.parseToJsonElement(body), "${case["params"]}")
            assertEquals(before, f.snapshot())
            if (name == "base" && case.getValue("params") == buildJsonObject { put("query", "原始账单备注"); put("amount", "100.00") }) {
                System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
                    val path = Path.of(output); Files.createDirectories(path)
                    Files.writeString(path.resolve("association-response.json"), buildJsonObject {
                        put("backend", if (f.database.sqlite) "sqlite" else "postgresql"); put("path", case.getValue("path"))
                        put("status", response.status.value); put("body", Json.parseToJsonElement(body))
                    }.toString())
                }
            }
        }
    }
    @Test fun associationHttpMatchesOriginalFieldsFiltersErrorsAndReadOnlyState() = checkHttp("base")
    @Test fun paginationMatchesOriginalAndExcludesManualRecords() = checkHttp("pagination")
    @Test fun localCalendarDatesMatchOriginal() = checkHttp("date")
    @Test fun wholeConfirmedClosureIsExcluded() = checkHttp("closure")
    @Test fun magnitudesRemainExactAcrossSignsAndSearchEscapesAreLiteral() = checkHttp("precision")
    @Test fun candidateReadDoesNotRequireReadyProjection() = checkHttp("unavailable")

    @Test fun originalSearchReturnsActualMembersOutsideMatchWindow() = testApplication {
        val f = fixture("base"); application { financeModule(f.database) }
        val before = f.snapshot()
        val response = client.get("/api/v1/cash-records/1101/association-targets") {
            header("Authorization", "Bearer ${f.token}"); parameter("query", "原始账单备注"); parameter("amount", "100.00")
        }
        assertEquals(200, response.status.value); val items = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("items").jsonArray
        assertEquals(listOf("1102"), items.map { it.jsonObject.getValue("transaction_id").jsonPrimitive.content })
        val item = items.single().jsonObject; assertTrue(item.getValue("row_revision").jsonPrimitive.long > 0)
        val fields = item.getValue("fields").jsonObject
        assertEquals(0, fields.getValue("amount").jsonPrimitive.content.toBigDecimal().compareTo("-100.00".toBigDecimal()))
        assertEquals("6102", fields.getValue("components").jsonArray.first().jsonObject.getValue("component_id").jsonPrimitive.content)
        val none = client.get("/api/v1/cash-records/1101/association-targets") {
            header("Authorization", "Bearer ${f.token}"); parameter("query", "自由搜索"); parameter("account_id", "999999")
        }
        assertEquals(200, none.status.value); assertEquals(JsonArray(emptyList()), Json.parseToJsonElement(none.bodyAsText()).jsonObject.getValue("items"))
        assertEquals(before, f.snapshot())
    }
    @Test fun originalSearchExcludesCurrentAndManualAndPaginatesWithoutDuplicates() = testApplication {
        val f = fixture("pagination"); application { financeModule(f.database) }; val before = f.snapshot()
        val first = client.get("/api/v1/cash-records/1101/association-targets") { header("Authorization", "Bearer ${f.token}"); parameter("limit", 1) }.apiBody()
        assertEquals(1, first.apiArray("items").size); assertTrue(first.apiText("next_cursor").isNotEmpty())
        val second = client.get("/api/v1/cash-records/1101/association-targets") {
            header("Authorization", "Bearer ${f.token}"); parameter("limit", 10); parameter("cursor", first.apiText("next_cursor"))
        }.apiBody()
        val ids = (first.apiArray("items") + second.apiArray("items")).map { it.jsonObject.apiText("transaction_id") }
        assertEquals(ids.size, ids.toSet().size); assertFalse("1101" in ids); assertFalse("1105" in ids); assertEquals(before, f.snapshot())
    }
    @Test fun originalSearchUsesExplicitLocalCalendarDay() = testApplication {
        val f = fixture("date"); application { financeModule(f.database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-records/1101/association-targets") {
            header("Authorization", "Bearer ${f.token}"); parameter("date_from", "2025-01-01"); parameter("date_to", "2025-01-01"); parameter("timezone_name", "Asia/Shanghai")
        }
        assertEquals(200, response.status.value)
        assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.apiArray("items").any { it.jsonObject.apiText("transaction_id") == "1102" })
        assertEquals(before, f.snapshot())
    }
    @Test fun workspaceChangeBeforeReturnRejectsOldCandidateData() {
        val f = fixture("revoke"); val before = f.snapshot(); var reached = 0
        val service = CashAssociationSearchService(f.database, beforeReturn = {
            reached++
            f.database.write { connection -> connection.jooq(f.database.sqlite)
                .update(com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS)
                .set(com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS.ACTIVE_WORKSPACE_ID, "association-other")
                .where(com.finance.tracker.backend.persistence.generated.tables.references.USER_SESSIONS.USER_ID.eq(f.user)).execute() }
        })
        assertTrue(assertFailsWith<AccessFailure> { service.list(f.token, "1101") }.forbidden); assertEquals(1, reached)
        assertEquals(before.filterKeys { it != "user_sessions" }, f.snapshot().filterKeys { it != "user_sessions" })
    }
    @Test fun internalReadFailureStopsWithoutPartialPageOrPrivateBody() = testApplication {
        val f = fixture("failure"); var reached = 0
        val database = f.database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            if (method.name == "prepareStatement" && (args!![0] as String).replace("`", "").replace("\"", "").lowercase().contains("cash_transactions")) {
                reached++; error("private association SQL body")
            }
            try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
        } as Connection }
        application { financeModule(database) }; val before = f.snapshot()
        val response = client.get("/api/v1/cash-records/1101/association-targets") { header("Authorization", "Bearer ${f.token}") }
        val body = response.bodyAsText(); assertEquals(503, response.status.value); assertEquals(1, reached)
        val error = Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject
        assertEquals("import_unavailable", error.getValue("code").jsonPrimitive.content)
        assertTrue(error.getValue("retryable").jsonPrimitive.boolean); assertFalse(body.contains("private association"))
        assertFalse(body.contains("items")); assertEquals(before, f.snapshot())
    }
    @Test fun candidateFieldsStayInSameSnapshotWhenFactsChangeAfterPageSelection() {
        val f = fixture("snapshot"); val before = f.snapshot(); var changed = false
        val oldRevision = f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.ID.eq(1102L).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace))).fetchSingle().value1()!! }
        val database = f.database { raw -> Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            val result = try { method.invoke(raw, *(args ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
            if (method.name == "prepareStatement" && result is PreparedStatement) {
                val query = (args!![0] as String).replace("`", "").replace("\"", "").lowercase()
                if (query.contains("order by") && query.contains("cash_transactions") && !query.contains("cash_transaction_components")) {
                    return@newProxyInstance Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) { _, operation, values ->
                        val value = try { operation.invoke(result, *(values ?: emptyArray())) } catch (failure: InvocationTargetException) { throw failure.cause ?: failure }
                        if (operation.name.startsWith("execute") && !changed) {
                            changed = true
                            f.database.write { connection -> connection.jooq(f.database.sqlite).update(CASH_TRANSACTIONS)
                                .set(CASH_TRANSACTIONS.NOTE, "snapshot-new").set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
                                .where(CASH_TRANSACTIONS.ID.eq(1102L).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace))).execute() }
                        }
                        value
                    }
                }
            }
            result
        } as Connection }
        val page = CashAssociationSearchService(database).list(f.token, "1101", mapOf("query" to "原始账单备注"))
        val candidate = page.apiArray("items").single().jsonObject
        assertTrue(changed); assertEquals("1102", candidate.apiText("transaction_id"))
        assertEquals(oldRevision, candidate.getValue("row_revision").jsonPrimitive.long)
        assertEquals("原始账单备注", candidate.getValue("fields").jsonObject.apiText("note"))
        assertEquals("snapshot-new", f.database.read { it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.NOTE).from(CASH_TRANSACTIONS)
            .where(CASH_TRANSACTIONS.ID.eq(1102L).and(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace))).fetchSingle().value1() })
        assertEquals(before.filterKeys { it != "cash_transactions" }, f.snapshot().filterKeys { it != "cash_transactions" })
    }
}
