package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRecordDetailContractTest {
    private fun database(): Database {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_DETAIL_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        return Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun currentDetailMatchesEveryPythonFieldAndKeepsFinancialStateUnchanged() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_DETAIL_CONTRACT_DIR")))
        val db = database()
        application { financeModule(db) }
        val cases = Json.parseToJsonElement(root.resolve("detail-cases.json").readText()).jsonArray
        assertTrue(cases.size >= 35)
        val captures = mutableListOf<JsonObject>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val before = detailDatabaseState(db)
            val response = client.get(case.getValue("path").jsonPrimitive.content) {
                case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
            }
            val body = response.bodyAsText()
            val context = "$index ${case.getValue("path")} $body"
            assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
            if ("text" in case) assertEquals(case.getValue("text").jsonPrimitive.content, body, context)
            else assertEquals(case.getValue("body"), Json.parseToJsonElement(body), context)
            assertEquals(before, detailDatabaseState(db), context)
            if (response.status.value == 200) captures.add(buildJsonObject {
                put("path", case.getValue("path")); put("body", body)
            })
        }
        assertTrue(captures.isNotEmpty())
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("detail-responses.json"), buildJsonObject {
                put("backend", if (db.sqlite) "sqlite" else "postgresql")
                put("responses", JsonArray(captures))
            }.toString())
        }
    }

    @Test fun roleDowngradeBeforeReturnRemovesEditCapabilities() {
        val db = database()
        val workspace = sqlString(db, "SELECT workspace_id FROM workspace_memberships WHERE user_id='admin' LIMIT 1")
        try {
            val service = CashRecordDetailService(db, beforeReturn = {
                db.write { connection ->
                    connection.prepareStatement("UPDATE workspace_memberships SET role='viewer' WHERE workspace_id=? AND user_id='admin'").use {
                        it.setString(1, workspace); it.executeUpdate()
                    }
                }
            })
            val response = service.detail("synthetic-admin", "1001")
            assertEquals(JsonArray(emptyList()), response["editable_fields"])
            assertTrue((response["entry"]!!.jsonObject["editable_fields"] as JsonArray).isEmpty())
        } finally {
            db.write { connection ->
                connection.prepareStatement("UPDATE workspace_memberships SET role='admin' WHERE workspace_id=? AND user_id='admin'").use {
                    it.setString(1, workspace); it.executeUpdate()
                }
            }
        }
    }

    @Test fun corruptedSourceCoverageStopsTheReadWithoutWriting() {
        val db = database()
        val before = detailDatabaseState(db)
        try {
            db.write { connection ->
                connection.prepareStatement("UPDATE cash_source_bindings SET coverage=" +
                    (if (db.sqlite) "?" else "CAST(? AS json)") + " WHERE transaction_id=1015").use {
                    it.setString(1, "[]"); assertEquals(1, it.executeUpdate())
                }
            }
            assertFailsWith<CashRecordReadUnavailable> { CashRecordDetailService(db).detail("synthetic-admin", "1015") }
        } finally {
            db.write { connection ->
                connection.prepareStatement("UPDATE cash_source_bindings SET coverage=" +
                    (if (db.sqlite) "?" else "CAST(? AS json)") + " WHERE transaction_id=1015").use {
                    it.setString(1, "{}"); it.executeUpdate()
                }
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun corruptedPendingFieldsStopsTheReadWithoutWriting() {
        val db = database()
        val before = detailDatabaseState(db)
        try {
            db.write { connection ->
                connection.prepareStatement("UPDATE cash_transaction_issues SET pending_fields=" +
                    (if (db.sqlite) "?" else "CAST(? AS json)") + " WHERE id=6002").use {
                    it.setString(1, "[\"x\"]"); assertEquals(1, it.executeUpdate())
                }
            }
            assertFailsWith<CashRecordReadUnavailable> { CashRecordDetailService(db).detail("synthetic-admin", "1006") }
        } finally {
            db.write { connection ->
                connection.prepareStatement("UPDATE cash_transaction_issues SET pending_fields=NULL WHERE id=6002").use {
                    it.executeUpdate()
                }
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun membershipRemovalBeforeReturnStopsTheRead() {
        val db = database()
        val workspace = sqlString(db, "SELECT workspace_id FROM workspace_memberships WHERE user_id='viewer' LIMIT 1")
        try {
            val service = CashRecordDetailService(db, beforeReturn = {
                db.write { connection ->
                    connection.prepareStatement("DELETE FROM workspace_memberships WHERE workspace_id=? AND user_id='viewer'").use {
                        it.setString(1, workspace); it.executeUpdate()
                    }
                }
            })
            val failure = assertFailsWith<AccessFailure> { service.detail("synthetic-viewer", "1001") }
            assertTrue(failure.forbidden)
        } finally {
            db.write { connection ->
                connection.prepareStatement("INSERT INTO workspace_memberships(workspace_id,user_id,role,created_at) VALUES(?, 'viewer', 'viewer', '2026-01-01 00:00:00')").use {
                    it.setString(1, workspace); it.executeUpdate()
                }
            }
        }
    }

    private fun sqlString(db: Database, query: String): String = db.read { connection ->
        connection.createStatement().use { statement -> statement.executeQuery(query).use { rows ->
            assertTrue(rows.next()); rows.getString(1)
        } }
    }
}

/** 读取前后逐行比较全部维护相关表，证明详情读取不写入财务或维护状态。 */
internal fun detailDatabaseState(db: Database): String = db.read { connection ->
    val tables = listOf("workspace_memberships", "user_sessions", "cash_transactions", "cash_transaction_components",
        "cash_transaction_issues", "cash_issue_candidates", "transaction_relations", "cash_relation_candidates",
        "cash_entries", "cash_entry_members", "cash_source_bindings", "cash_projection_states",
        "cash_projection_datasets", "cash_projections", "cash_projection_members")
    tables.joinToString("|") { table ->
        connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM $table").use { rows ->
            val metadata = rows.metaData
            val values = mutableListOf<String>()
            while (rows.next()) values += (1..metadata.columnCount).joinToString(",") { index ->
                metadata.getColumnName(index) + "=" + rows.getString(index)
            }
            table + "{" + values.sorted().joinToString(";") + "}"
        } }
    }
}
