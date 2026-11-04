package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportBusinessPlanContractTest {

    private fun normalizeAmounts(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, item) ->
            if ((key == "amount" || key.endsWith("_amount") || key.startsWith("amount")) && item is JsonPrimitive && item.isString)
                JsonPrimitive(item.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: item.content)
            else normalizeAmounts(item) })
        is JsonArray -> JsonArray(value.map(::normalizeAmounts))
        else -> value
    }

    @Test fun currentSeedsAndTransformsParticipateInTheCompleteReadOnlyGraph() {
        val db = database(); val before = databaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
            val request = MutationPlanRequest.parse(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "7507"); put("expected_revision", 1); put("patch", buildJsonObject {})
            })
            @Suppress("UNCHECKED_CAST") val operation = cashSourceValue(MutationPlanBuilder(context).build(7507, request)
                .getValue("operations").jsonArray.single()) as Map<String, Any?>
            assertEquals("duplicate", operation["planned_action"])
            val rows = listOf(mapOf("transaction_id" to "7507", "operation_ref" to "op_1", "planned_action" to "duplicate"))
            val builder = CashImportBusinessPlanBuilder(context)
            assertEquals(emptyList<Any>(), builder.buildOperations(listOf(operation), rows, emptyMap(), null)["items"])
            var transformed = 0; var grouped = 0
            val result = builder.buildOperations(listOf(operation), rows, emptyMap(), null, includeUnchanged = true,
                additionalSeedIds = setOf(7503), snapshotTransform = { snapshot ->
                    transformed++; assertTrue("7503" in snapshot.values)
                    snapshot.facts["7507"] = snapshot.facts.getValue("7507").withAttention(true)
                }, currentRelationBuilder = { snapshot, accounts ->
                    grouped++; assertTrue(snapshot.facts.getValue("7507").needsAttention)
                    assertTrue("9201" in accounts); emptyList()
                })
            assertEquals(1, transformed); assertEquals(1, grouped)
            val items = result.getValue("items") as List<*>
            assertTrue(items.any { (it as Map<*, *>)["confirmation_status"] == "pending" })
            val dependencies = result.getValue("dependencies") as Map<*, *>
            assertTrue(7503L in dependencies["seed_ids"] as List<*>)
            assertEquals("relation_group_changed", assertFailsWith<CashRelationFailure> {
                builder.buildOperations(listOf(operation), rows, emptyMap(), null, decisionGroupIds = listOf("missing-current-group"))
            }.code)
        }
        assertEquals(before, databaseState(db))
    }
    @Test fun currentOperationsNeedNoImportFileOrRowReference() {
        val db = database(); val before = databaseState(db)
        db.read { connection ->
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
            val request = MutationPlanRequest.parse(buildJsonObject {
                put("intent", "edit_record"); put("transaction_id", "7501"); put("expected_revision", 1)
                put("patch", buildJsonObject { put("note", "维护后的备注") })
            })
            val operation = MutationPlanBuilder(context).build(7501, request).getValue("operations").jsonArray.single()
            @Suppress("UNCHECKED_CAST") val input = cashSourceValue(operation) as Map<String, Any?>
            val result = CashImportBusinessPlanBuilder(context).buildOperations(listOf(input), listOf(mapOf(
                "transaction_id" to "7501", "operation_ref" to "op_1", "planned_action" to input.getValue("planned_action"))),
                emptyMap(), null, includeUnchanged = true)
            val items = result.getValue("items") as List<*>
            assertTrue(items.isNotEmpty())
            val details = result.getValue("item_details") as Map<*, *>
            val members = details.values.flatMap { (it as Map<*, *>)["members"] as List<*> }
            assertTrue(members.any { (it as Map<*, *>)["transaction_id"] == "7501" })
            assertTrue(members.none { (it as Map<*, *>).containsKey("row_ref") })
            assertEquals(emptyList<Any>(), result["file_results"])
        }
        assertEquals(before, databaseState(db))
    }
    @Test fun preparedOperationsUseTheSameCompletePythonBusinessContract() {
        val db = database(); val before = databaseState(db)
        for (raw in Json.parseToJsonElement(root().resolve("business-plan-cases.json").readText()).jsonArray) {
            val case = raw.jsonObject
            @Suppress("UNCHECKED_CAST") val input = cashSourceValue(case.getValue("input")) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val previous = cashSourceValue(case.getValue("previous")) as? Map<String, Any?>
            db.read { connection ->
                val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
                val source = CashImportOperationPlanner(context).prepare(files(),
                    (input["account_selections"] as? List<Map<String, Any?>>).orEmpty(),
                    (input["row_choices"] as? List<Map<String, Any?>>).orEmpty(), emptyMap(), wireValues = false)
                val operations = CashImportPreviewPreparation(context).prepare(source.operations, source.source.rows.rows).operations
                val savedOperations = sourceJson(operations); val savedRows = sourceJson(source.rows)
                val builder = CashImportBusinessPlanBuilder(context)
                if (case["error"] != null) assertEquals(case.getValue("error").jsonPrimitive.content,
                    assertFailsWith<CashImportPlanError> { builder.buildOperations(operations, source.rows, input, previous,
                        files = files(), sourceRows = source.source.rows.rows, sourceCandidateSets = source.candidateSets) }.code)
                else assertEquals(normalizeAmounts(case.getValue("expected")), normalizeAmounts(sourceJson(builder.buildOperations(operations, source.rows, input, previous,
                    files = files(), sourceRows = source.source.rows.rows, sourceCandidateSets = source.candidateSets))), case["name"].toString())
                assertEquals(savedOperations, sourceJson(operations)); assertEquals(savedRows, sourceJson(source.rows))
                assertEquals(case.getValue("input"), sourceJson(input)); assertEquals(case.getValue("previous"), sourceJson(previous))
            }
        }
        assertEquals(before, databaseState(db))
    }
    private fun databaseState(db: Database) = db.read { connection ->
        val tables = DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }
        assertEquals(32, tables.size)
        tables.associate { table -> table.name to connection.createStatement().use { statement ->
            statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                val columns = rows.metaData.columnCount
                buildList { while (rows.next()) add(JsonArray((1..columns).map { index -> rows.getString(index)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
            }
        } }
    }
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun files(): List<ParsedImportFile> {
        val input = Json.parseToJsonElement(root().resolve("input-session.json").readText()).jsonObject
        return ParsedImportCodec().decode(input.getValue("session_id").jsonPrimitive.content,
            input.getValue("content").toString().encodeToByteArray()).files
    }

    @Test fun completeBusinessPlansMatchPythonWithoutFinancialWrites() {
        val db = database()
        val before = databaseState(db)
        val files = files()
        val filesBefore = ParsedImportCodec().encode(ParsedImportSession("A".repeat(43), "operation-workspace", "owner",
            Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:30:00Z"), java.math.BigInteger.ONE, files))
        for (raw in Json.parseToJsonElement(root().resolve("business-plan-cases.json").readText()).jsonArray) {
            val case = raw.jsonObject
            @Suppress("UNCHECKED_CAST") val input = cashSourceValue(case.getValue("input")) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST") val previous = cashSourceValue(case.getValue("previous")) as? Map<String, Any?>
            db.read { connection ->
                val builder = CashImportBusinessPlanBuilder(CashSqlContext(connection, "operation-workspace", db.sqlite,
                    now = { Instant.parse("2026-10-01T09:00:00Z") }))
                if (case["error"] != null) assertEquals(case.getValue("error").jsonPrimitive.content,
                    assertFailsWith<CashImportPlanError> { builder.build(files, input, previous) }.code)
                else {
                    val result = builder.build(files, input, previous)
                    assertEquals(normalizeAmounts(case.getValue("expected")), normalizeAmounts(sourceJson(result)), case.getValue("name").jsonPrimitive.content)
                    CashImportPlanViews.validateReadable(result)
                }
            }
            assertEquals(case.getValue("input"), sourceJson(input))
            assertEquals(case.getValue("previous"), sourceJson(previous))
        }
        assertContentEquals(filesBefore, ParsedImportCodec().encode(ParsedImportSession("A".repeat(43), "operation-workspace", "owner",
            Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:30:00Z"), java.math.BigInteger.ONE, files)))
        assertEquals(before, databaseState(db))
    }
}
