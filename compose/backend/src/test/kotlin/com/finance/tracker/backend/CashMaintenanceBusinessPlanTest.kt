package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashMaintenanceBusinessPlanTest {
    @Test fun completeOrdinaryMaintenancePlansMatchOriginalBusinessGraphsWithoutWrites() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MAINTENANCE_BUSINESS_CONTRACT_DIR")))
        val scenarios = Json.parseToJsonElement(root.resolve("scenarios.json").readText()).jsonObject
        assertTrue(scenarios.isNotEmpty())
        for (name in scenarios.keys) {
            val directory = root.resolve(name)
            val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
            val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
            val database = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
            val before = state(database)
            database.read { connection ->
                val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, database.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") })
                val request = MutationPlanRequest.parse(case.getValue("input"))
                val previous = case["previous"]?.takeIf { it != JsonNull }?.jsonObject
                fun build() = MutationPlanBuilder(context).build(request.transactionId.toLong(), request, previous)
                if (case["error"] != null) {
                    val failure = assertFailsWith<RuntimeException>(name) { build() }
                    val code = when (failure) {
                        is MutationPlanFailure -> failure.code
                        is CashPolicyError -> failure.code
                        is CashImportPlanError -> failure.code
                        else -> throw failure
                    }
                    assertEquals(case.getValue("error").jsonPrimitive.content, code, name)
                } else {
                    val result = build()
                    assertEquals(case.getValue("expected"), result, name)
                    MutationPlanView.validateBuild(result)
                }
            }
            assertEquals(before, state(database), name)
        }
    }
    private fun state(database: Database) = database.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }
}
