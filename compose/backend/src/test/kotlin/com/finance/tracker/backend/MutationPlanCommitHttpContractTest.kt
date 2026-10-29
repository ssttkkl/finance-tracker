package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanCommitHttpContractTest {
    private fun database() = Json.parseToJsonElement(Path.of(requireNotNull(
        System.getenv("FT_KOTLIN_COMMIT_CONTRACT_DIR"))).resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun editRecordCommitMatchesPythonReceiptAndState() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_COMMIT_CONTRACT_DIR")))
        val expected = Json.parseToJsonElement(root.resolve("commit.json").readText()).jsonObject
        val db = database()
        application { financeModule(db) }
        val created = client.post("/api/v1/cash-mutations/plans") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "2001"); put("expected_revision", 1)
                put("patch", buildJsonObject { put("note", "提交备注") })
            }.toString())
        }
        assertEquals(200, created.status.value, created.bodyAsText())
        val plan = Json.parseToJsonElement(created.bodyAsText()).jsonObject
        val planId = plan.getValue("plan_id").jsonPrimitive.content
        val committed = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(200, committed.status.value, committed.bodyAsText())
        val receipt = Json.parseToJsonElement(committed.bodyAsText()).jsonObject
        val expectedReceipt = expected.getValue("receipt").jsonObject
        for ((key, value) in expectedReceipt) assertEquals(value, receipt[key], key)
        assertEquals(planId, receipt.getValue("plan_id").jsonPrimitive.content)
        val state = db.read { connection ->
            val entry = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT note,revision,manual_fields FROM cash_entries WHERE workspace_id='commit-a' AND id='entry-2001'").use {
                    it.next(); Triple(it.getString(1), it.getLong(2), it.getString(3))
                }
            }
            val transaction = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT note,row_revision FROM cash_transactions WHERE workspace_id='commit-a' AND id=2001").use {
                    it.next(); it.getString(1) to it.getLong(2)
                }
            }
            val projection = connection.createStatement().use { statement ->
                statement.executeQuery("SELECT projection_version,availability,member_count FROM cash_projection_states WHERE workspace_id='commit-a'").use {
                    it.next(); Triple(it.getLong(1), it.getString(2), it.getLong(3))
                }
            }
            Triple(entry, transaction, projection)
        }
        val expectedEntry = expected.getValue("entry").jsonObject
        assertEquals(expectedEntry.getValue("note").jsonPrimitive.content, state.first.first)
        assertEquals(expectedEntry.getValue("revision").jsonPrimitive.long, state.first.second)
        assertEquals(expectedEntry.getValue("manual_fields").jsonArray.size, Json.parseToJsonElement(state.first.third).jsonArray.size)
        assertEquals(expected.getValue("transaction").jsonObject.getValue("note").jsonPrimitive.content, state.second.first)
        assertEquals(expected.getValue("transaction").jsonObject.getValue("row_revision").jsonPrimitive.long, state.second.second)
        assertEquals(expected.getValue("projection").jsonObject.getValue("version").jsonPrimitive.long, state.third.first)
        assertEquals(expected.getValue("projection").jsonObject.getValue("availability").jsonPrimitive.content, state.third.second)
        assertEquals(expected.getValue("projection").jsonObject.getValue("member_count").jsonPrimitive.long, state.third.third)

        val replay = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(200, replay.status.value)
        assertEquals(receipt.getValue("receipt_id"), Json.parseToJsonElement(replay.bodyAsText())
            .jsonObject.getValue("receipt_id"))
        val consumed = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            header("Idempotency-Key", "commit-edit-record-2")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(409, consumed.status.value)
        assertEquals("edit_context_committed", errorCode(consumed.bodyAsText()))
        val missing = client.post("/api/v1/cash-mutations/plans/$planId/commit") {
            header(HttpHeaders.Authorization, "Bearer synthetic-admin")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("expected_plan_revision", 1) }.toString())
        }
        assertEquals(422, missing.status.value)
        assertEquals("idempotency_key_required", errorCode(missing.bodyAsText()))
    }

    private fun errorCode(body: String): String? =
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull
}
