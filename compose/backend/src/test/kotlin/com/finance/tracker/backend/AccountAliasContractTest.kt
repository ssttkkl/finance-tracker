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

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class AccountAliasContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_ALIAS_CONTRACT_DIR")))
    private fun config(section: String? = null): JsonObject = Json.parseToJsonElement(
        (if (section == null) root() else root().resolve(section)).resolve("database.json").readText()).jsonObject
    private fun database(section: String? = null): Database = config(section).let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private val at = Instant.parse("2026-10-01T09:00:00Z")
    private fun snapshot(database: Database, excludeAliases: Boolean = false) = database.read { connection ->
        DefaultSchema.DEFAULT_SCHEMA.tables.filter { !excludeAliases || it.name != "account_aliases" }.associate { table ->
            table.name to connection.createStatement().use {
                it.executeQuery("SELECT * FROM \"" + table.name + "\"").use { rows ->
                    buildList {
                        while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { column ->
                            rows.getString(column)?.let(::JsonPrimitive) ?: JsonNull
                        }).toString())
                    }.sorted()
                }
            }
        }
    }
    private fun aliasRows(database: Database): JsonArray = database.read { connection ->
        val alias = ACCOUNT_ALIASES
        JsonArray(connection.jooq(database.sqlite).selectFrom(alias).orderBy(alias.ID).fetch().map { row ->
            buildJsonObject {
                put("id", row[alias.ID]!!)
                put("workspace_id", row[alias.WORKSPACE_ID]!!)
                put("alias_type", row[alias.ALIAS_TYPE]!!)
                put("alias_value", row[alias.ALIAS_VALUE]!!)
                put("account_id", row[alias.ACCOUNT_ID]!!)
                put("created_at", row[alias.CREATED_AT]!!.toString())
                put("updated_at", row[alias.UPDATED_AT]!!.toString())
            }
        })
    }
    private fun normalizeDates(rows: JsonArray) = JsonArray(rows.map { value ->
        JsonObject(value.jsonObject.mapValues { (key, item) ->
            if (key in setOf("created_at", "updated_at")) JsonPrimitive(pythonCashTimestamp(item.jsonPrimitive.content).toString()) else item
        })
    })

    @Test fun missingAccountUsesOriginalErrorAndWritesNothing() = testApplication {
        val database = database()
        val before = snapshot(database)
        application { financeModule(database, clock = { at }) }
        val response = client.post("/api/v1/relations/aliases") {
            header("Authorization", "Bearer " + config().getValue("owner_token").jsonPrimitive.content)
            header("Content-Type", "application/json")
            setBody("""{"value":"1234","account":"missing-alias-account"}""")
        }
        assertEquals(400, response.status.value, response.bodyAsText())
        assertEquals(Json.parseToJsonElement("""{"error":{"code":"invalid_relation","message":"account.not_found"}}"""),
            Json.parseToJsonElement(response.bodyAsText()))
        assertEquals(before, snapshot(database))
    }

    @Test fun allLegacyHttpRequestsMatchOriginalAndOnlyAliasRowsChange() = testApplication {
        val database = database()
        val cases = Json.parseToJsonElement(root().resolve("contracts.json").readText()).jsonObject.getValue("cases").jsonArray
        assertEquals(66, cases.size)
        val before = snapshot(database, excludeAliases = true)
        application { financeModule(database, clock = { at }) }
        for (raw in cases) {
            val case = raw.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val response = client.post("/api/v1/relations/aliases") {
                val token = case.getValue("token")
                if (token != JsonNull) header("Authorization", "Bearer " + token.jsonPrimitive.content)
                header("Content-Type", "application/json")
                setBody(Base64.getDecoder().decode(case.getValue("content").jsonPrimitive.content))
            }
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, name + " " + response.bodyAsText())
            val expected = case.getValue("body")
            if (expected is JsonObject) assertEquals(expected, Json.parseToJsonElement(response.bodyAsText()), name)
            else assertEquals(expected.jsonPrimitive.content, response.bodyAsText(), name)
            assertEquals(normalizeDates(case.getValue("aliases").jsonArray), aliasRows(database), name)
        }
        assertEquals(before, snapshot(database, excludeAliases = true))
    }

    @Test fun originalAccountIdentifierAliasNormalizesSeparatorsAndRejectsInvalidValues() {
        val database = database("source")
        val config = config("source")
        database.write { connection ->
            val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, database.sqlite, { at })
            val repository = AccountAliasRepository(context)
            val accountId = assertNotNull(context.sql.select(ACCOUNTS.ID).from(ACCOUNTS)
                .where(ACCOUNTS.WORKSPACE_ID.eq(context.workspace)).and(ACCOUNTS.NAME.eq("\u76ee\u6807\u8d26\u6237"))
                .fetchOne(ACCOUNTS.ID))
            assertEquals(config.getValue("account_id").jsonPrimitive.content.toLong(), accountId)
            repository.add("account_identifier", "6222-0000-0000-1234", accountId)
            assertTrue(assertFailsWith<AccountAliasFailure> {
                repository.add("account_identifier", "\u793a\u4f8b\u8d26\u6237", accountId)
            }.message!!.contains("account_identifier"))
            assertEquals("6222000000001234", repository.list().first()["alias_value"])
        }
    }

    @Test fun actualOpenApiPreservesOriginalAliasOperation() = testApplication {
        val database = database()
        val expected = Json.parseToJsonElement(root().resolve("contracts.json").readText()).jsonObject.getValue("openapi")
        application { financeModule(database, clock = { at }) }
        val response = client.get("/openapi.json")
        assertEquals(200, response.status.value)
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(expected, document.getValue("paths").jsonObject["/api/v1/relations/aliases"])
    }

    private fun instrumentedDatabase(stage: String, inserts: java.util.concurrent.atomic.AtomicInteger,
        reached: java.util.concurrent.atomic.AtomicInteger, afterLock: ((java.sql.Connection) -> Unit)? = null): Database {
        val config = config("fault")
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
                if (Regex("\\binsert into account_aliases\\b").containsMatchIn(normalized)) {
                    inserts.incrementAndGet()
                    if (stage == "post-insert-role") {
                        reached.incrementAndGet()
                        connection.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='alias-owner'").use {
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
                            if (operation.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate"))
                                execute(sql ?: values?.firstOrNull() as? String ?: "", ::invoke)
                            else invoke()
                        }
                    } else result
                }
            } as java.sql.Connection
        }
    }

    @Test fun actualInsertAndCommitFaultsNeverRetryOrLeavePartialRows() {
        for ((index, stage) in listOf("after-insert", "before-commit", "after-commit").withIndex()) testApplication {
            val database = database("fault")
            val before = snapshot(database)
            val nonAliases = snapshot(database, excludeAliases = true)
            val rowsBefore = aliasRows(database)
            val inserts = java.util.concurrent.atomic.AtomicInteger()
            val reached = java.util.concurrent.atomic.AtomicInteger()
            val observed = instrumentedDatabase(stage, inserts, reached)
            val value = "910" + (index + 1)
            application { financeModule(observed, clock = { at }) }
            val response = client.post("/api/v1/relations/aliases") {
                header("Authorization", "Bearer " + config("fault").getValue("owner_token").jsonPrimitive.content)
                header("Content-Type", "application/json")
                setBody(buildJsonObject { put("value", value); put("account", "\u76ee\u6807\u8d26\u6237") }.toString())
            }
            assertEquals(503, response.status.value, stage + " " + response.bodyAsText())
            assertEquals(JsonPrimitive("storage.connect"), Json.parseToJsonElement(response.bodyAsText()).jsonObject
                .getValue("error").jsonObject["code"])
            assertFalse(response.bodyAsText().contains("private"))
            assertEquals(1, reached.get(), stage)
            assertEquals(1, inserts.get(), stage)
            if (stage == "after-commit") {
                val rows = aliasRows(database)
                assertEquals(rowsBefore.size + 1, rows.size)
                assertEquals(1, rows.count { it.jsonObject["alias_value"] == JsonPrimitive(value) })
                assertEquals(nonAliases, snapshot(database, excludeAliases = true))
            } else assertEquals(before, snapshot(database), stage)
        }
    }

    @Test fun livePermissionsWorkspaceAndAccountAreCheckedAfterTheActualWorkspaceLock() {
        for (mode in listOf("role", "workspace", "account", "post-insert-role")) testApplication {
            val database = database("fault")
            val config = config("fault")
            val workspace = config.getValue("workspace").jsonPrimitive.content
            val before = snapshot(database)
            val inserts = java.util.concurrent.atomic.AtomicInteger()
            val reached = java.util.concurrent.atomic.AtomicInteger()
            val observed = if (mode == "post-insert-role") instrumentedDatabase(mode, inserts, reached)
                else instrumentedDatabase("none", inserts, reached) { connection ->
                val sql = when (mode) {
                    "role" -> "UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='alias-owner'"
                    "workspace" -> "UPDATE user_sessions SET active_workspace_id='other-workspace' WHERE active_workspace_id=? AND user_id='alias-owner'"
                    else -> "DELETE FROM accounts WHERE workspace_id=? AND id=777"
                }
                connection.prepareStatement(sql).use {
                    it.setString(1, workspace); assertEquals(1, it.executeUpdate(), mode)
                }
            }
            application { financeModule(observed, clock = { at }) }
            val response = client.post("/api/v1/relations/aliases") {
                header("Authorization", "Bearer " + config.getValue("owner_token").jsonPrimitive.content)
                header("Content-Type", "application/json")
                setBody("""{"value":"9260","account":"\u76ee\u6807\u8d26\u6237"}""")
            }
            assertEquals(if (mode == "account") 400 else 403, response.status.value, mode + " " + response.bodyAsText())
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(JsonPrimitive(if (mode == "account") "invalid_relation" else "workspace_forbidden"), error["code"])
            if (mode == "account") assertEquals(JsonPrimitive("account.not_found"), error["message"])
            assertEquals(1, reached.get(), mode)
            assertEquals(if (mode == "post-insert-role") 1 else 0, inserts.get(), mode)
            assertEquals(before, snapshot(database), mode)
        }
    }

}
