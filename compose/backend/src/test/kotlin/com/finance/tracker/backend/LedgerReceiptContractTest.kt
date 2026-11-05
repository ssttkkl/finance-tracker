package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

class LedgerReceiptContractTest {
    private fun inWorkspace(action: (Database, String) -> Unit) {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json").readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val service = WorkspaceService(database)
        val workspace = service.create("synthetic-admin", "Ledger receipt synthetic test").activeWorkspaceId!!
        try { action(database, workspace) } finally {
            service.delete("synthetic-admin", "Ledger receipt synthetic test")
            service.select("synthetic-admin", "fixture-a")
        }
    }

    @Test fun exactLedgerSequenceSurvivesPersistenceReplayAndBothPublicReadPaths() = inWorkspace { database, workspace -> testApplication {
        application { financeModule(database) }
        val legacy = database.write { connection ->
            ReceiptRepository(connection, workspace, database.sqlite).create("admin", "legacy-without-sequence", "a".repeat(64), "import",
                mapOf("status" to "completed"), emptyList<Any>())
        }
        val legacyResponse = client.get("/api/v1/cash-mutations/receipts/${legacy.getValue("receipt_id").jsonPrimitive.content}") {
            bearerAuth("synthetic-admin")
        }
        assertEquals(200, legacyResponse.status.value)
        assertFalse("ledger_seq" in Json.parseToJsonElement(legacyResponse.bodyAsText()).jsonObject)
        for ((index, sequence) in listOf("0", "9007199254740993", Long.MAX_VALUE.toString()).withIndex()) {
            val key = "ledger-receipt-$index"
            val original = database.write { connection ->
                ReceiptRepository(connection, workspace, database.sqlite).create("admin", key, "a".repeat(64), "import",
                    mapOf("status" to "completed", "ledger_seq" to sequence), emptyList<Any>())
            }
            assertEquals(JsonPrimitive(sequence), original["ledger_seq"])
            database.read { connection ->
                assertEquals(original, ReceiptRepository(connection, workspace, database.sqlite).replay("admin", key, "a".repeat(64), "import"))
            }
            val receiptId = original.getValue("receipt_id").jsonPrimitive.content
            val bodies = listOf("/api/v1/cash-mutations/receipts/$receiptId", "/api/v1/cash-mutations/receipts/by-key?key=$key").map { path ->
                val response = client.get(path) { bearerAuth("synthetic-admin") }
                assertEquals(200, response.status.value, response.bodyAsText())
                Json.parseToJsonElement(response.bodyAsText()).jsonObject
            }
            assertEquals(bodies[0], bodies[1])
            assertEquals(JsonPrimitive(sequence), bodies[0]["ledger_seq"])
            assertFalse("request_digest" in bodies[0])
            assertFalse("projection_version" in bodies[0])
        }
    } }

    @Test fun invalidSequenceTypesAndRepresentationsCannotEnterReceiptSummaries() {
        for (bad in listOf(null, 1L, true, JsonPrimitive(1), JsonNull, "", "-1", "+1", "01", " 1", "1 ", "1.0",
            "1e0", "١", "9223372036854775808", "1".repeat(100))) {
            assertEquals("mutation_summary_invalid", assertFailsWith<IllegalArgumentException> {
                normalizeReceiptSummary(mapOf("ledger_seq" to bad))
            }.message)
        }
    }

    @Test fun corruptPersistedSequenceFailsInsteadOfDisappearingFromThePublicReceipt() = inWorkspace { database, workspace -> testApplication {
        application { financeModule(database) }
        val id = database.write { connection ->
            ReceiptRepository(connection, workspace, database.sqlite).create("admin", "corrupt-sequence", "a".repeat(64), "import",
                mapOf("ledger_seq" to "1"), emptyList<Any>()).getValue("receipt_id").jsonPrimitive.content
        }
        for (bad in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive("-1"), JsonPrimitive("9223372036854775808"), JsonPrimitive("private-invalid-version"))) {
            database.write { connection ->
                val json = if (database.sqlite) "?" else "CAST(? AS json)"
                connection.prepareStatement("UPDATE cash_mutation_receipts SET result_summary=$json WHERE workspace_id=? AND id=?").use {
                    it.setString(1, buildJsonObject { put("ledger_seq", bad) }.toString())
                    it.setString(2, workspace); it.setString(3, id); assertEquals(1, it.executeUpdate())
                }
            }
            val response = client.get("/api/v1/cash-mutations/receipts/$id") { bearerAuth("synthetic-admin") }
            assertEquals(503, response.status.value)
            assertFalse(response.bodyAsText().contains("private-invalid-version"))
        }
    } }
}
