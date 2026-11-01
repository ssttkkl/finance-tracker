package com.finance.tracker.backend

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.util.Locale
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRelationOriginOriginalTest {
    private var capturing = false
    private val queries = mutableListOf<String>()
    private fun observed(connection: Connection): Connection = Proxy.newProxyInstance(Connection::class.java.classLoader,
        arrayOf(Connection::class.java)) { _, method, args ->
        try {
            val result = method.invoke(connection, *(args ?: emptyArray()))
            if (method.name == "prepareStatement" && args?.firstOrNull() is String && result is PreparedStatement) {
                val sql = (args[0] as String).replace("\"", "").replace(96.toChar().toString(), "").lowercase(Locale.ROOT)
                Proxy.newProxyInstance(PreparedStatement::class.java.classLoader, arrayOf(PreparedStatement::class.java)) {
                        _, execute, parameters ->
                    try {
                        if (capturing && execute.name in setOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate") &&
                            Regex("\\bfrom\\s+(?:[a-z_][a-z0-9_]*\\.)?cash_transactions\\b").containsMatchIn(sql)) queries += sql
                        execute.invoke(result, *(parameters ?: emptyArray()))
                    } catch (failure: InvocationTargetException) { throw failure.targetException }
                }
            } else result
        } catch (failure: InvocationTargetException) { throw failure.targetException }
    } as Connection

    @Test fun relationOriginUsesCompleteParentsAlreadyReadInSameCall() {
        val config = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_CONTRACT_DIR")), "database.json")
            .readText()).jsonObject
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), ::observed)
        val workspaces = WorkspaceService(database)
        val label = "Relation origin original"
        val workspace = workspaces.create("synthetic-admin", label).activeWorkspaceId!!
        try {
            val account = deletionAccount(database, workspace)
            database.write { connection ->
                val at = Instant.parse("2026-01-01T00:00:00Z")
                val ids = (0..4).map { index ->
                    deletionInsert(connection, database, "cash_transactions", linkedMapOf(
                        "workspace_id" to workspace, "account_id" to account, "state" to "posted", "record_origin" to "legacy",
                        "row_revision" to 1L, "cash_granularity" to "atomic", "record_id" to (1101 + index).toString(),
                        "source_type" to "context-fixture", "manual_overrides" to JsonObject(emptyMap()), "occurred_at" to at,
                        "amount" to BigDecimal(if (index in 2..3) "100" else "-100"), "currency" to "CNY",
                        "counterparty" to "", "counterparty_account" to "", "counterparty_account_attrs" to JsonArray(emptyList()),
                        "note" to "", "record_type" to if (index in 2..3) "refund" else "consumption",
                        "created_at" to at, "deleted_by" to "", "delete_reason" to ""))!!
                }
                val parts = ids.mapIndexed { index, id ->
                    val part = Math.addExact(id, 1_000_000_000_000L)
                    deletionInsert(connection, database, "cash_transaction_components", linkedMapOf(
                        "id" to part, "workspace_id" to workspace, "cash_transaction_id" to id, "account_id" to account,
                        "amount" to BigDecimal(if (index in 2..3) "100" else "-100"), "currency" to "CNY", "ordinal" to 0,
                        "label" to "", "source_key" to "", "metadata_json" to JsonObject(emptyMap()), "created_at" to at))
                    part
                }
                queries.clear(); capturing = true
                try {
                    CurrentCashRelationWriteRepository(CashSqlContext(connection, workspace, database.sqlite)).add(mapOf(
                        "kind" to "payment_mirror", "state" to "draft", "decision_group_id" to null,
                        "primary_transaction_id" to ids[0], "secondary_transaction_id" to ids[1],
                        "primary_component_id" to parts[0], "secondary_component_id" to parts[1],
                        "anchor_transaction_id" to ids[0], "anchor_component_id" to parts[0],
                        "primary_role" to "expense", "secondary_role" to "expense", "applied_amount" to "100"))
                } finally { capturing = false }
            }
            assertEquals(1, queries.size, queries.joinToString("\n"))
        } finally {
            workspaces.delete("synthetic-admin", label)
            workspaces.select("synthetic-admin", "fixture-a")
        }
    }
}

