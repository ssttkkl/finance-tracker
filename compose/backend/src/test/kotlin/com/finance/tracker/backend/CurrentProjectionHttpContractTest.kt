package com.finance.tracker.backend

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentProjectionHttpContractTest {
    @Test fun currentDetailsAndStableLocationsMatchCompletePythonResponsesWithoutWrites() = testApplication {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PROJECTION_CONTRACT_DIR")))
        val config = Json.parseToJsonElement(root.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val failure = AtomicBoolean()
        application { financeModule(db, currentProjections = CurrentProjectionReadService(db,
            afterStateRead = { if (failure.get()) throw StorageFailure("storage.connect") })) }
        val cases = Json.parseToJsonElement(root.resolve("current-projection-reads.json").readText()).jsonArray
        assertTrue(cases.size >= 70)
        val captures = mutableListOf<JsonObject>()
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            fun sql(key: String) = db.write { c -> for (action in case.getValue(key).jsonArray) c.createStatement().use { it.execute(action.jsonPrimitive.content) } }
            sql("actions")
            try {
                failure.set(case.getValue("failure").jsonPrimitive.boolean)
                val before = currentProjectionDatabaseState(db)
                val response = client.get(case.getValue("path").jsonPrimitive.content) {
                    case.getValue("headers").jsonObject.forEach { (key, value) -> header(key, value.jsonPrimitive.content) }
                }
                val text = response.bodyAsText()
                val context = "$index ${case.getValue("path")} $text"
                assertEquals(case.getValue("status").jsonPrimitive.int, response.status.value, context)
                if ("text" in case) assertEquals(case.getValue("text").jsonPrimitive.content, text, context)
                else assertEquals(case.getValue("body"), Json.parseToJsonElement(text), context)
                assertEquals(before, currentProjectionDatabaseState(db), "read changed financial state: $context")
                if (response.status.value == 200 && case.getValue("path").jsonPrimitive.content.startsWith("/api/v1/cash-projections/")) {
                    assertFalse("私有" in text, "pending source data leaked")
                    captures += buildJsonObject {
                        put("path", case.getValue("path")); put("body", text); put("headers", case.getValue("headers"))
                    }
                }
            } finally { sql("undo"); failure.set(false) }
        }
        assertTrue(captures.isNotEmpty())
        System.getenv("FT_KOTLIN_CLIENT_CONTRACT_DIR")?.let { output ->
            val directory = Path.of(output)
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("current-projection-responses.json"), buildJsonObject {
                put("backend", if (db.sqlite) "sqlite" else "postgresql"); put("responses", JsonArray(captures))
            }.toString())
        }
    }
}

internal fun currentProjectionDatabaseState(db: Database, workspace: String = "fixture-a") = db.read { c ->
    listOf("accounts", "cash_transactions", "cash_transaction_components", "cash_transaction_issues", "cash_issue_candidates",
        "ledger_snapshots", "cash_projection_states", "cash_projection_datasets", "cash_projections", "cash_projection_members",
        "cash_projection_relations", "cash_entries", "cash_entry_members", "transaction_relations", "cash_relation_candidates",
        "investment_events", "cash_investment_funding_relations").associateWith { table ->
        c.prepareStatement("SELECT * FROM $table WHERE workspace_id=?").use {
            it.setString(1, workspace); it.executeQuery().use { rows -> buildList {
                while (rows.next()) add((1..rows.metaData.columnCount).associate { rows.metaData.getColumnName(it) to rows.getString(it) })
            }.groupingBy { it }.eachCount() }
        }
    }
}
