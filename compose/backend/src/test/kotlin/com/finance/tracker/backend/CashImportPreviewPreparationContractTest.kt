package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportPreviewPreparationContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun text(row: JsonObject, key: String) = row[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content

    @Test fun postOperationPreparationMatchesPythonAndDoesNotWrite() {
        val db = database()
        val before = detailDatabaseState(db)
        val cases = Json.parseToJsonElement(root().resolve("preparation-cases.json").readText()).jsonArray
        assertEquals(4, cases.size)
        for (item in cases) {
            val case = item.jsonObject
            @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("raw_operations")) as List<Map<String, Any?>>
            val sourceRows = case.getValue("source_rows").jsonObject.mapValues { (_, value) ->
                val row = value.jsonObject
                SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                    text(row, "source_account_key"), row.getValue("file_digest").jsonPrimitive.content,
                    row.getValue("parser_version").jsonPrimitive.content, row.getValue("table_key").jsonPrimitive.content,
                    row.getValue("position").jsonPrimitive.int, emptyMap(), providerId = text(row, "provider_id"))
            }
            @Suppress("UNCHECKED_CAST") val choices = cashSourceValue(case.getValue("entry_choices")) as List<Map<String, Any?>>
            @Suppress("UNCHECKED_CAST") val previous = case["previous_plan"]?.takeUnless { it == JsonNull }
                ?.let { cashSourceValue(it) } as? Map<String, Any?>
            val extra = case.getValue("additional_seed_ids").jsonArray.map { it.jsonPrimitive.long }.toSet()
            val actual = db.read { connection ->
                CashImportPreviewPreparation(CashSqlContext(connection, "operation-workspace", db.sqlite))
                    .prepare(operations, sourceRows, choices, previous, extra)
            }
            val expectedOperations = case.getValue("operations").jsonArray
            val rawOperations = case.getValue("raw_operations").jsonArray
            assertEquals(expectedOperations.size, actual.operations.size)
            for (index in expectedOperations.indices) {
                val expected = expectedOperations[index].jsonObject
                val raw = rawOperations[index].jsonObject
                val observed = sourceJson(actual.operations[index]).jsonObject
                assertEquals(expected["source_scope_key"], observed["source_scope_key"])
                assertEquals(expected["matching_record_id"], observed["matching_record_id"])
                assertEquals(JsonObject(raw + mapOf("source_scope_key" to expected.getValue("source_scope_key"),
                    "matching_record_id" to expected.getValue("matching_record_id"))), observed)
            }
            assertEquals(case.getValue("raw_operations"), sourceJson(operations))
            assertEquals(case.getValue("accounts"), sourceJson(actual.accounts))
            assertEquals(case.getValue("candidate_ids"), sourceJson(actual.candidateIds.sorted()))
            assertEquals(case.getValue("retained_parent_ids"), sourceJson(actual.retainedParentIds.sorted()))
            assertEquals(case.getValue("context_seed_ids"), sourceJson(actual.context.seedIds.sorted()))
            assertEquals(case.getValue("context_transaction_ids"), sourceJson(actual.context.transactionIds.sorted()))
            assertEquals(case.getValue("context_token").jsonPrimitive.content, actual.context.token())
        }
        val error = assertFailsWith<CashImportPlanError> {
            db.read { connection ->
                CashImportPreviewPreparation(CashSqlContext(connection, "operation-workspace", db.sqlite))
                    .prepare(emptyList(), emptyMap(), listOf(mapOf("edit_ref" to "missing")),
                        mapOf("entry_targets" to emptyMap<String, Any?>()))
            }
        }
        val invalid = Json.parseToJsonElement(root().resolve("invalid-target.json").readText()).jsonObject
        assertEquals(invalid.getValue("code").jsonPrimitive.content, error.code)
        assertEquals(before, detailDatabaseState(db))
    }
}
