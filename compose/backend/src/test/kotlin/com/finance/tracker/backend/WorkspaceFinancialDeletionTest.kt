package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class WorkspaceFinancialDeletionTest {
    @Test fun workspaceDeleteRemovesImportedCashProjectionRelationsAndEntries() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")),
            "database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val workspaces = WorkspaceService(db)
        val created = workspaces.create("synthetic-admin", "微信流水删除工作区")
        val workspace = created.activeWorkspaceId!!
        try {
            val category = CategoryService(db).create("synthetic-admin", "餐饮").id
            val account = deletionAccount(db, workspace)
            val fact = db.write { connection ->
                val id = deletionFact(connection, db, workspace, account, category)
                connection.prepareStatement("UPDATE cash_transactions SET source_type='wechat',record_id='wechat-imported-003' WHERE workspace_id=? AND id=?").use {
                    it.setString(1, workspace); it.setLong(2, id); it.executeUpdate()
                }
                deletionInsert(connection, db, "cash_transaction_components", linkedMapOf(
                    "workspace_id" to workspace, "cash_transaction_id" to id, "account_id" to account,
                    "amount" to BigDecimal("-10"), "currency" to "CNY", "ordinal" to 0L, "label" to "", "source_key" to "",
                    "metadata_json" to JsonObject(emptyMap()), "created_at" to deletionTime))
                deletionEntry(connection, db, workspace, "$workspace-entry", id, category)
                deletionInsert(connection, db, "cash_entry_members", linkedMapOf(
                    "workspace_id" to workspace, "transaction_id" to id, "entry_id" to "$workspace-entry",
                    "standalone_values" to JsonObject(emptyMap()), "standalone_manual_fields" to JsonArray(emptyList())))
                id
            }
            val dataset = db.read { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT active_dataset_id FROM cash_projection_states WHERE workspace_id='$workspace'").use {
                        it.next(); it.getString(1)
                    }
                }
            }
            val relation = db.write { connection ->
                val id = deletionRelation(connection, db, workspace, fact)
                val projection = deletionProjection(connection, db, workspace, dataset, fact, account, category, 1)
                connection.prepareStatement("UPDATE cash_projection_states SET projection_version=1,projection_count=1,member_count=1 WHERE workspace_id=?").use {
                    it.setString(1, workspace); it.executeUpdate()
                }
                deletionInsert(connection, db, "cash_projection_members", linkedMapOf(
                    "workspace_id" to workspace, "dataset_id" to dataset, "projection_row_id" to projection,
                    "cash_transaction_id" to fact, "roles_json" to JsonArray(listOf(JsonPrimitive("root"))), "ordinal" to 0))
                deletionInsert(connection, db, "cash_projection_relations", linkedMapOf(
                    "workspace_id" to workspace, "dataset_id" to dataset, "projection_row_id" to projection,
                    "transaction_relation_id" to id, "kind" to "transfer_pair", "subtype" to "internal_transfer", "ordinal" to 0))
                id
            }
            assertTrue(fact > 0 && relation > 0)
            val result = workspaces.delete("synthetic-admin", "微信流水删除工作区")
            assertEquals("fixture-a", result.activeWorkspaceId)
            val counts = db.read { connection ->
                listOf("workspaces" to "id", "accounts" to "workspace_id", "cash_transactions" to "workspace_id",
                    "cash_transaction_components" to "workspace_id", "cash_entries" to "workspace_id",
                    "cash_entry_members" to "workspace_id", "transaction_relations" to "workspace_id",
                    "cash_projections" to "workspace_id", "cash_projection_datasets" to "workspace_id",
                    "cash_projection_states" to "workspace_id", "cash_projection_members" to "workspace_id",
                    "cash_projection_relations" to "workspace_id").associate { (table, column) ->
                    table to connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM $table WHERE $column='$workspace'").use {
                            it.next(); it.getLong(1)
                        }
                    }
                }
            }
            assertEquals(emptySet(), counts.filterValues { it != 0L }.keys)
        } finally {
            if (db.read { connection -> connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM workspaces WHERE id='$workspace'").use { it.next(); it.getLong(1) }
                } } > 0) workspaces.delete("synthetic-admin", "微信流水删除工作区")
            workspaces.select("synthetic-admin", "fixture-a")
        }
    }
}
