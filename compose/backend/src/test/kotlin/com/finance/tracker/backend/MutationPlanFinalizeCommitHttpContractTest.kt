package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanFinalizeCommitHttpContractTest {
    @Test fun finalizeDraftCommitMatchesPythonReceiptStateAndBalance() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_FINALIZE_COMMIT_CONTRACT_DIR")))
        val expected = Json.parseToJsonElement(root.resolve("commit.json").readText()).jsonObject
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        application { financeModule(db) }
        val created = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("intent", "finalize_draft"); put("transaction_id", "2002"); put("expected_revision", 1)
                put("patch", buildJsonObject { put("note", "定稿") })
            }.toString())
        }
        assertEquals(200, created.status.value, created.bodyAsText())
        val planId = Json.parseToJsonElement(created.bodyAsText()).jsonObject.getValue("plan_id").jsonPrimitive.content
        val committed = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-finalize-draft")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(200, committed.status.value, committed.bodyAsText())
        val receipt = Json.parseToJsonElement(committed.bodyAsText()).jsonObject
        for ((key, value) in expected.getValue("receipt").jsonObject) assertEquals(value, receipt[key], key)
        val state = db.read { connection ->
            val entry = connection.createStatement().use { statement ->
                statement.executeQuery("""SELECT e.note,e.revision,e.manual_fields FROM cash_entries e
                    JOIN cash_entry_members m ON m.workspace_id=e.workspace_id AND m.entry_id=e.id
                    WHERE m.workspace_id='commit-a' AND m.transaction_id=2002""").use {
                    it.next(); Triple(it.getString(1), it.getLong(2), it.getString(3))
                }
            }
            val transaction = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT state,note,row_revision,manual_overrides FROM cash_transactions WHERE workspace_id='commit-a' AND id=2002").use {
                    it.next(); listOf(it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
                }
            }
            val projection = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT projection_version,availability,member_count FROM cash_projection_states WHERE workspace_id='commit-a'").use {
                    it.next(); Triple(it.getLong(1), it.getString(2), it.getLong(3))
                }
            }
            val snapshot = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT payload FROM ledger_snapshots WHERE workspace_id='commit-a'").use {
                    it.next(); it.getString(1)
                }
            }
            listOf(entry, transaction, projection, snapshot)
        }
        val expectedEntry = expected.getValue("entry").jsonObject
        @Suppress("UNCHECKED_CAST") val entry = state[0] as Triple<String, Long, String>
        assertEquals(expectedEntry.getValue("note").jsonPrimitive.content, entry.first)
        assertEquals(expectedEntry.getValue("revision").jsonPrimitive.long, entry.second)
        assertEquals(expectedEntry.getValue("manual_fields").jsonArray, Json.parseToJsonElement(entry.third))
        val expectedTransaction = expected.getValue("transaction").jsonObject
        @Suppress("UNCHECKED_CAST") val transaction = state[1] as List<Any>
        assertEquals(expectedTransaction.getValue("state").jsonPrimitive.content, transaction[0])
        assertEquals(expectedTransaction.getValue("note").jsonPrimitive.content, transaction[1])
        assertEquals(expectedTransaction.getValue("row_revision").jsonPrimitive.long, transaction[2])
        assertEquals(expectedTransaction.getValue("manual_overrides"), Json.parseToJsonElement(transaction[3] as String))
        val expectedProjection = expected.getValue("projection").jsonObject
        @Suppress("UNCHECKED_CAST") val projection = state[2] as Triple<Long, String, Long>
        assertEquals(expectedProjection.getValue("version").jsonPrimitive.long, projection.first)
        assertEquals(expectedProjection.getValue("availability").jsonPrimitive.content, projection.second)
        assertEquals(expectedProjection.getValue("member_count").jsonPrimitive.long, projection.third)
        assertEquals(expected.getValue("snapshot"), Json.parseToJsonElement(state[3] as String))
    }
}
