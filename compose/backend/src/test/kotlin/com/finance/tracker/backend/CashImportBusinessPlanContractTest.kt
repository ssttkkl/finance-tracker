package com.finance.tracker.backend

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportBusinessPlanContractTest {
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
        val before = detailDatabaseState(db)
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
                    assertEquals(case.getValue("expected"), sourceJson(result), case.getValue("name").jsonPrimitive.content)
                    CashImportPlanViews.validateReadable(result)
                }
            }
            assertEquals(case.getValue("input"), sourceJson(input))
            assertEquals(case.getValue("previous"), sourceJson(previous))
        }
        assertContentEquals(filesBefore, ParsedImportCodec().encode(ParsedImportSession("A".repeat(43), "operation-workspace", "owner",
            Instant.parse("2026-10-01T09:00:00Z"), Instant.parse("2026-10-01T09:30:00Z"), java.math.BigInteger.ONE, files)))
        assertEquals(before, detailDatabaseState(db))
    }
}
