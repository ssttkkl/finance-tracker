package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CurrentCashRelationPlannerTest {
    @Test fun zeroRefundWithUnknownTargetComponentAmountKeepsTheEndpointFailure() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR")))
        val name = "manual_search_and_manual_preview_support_zero_refund_in_both_directions_p1_s1"
        val directory = root.resolve(name)
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        val before = state(db)
        val rollback = RuntimeException("synthetic-zero-refund-rollback")
        assertSame(rollback, assertFailsWith<RuntimeException> {
            db.write { connection ->
                connection.prepareStatement("UPDATE cash_transaction_components SET amount = NULL WHERE workspace_id = ? AND id = ?").use {
                    it.setString(1, config.getValue("workspace").jsonPrimitive.content); it.setLong(2, 6101L)
                    assertEquals(1, it.executeUpdate())
                }
                val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, db.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") })
                val failure = assertFailsWith<CashPolicyError> {
                    CurrentCashMaintenancePlanBuilder(context).build(MutationPlanRequest.fromStored(case.getValue("input").jsonObject), null)
                }
                assertEquals("relation_endpoint_incomplete", failure.code)
                throw rollback
            }
        })
        assertEquals(before, state(db))
    }

    @Test fun zeroRefundManualPreviewMatchesOriginalFromEitherEndpoint() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR")))
        val scenarios = Json.parseToJsonElement(root.resolve("scenarios.json").readText()).jsonObject
        val names = scenarios.keys.filter { it.startsWith("manual_search_and_manual_preview_support_zero_refund_in_both_directions_") }
        assertEquals(2, names.size)
        for (name in names) {
            val directory = root.resolve(name)
            val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
            val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
            val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
                System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
            val before = state(db)
            db.read { connection ->
                val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, db.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") })
                val actual = CurrentCashMaintenancePlanBuilder(context).build(
                    MutationPlanRequest.fromStored(case.getValue("input").jsonObject), null)
                assertEquals(case.getValue("expected"), actual, name)
                val edges = actual.getValue("relation_groups").jsonArray.flatMap { it.jsonObject.getValue("edges").jsonArray }
                assertEquals(1, edges.size, name)
                assertEquals("1101", edges.single().jsonObject.getValue("primary_transaction_id").jsonPrimitive.content, name)
                assertEquals("1102", edges.single().jsonObject.getValue("secondary_transaction_id").jsonPrimitive.content, name)
            }
            assertEquals(before, state(db), name)
        }
    }

    @Test fun completeMaintenancePreviewMatchesOriginalCurrentRelationPlans() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR")))
        for (name in Json.parseToJsonElement(root.resolve("scenarios.json").readText()).jsonObject.keys) {
            val directory = root.resolve(name); val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
            val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
            val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
            val before = state(db)
            db.read { connection ->
                val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, db.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") })
                val previous = case["previous"]?.takeIf { it != JsonNull }?.jsonObject
                if (case["error"] != null) {
                    val failure = assertFailsWith<RuntimeException>(name) { CurrentCashMaintenancePlanBuilder(context).build(MutationPlanRequest.fromStored(case.getValue("input").jsonObject), previous) }
                    val code = when (failure) {
                        is CashPolicyError -> failure.code
                        is MutationPlanFailure -> failure.code
                        is CashImportPlanError -> failure.code
                        is CashRelationFailure -> failure.code
                        else -> throw failure
                    }
                    assertEquals(case.getValue("error").jsonPrimitive.content, code, name)
                } else {
                    val result = CurrentCashMaintenancePlanBuilder(context).build(MutationPlanRequest.fromStored(case.getValue("input").jsonObject), previous)
                    assertEquals(case.getValue("expected"), result, name)
                    MutationPlanView.validateBuild(result)
                }
            }
            assertEquals(before, state(db), name)
        }
    }
    @Test fun completeCurrentGroupsAndCandidateProofsMatchOriginalWithoutWrites() {
        val root = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_PLAN_CONTRACT_DIR")))
        val scenarios = Json.parseToJsonElement(root.resolve("scenarios.json").readText()).jsonObject
        var compared = 0
        var expectedCases = 0
        for (name in scenarios.keys) {
            val directory = root.resolve(name)
            val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
            if (case["error"] != null) continue
            expectedCases++
            val raw = case.getValue("prepared").jsonObject
            val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
            val db = Database(config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
            val before = state(db)
            @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(raw.getValue("operations")) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val rows = cashSourceValue(raw.getValue("rows")) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val input = cashSourceValue(case.getValue("input")) as Map<String, Any?>
            db.read { connection ->
                val context = CashSqlContext(connection, config.getValue("workspace").jsonPrimitive.content, db.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") })
                val planner = CurrentCashRelationPlanner(context, case.getValue("input").jsonObject.getValue("transaction_id").jsonPrimitive.long)
                val request = MutationPlanRequest.fromStored(case.getValue("input").jsonObject)
                val seeds = planner.seedIds + request.relationAdditions.map { it.getValue("target_transaction_id").jsonPrimitive.long }
                assertEquals(raw.getValue("additional_seed_ids").jsonArray.map { it.jsonPrimitive.long }.toSet(), seeds, name)
                assertEquals(raw.getValue("decision_group_ids").jsonArray.map { it.jsonPrimitive.content }.toSet(), planner.decisionGroupIds.toSet(), name)
                val previous = case["previous"]?.takeIf { it != JsonNull }
                @Suppress("UNCHECKED_CAST") val prior = previous?.let(::cashSourceValue) as? Map<String, Any?>
                val result = CashImportBusinessPlanBuilder(context).buildOperations(operations, rows, input, prior,
                    includeUnchanged = true, additionalSeedIds = seeds, decisionGroupIds = planner.decisionGroupIds,
                    currentRelationBuilder = { snapshot, accounts -> planner.build(snapshot, accounts) + planner.manualGroups(snapshot, request.transactionId,
                        request.relationAdditions.map { cashSourceValue(it) as Map<String, Any?> }) })
                val expected = case.getValue("expected").jsonObject
                assertEquals(expected.getValue("relation_groups"), sourceJson(result.getValue("relation_groups")), name)
                assertEquals(expected.getValue("dependencies"), sourceJson(result.getValue("dependencies")), name)
                assertEquals(expected.getValue("relation_failures"), sourceJson(result.getValue("relation_failures")), name)
            }
            assertEquals(before, state(db), name)
            compared++
        }
        assertTrue(expectedCases > 0)
        assertEquals(expectedCases, compared)
    }
    private fun state(db: Database) = db.read { connection -> DefaultSchema.DEFAULT_SCHEMA.tables.associate { table ->
        table.name to connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
            val count = rows.metaData.columnCount
            buildList { while (rows.next()) add(JsonArray((1..count).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
        } }
    } }
}
