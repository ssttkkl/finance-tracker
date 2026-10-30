package com.finance.tracker.backend

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportCandidateContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_CANDIDATE_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }

    @Test fun candidateIdsMatchPythonAcrossTimeKeysAccountsAndScopesWithoutWriting() {
        val db = database()
        val cases = Json.parseToJsonElement(root().resolve("candidate-cases.json").readText()).jsonArray
        assertTrue(cases.size >= 14)
        val before = detailDatabaseState(db)
        for (item in cases) {
            val case = item.jsonObject
            @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>
            val sourceRows = case.getValue("source_rows").jsonObject.mapValues { (_, value) ->
                val row = value.jsonObject
                SourceOccurrence(row.getValue("source_type").jsonPrimitive.content,
                    row["account_scope_key"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
                    "a".repeat(64), "fixture-v1", "table", 0, emptyMap(),
                    providerId = row.getValue("provider_id").jsonPrimitive.content)
            }
            val actual = db.read { connection ->
                CashImportCandidateRepository(CashSqlContext(connection, "operation-workspace", db.sqlite))
                    .candidateIds(operations, sourceRows).sorted()
            }
            assertEquals(case.getValue("candidate_ids"), sourceJson(actual), case.getValue("name").jsonPrimitive.content)
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
