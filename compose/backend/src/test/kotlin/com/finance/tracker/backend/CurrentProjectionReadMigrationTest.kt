package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentProjectionReadMigrationTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun currentDetailAndStableLocationPreservePrecisionAndReadOnlyState() = testApplication {
        val db = database()
        val workspaces = WorkspaceService(db)
        val name = "当前收支读取"
        val workspace = workspaces.create("synthetic-admin", name).activeWorkspaceId!!
        application { financeModule(db) }
        try {
            AccountService(db).create("synthetic-admin", buildJsonObject {
                put("name", "读取账户"); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
            }, "current-read-account")
            LegacyCashflowService(db).addTransaction("synthetic-admin",
                LegacyCashInput("读取账户", "-12.340000000000000001", "CNY", "2026-08-12T03:04:05.123456Z", counterparty = "当前商家"))
            val before = financialState(db, workspace)
            val identifier = db.read { c -> c.prepareStatement("SELECT id FROM cash_transactions WHERE workspace_id=?").use {
                it.setString(1, workspace); it.executeQuery().use { rows -> assertTrue(rows.next()); rows.getLong(1).toString() }
            } }
            val located = client.get("/api/v1/cash-records/$identifier/projection") { bearerAuth("synthetic-admin") }
            assertEquals(200, located.status.value, located.bodyAsText())
            val location = Json.parseToJsonElement(located.bodyAsText()).jsonObject
            assertEquals(JsonPrimitive(identifier), location["transaction_id"])
            val response = client.get("/api/v1/cash-projections/" + location.getValue("projection_id").jsonPrimitive.content) { bearerAuth("synthetic-admin") }
            assertEquals(200, response.status.value, response.bodyAsText())
            val detail = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(location["projection_version"], detail["projection_version"])
            val projection = detail.getValue("projection").jsonObject
            assertEquals(JsonPrimitive(identifier), projection["transaction_id"])
            assertEquals(JsonPrimitive("-12.340000000000000001"), projection["amount"])
            assertEquals(JsonPrimitive("2026-08-12T03:04:05.123456+00:00"), projection["occurred_at"])
            assertEquals(JsonPrimitive("confirmed"), projection["confirmation_status"])
            assertEquals(1, detail.getValue("members").jsonArray.size)
            assertEquals(JsonArray(emptyList()), detail["issues"])
            assertEquals(before, financialState(db, workspace))
        } finally {
            workspaces.delete("synthetic-admin", name)
            workspaces.select("synthetic-admin", "fixture-a")
        }
    }

    @Test fun invalidStableIdentifiersAndUnauthorizedReadsKeepTheirOriginalErrors() = testApplication {
        val db = database()
        application { financeModule(db) }
        for (identifier in listOf("0", "-1", "1.0", "9223372036854775808", "1".padStart(20, '0'), "true", "١", "1e3")) {
            val response = client.get("/api/v1/cash-records/$identifier/projection") { bearerAuth("synthetic-admin") }
            assertEquals(404, response.status.value, "$identifier ${response.bodyAsText()}")
            assertEquals(Json.parseToJsonElement("""{"error":{"code":"record_not_found","message":"未找到这条流水。","field_errors":[],"retryable":false}}"""), Json.parseToJsonElement(response.bodyAsText()))
        }
        for (path in listOf("/api/v1/cash-records/1/projection", "/api/v1/cash-projections/cash:1")) {
            val response = client.get(path)
            assertEquals(401, response.status.value)
            val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject
            assertEquals(JsonPrimitive("authentication_required"), error["code"])
            assertEquals(JsonArray(emptyList()), error["field_errors"])
            assertEquals(JsonPrimitive(false), error["retryable"])
        }
    }

    private fun financialState(db: Database, workspace: String) = db.read { c ->
        listOf("cash_transactions", "cash_transaction_components", "ledger_snapshots", "cash_projection_states", "cash_projection_datasets",
            "cash_projections", "cash_projection_members", "cash_projection_relations", "cash_entries", "cash_entry_members").associateWith { table ->
            c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {
                it.setString(1, workspace); it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map(rows::getString))
                } }
            }
        }
    }
}
