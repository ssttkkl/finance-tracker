package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import java.util.Base64
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class FundingRelationHttpContractTest {
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_FUNDING_HTTP_DIR")))
    private fun config(group: String): JsonObject = Json.parseToJsonElement(root().resolve(group).resolve("database.json").readText()).jsonObject
    private fun database(group: String = "native"): Database {
        val config = config(group)
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun normalize(raw: JsonArray) = JsonArray(raw.map { row -> JsonObject(row.jsonObject.mapValues { (key, value) ->
        if (key in setOf("created_at", "decided_at") && value != JsonNull) JsonPrimitive(pythonCashTimestamp(value.jsonPrimitive.content).toString()) else value
    }) })
    private fun rows(db: Database): JsonArray = db.read { c ->
        val r = CASH_INVESTMENT_FUNDING_RELATIONS
        JsonArray(c.jooq(db.sqlite).selectFrom(r).orderBy(r.ID).fetch().map { row -> buildJsonObject {
            for (field in r.fields()) {
                val value = row[field]
                put(field.name, when (value) {
                    is Instant -> JsonPrimitive(value.toString())
                    is JsonElement -> value
                    else -> sourceJson(value)
                })
            }
        } })
    }
    private fun unrelated(db: Database) = db.read { c ->
        DefaultSchema.DEFAULT_SCHEMA.tables.filter { it.name != "cash_investment_funding_relations" }.associate { table ->
            table.name to c.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"" + table.name + "\"").use { result ->
                buildList { while (result.next()) add((1..result.metaData.columnCount).map { result.getString(it) }.toString()) }.sorted()
            } }
        }
    }
    @Test fun pendingRouteIsAvailable() = testApplication {
        application { financeModule(database(), clock = { at }) }
        val response = client.get("/api/v1/funding-relations/pending") { header("Authorization", "Bearer funding-owner") }
        assertEquals(200, response.status.value, response.bodyAsText())
    }
    @Test fun originalHttpResponsesAndEveryPersistedRelationMatch() = testApplication {
        val db = database(); val before = unrelated(db)
        val contract = Json.parseToJsonElement(root().resolve("contracts.json").readText()).jsonObject
        application { financeModule(db, clock = { at }) }
        for (value in contract.getValue("cases").jsonArray) {
            val case = value.jsonObject; val name = case.getValue("name").jsonPrimitive.content
            val response = client.request(case.getValue("path").jsonPrimitive.content) {
                method = io.ktor.http.HttpMethod.parse(case.getValue("method").jsonPrimitive.content)
                if (case.getValue("token") != JsonNull) header("Authorization", "Bearer " + case.getValue("token").jsonPrimitive.content)
                header("Content-Type", "application/json")
                setBody(Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content))
            }
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, name + " " + response.bodyAsText())
            val expected = case.getValue("body")
            if (expected is JsonObject || expected is JsonArray) assertEquals(expected, Json.parseToJsonElement(response.bodyAsText()), name)
            else assertEquals(expected!!.jsonPrimitive.content, response.bodyAsText(), name)
            assertEquals(normalize(case.getValue("relations").jsonArray), rows(db), name)
        }
        assertEquals(before, unrelated(db))
        val paths = Json.parseToJsonElement(client.get("/openapi.json").bodyAsText()).jsonObject.getValue("paths").jsonObject
        for ((path, schema) in contract.getValue("paths").jsonObject) assertEquals(schema, paths[path], path)
    }
    @Test fun unicodeReasonCannotMaskMissingOrAlreadyDecidedRelation() = testApplication {
        val db = database("current")
        application { financeModule(db, clock = { at }) }
        suspend fun request(path: String, content: String = "{}") = client.post("/api/v1/funding-relations/" + path) {
            header("Authorization", "Bearer funding-owner"); header("Content-Type", "application/json"); setBody(content)
        }
        val invalid = "{\"reason\":\"\\ud800\"}"
        val missing = request("999999/confirm", invalid)
        assertEquals(409, missing.status.value)
        assertEquals("找不到资金调拨关系", Json.parseToJsonElement(missing.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        assertEquals(200, request("scan").status.value)
        assertEquals(200, request("1/confirm").status.value)
        val decided = request("1/confirm", invalid)
        assertEquals(409, decided.status.value)
        assertEquals("只能确认待审核资金调拨关系", Json.parseToJsonElement(decided.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
    }
    private fun instrumentedDatabase(group: String, stage: String, inserts: java.util.concurrent.atomic.AtomicInteger,
        reached: java.util.concurrent.atomic.AtomicInteger, afterLock: ((java.sql.Connection) -> Unit)? = null): Database {
        val config = config(group)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val sqlite = config.getValue("jdbc_url").jsonPrimitive.content.startsWith("jdbc:sqlite:")
        return Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD")) { connection ->
            var sqliteWrite = false
            fun execute(sql: String, action: () -> Any?): Any? {
                val normalized = sql.replace("\"", "").replace(96.toChar().toString(), "").trim()
                    .lowercase(java.util.Locale.ROOT).replace(Regex("\\s+"), " ")
                if (normalized == "commit" && stage == "before-commit") {
                    reached.incrementAndGet(); throw java.sql.SQLException("private commit boundary", "XX000")
                }
                val result = action()
                if (normalized == "begin immediate") sqliteWrite = true
                if (Regex("\\binsert into cash_investment_funding_relations\\b").containsMatchIn(normalized)) {
                    inserts.incrementAndGet()
                    if (stage == "post-insert-role") {
                        reached.incrementAndGet()
                        connection.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='funding-owner'").use {
                            it.setString(1, config.getValue("workspace").jsonPrimitive.content)
                            assertEquals(1, it.executeUpdate())
                        }
                    }
                    if (stage == "after-insert") {
                        reached.incrementAndGet(); throw java.sql.SQLException("private alias insert boundary", "XX000")
                    }
                }
                if (afterLock != null && normalized.startsWith("select workspaces.id from workspaces where ") &&
                    (sqlite && sqliteWrite || !sqlite && normalized.endsWith(" for update")) &&
                    once.compareAndSet(false, true)) {
                    reached.incrementAndGet(); afterLock(connection)
                }
                if (stage == "after-projection" && Regex("\\b(?:insert into|update) cash_projections\\b").containsMatchIn(normalized)) {
                    reached.incrementAndGet(); throw java.sql.SQLException("private projection boundary", "XX000")
                }
                if (normalized == "commit" && stage == "after-commit") {
                    reached.incrementAndGet(); throw java.sql.SQLException("private lost commit response", "XX000")
                }
                return result
            }
            java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection::class.java.classLoader,
                arrayOf(java.sql.Connection::class.java)) { _, method, args ->
                fun call(): Any? = try { method.invoke(connection, *(args ?: emptyArray())) }
                    catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                if (method.name == "commit") execute("COMMIT", ::call)
                else {
                    val result = call()
                    if (method.name in setOf("prepareStatement", "createStatement") && result is java.sql.Statement) {
                        val sql = if (method.name == "prepareStatement") args!![0] as String else null
                        val type = if (result is java.sql.PreparedStatement) java.sql.PreparedStatement::class.java else java.sql.Statement::class.java
                        java.lang.reflect.Proxy.newProxyInstance(java.sql.Statement::class.java.classLoader, arrayOf(type)) {
                                _, operation, values ->
                            fun invoke(): Any? = try { operation.invoke(result, *(values ?: emptyArray())) }
                                catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
                            if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch"))
                                execute(sql ?: values?.firstOrNull() as? String ?: "", ::invoke)
                            else invoke()
                        }
                    } else result
                }
            } as java.sql.Connection
        }
    }

    @Test fun realInsertAndCommitFaultsRollBackOrPersistExactlyOnce() {
        for (stage in listOf("after-insert", "before-commit", "after-commit")) testApplication {
            val db = database("fault"); val before = rows(db); val other = unrelated(db)
            val inserts = java.util.concurrent.atomic.AtomicInteger(); val reached = java.util.concurrent.atomic.AtomicInteger()
            val observed = instrumentedDatabase("fault", stage, inserts, reached)
            application { financeModule(observed, clock = { at }) }
            val response = client.post("/api/v1/funding-relations/scan") { header("Authorization", "Bearer funding-owner") }
            assertEquals(503, response.status.value, stage + " " + response.bodyAsText())
            assertEquals(JsonPrimitive("storage.connect"), Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject["code"])
            assertFalse("private" in response.bodyAsText()); assertEquals(1, reached.get(), stage)
            assertEquals(if (stage == "after-insert") 1 else 2, inserts.get(), stage)
            if (stage == "after-commit") assertEquals(2, rows(db).size) else assertEquals(before, rows(db), stage)
            assertEquals(other, unrelated(db), stage)
        }
    }
    @Test fun readyProjectionAndRelationAreOneAtomicWrite() = testApplication {
        val db = database("ready")
        CashProjectionService(db, "funding", { at }).rebuild()
        db.write { c ->
            assertTrue(c.lockWorkspace(db.sqlite, "funding"))
            assertEquals(2, FundingRelationRepository(CashSqlContext(c, "funding", db.sqlite, { at })).scan().size)
        }
        val before = rows(db); val other = unrelated(db)
        val inserts = java.util.concurrent.atomic.AtomicInteger(); val reached = java.util.concurrent.atomic.AtomicInteger()
        val observed = instrumentedDatabase("ready", "after-projection", inserts, reached)
        application { financeModule(observed, clock = { at }) }
        val response = client.post("/api/v1/funding-relations/1/confirm") {
            header("Authorization", "Bearer funding-owner"); header("Content-Type", "application/json"); setBody("{}")
        }
        assertEquals(503, response.status.value, response.bodyAsText()); assertEquals(1, reached.get())
        assertEquals(before, rows(db)); assertEquals(other, unrelated(db))
        FundingRelationService(db, { at }).write("funding-owner", ReadServices(db, { at }).currentContext("funding-owner"), "confirm", java.math.BigInteger.ONE)
        db.read { c ->
            val table = CASH_PROJECTIONS
            val projection = c.jooq(db.sqlite).selectFrom(table).where(table.FUNDING_RELATION_ID.eq(1L)).fetchSingle()
            assertEquals("internal_transfer", projection[table.ECONOMIC_TYPE])
            assertEquals("bank_security_transfer", projection[table.BUSINESS_LABEL])
            assertEquals(0, projection[table.NET_AMOUNT]!!.text.toBigDecimal().signum())
        }
    }
    @Test fun currentPermissionsAndWorkspaceAreRecheckedAfterRealLock() {
        for (mode in listOf("role", "workspace")) testApplication {
            val db = database("fault"); val before = rows(db); val other = unrelated(db)
            val inserts = java.util.concurrent.atomic.AtomicInteger(); val reached = java.util.concurrent.atomic.AtomicInteger()
            val observed = instrumentedDatabase("fault", "none", inserts, reached) { connection ->
                val sql = if (mode == "role") "UPDATE workspace_memberships SET role='viewer' WHERE workspace_id='funding' AND user_id='funding-owner'"
                    else "UPDATE user_sessions SET active_workspace_id='funding-other' WHERE user_id='funding-owner'"
                connection.createStatement().use { assertEquals(1, it.executeUpdate(sql)) }
            }
            application { financeModule(observed, clock = { at }) }
            val response = client.post("/api/v1/funding-relations/scan") { header("Authorization", "Bearer funding-owner") }
            assertEquals(403, response.status.value, mode + " " + response.bodyAsText())
            assertEquals(1, reached.get()); assertEquals(0, inserts.get())
            assertEquals(before, rows(db)); assertEquals(other, unrelated(db))
        }
    }
}
