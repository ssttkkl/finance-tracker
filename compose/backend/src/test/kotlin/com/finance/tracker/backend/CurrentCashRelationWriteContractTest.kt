package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CurrentCashRelationWriteContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_CURRENT_RELATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is Instant -> JsonPrimitive("<time>")
        is CashStoredDecimal -> JsonPrimitive(value.text.toBigDecimal().stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() })
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    @Suppress("UNCHECKED_CAST") private fun values(raw: JsonElement): Any? {
        val value = cashSourceValue(raw)
        if (value !is Map<*, *>) return value
        return (value as Map<String, Any?>).mapValues { (key, item) ->
            if (key == "decided_at" && item is String && item.endsWith('Z')) Instant.parse(item) else item
        }
    }

    @Test fun currentRoleWritesMatchPythonAndRollback() {
        val db = database()
        val before = detailDatabaseState(db)
        for (raw in Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray) {
            val case = raw.jsonObject
            db.write { connection ->
                val savepoint = connection.setSavepoint()
                if (!db.sqlite) connection.createStatement().use { it.execute("SELECT setval('transaction_relations_id_seq', (SELECT max(id) FROM transaction_relations), true)") }
                val context = CashSqlContext(connection, "relation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
                val setup = case["setup"]?.takeUnless { it == JsonNull }?.jsonObject
                if (setup != null) {
                    val updates = setup.filterKeys { it != "id" }.mapKeys { CASH_TRANSACTIONS.field(it.key)!! }.mapValues { (field, rawValue) ->
                        if (field.name == "deleted_at") Instant.parse(rawValue.jsonPrimitive.content) else cashSourceValue(rawValue)
                    }
                    context.sql.update(CASH_TRANSACTIONS).set(updates).where(CASH_TRANSACTIONS.ID.eq(setup.getValue("id").jsonPrimitive.long)).execute()
                }
                val repo = CurrentCashRelationWriteRepository(context)
                val results = mutableListOf<Map<String, Any?>>()
                var identifier: Long? = null
                var error: String? = null
                try {
                    for (step in case.getValue("steps").jsonArray.map { it.jsonObject }) {
                        val row = when (step.getValue("method").jsonPrimitive.content) {
                            "add" -> repo.add(values(step.getValue("values"))).also { identifier = it["id"] as Long }
                            "replace" -> repo.replace(identifier, values(step.getValue("values")), cashSourceValue(step.getValue("revision")))
                            else -> repo.setCandidates(identifier, values(step.getValue("values")), cashSourceValue(step.getValue("revision")))
                        }
                        results += row
                    }
                } catch (failure: CashRelationFailure) { error = failure.code }
                val rows = context.sql.select(TRANSACTION_RELATIONS.ID).from(TRANSACTION_RELATIONS)
                    .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace)).orderBy(TRANSACTION_RELATIONS.ID).fetch().map { repo.get(it.value1()) }
                assertEquals(case.getValue("expected"), wire(mapOf("results" to results, "error" to error, "rows" to rows)), case.getValue("name").jsonPrimitive.content)
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun evidenceBudgetUsesOriginalJsonMembersBeforeKeyCollisions() {
        val db = database()
        val before = detailDatabaseState(db)
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
            val repo = CurrentCashRelationWriteRepository(context)
            val fields = mapOf("kind" to "refund_offset", "primary_transaction_id" to 93001L, "anchor_transaction_id" to 93001L, "state" to "ignored", "active" to false)
            val exact = repo.add(fields + ("evidence" to mapOf("text" to "x".repeat(65524))))
            assertEquals(65524, (exact.getValue("evidence") as JsonObject).getValue("text").jsonPrimitive.content.length)
            val floating = repo.add(fields + ("evidence" to mapOf("text" to "x".repeat(65512), "n" to 1e20)))
            assertEquals(65512, (floating.getValue("evidence") as JsonObject).getValue("text").jsonPrimitive.content.length)
            for (proof in listOf(mapOf("text" to "x".repeat(65525)), mapOf("text" to "x".repeat(65513), "n" to 1e20),
                linkedMapOf<Any?, Any?>(1 to "x".repeat(33000), "1" to "y".repeat(33000)), mapOf("number" to Double.NaN), mapOf("number" to "1".repeat(4301).toBigInteger())))
                assertEquals("relation_data_invalid", assertFailsWith<CashRelationFailure> { repo.add(fields + ("evidence" to proof)) }.code)
            val cycle = arrayListOf<Any?>()
            cycle.add(cycle)
            assertEquals("relation_data_invalid", assertFailsWith<CashRelationFailure> { repo.add(fields + ("evidence" to mapOf("cycle" to cycle))) }.code)
            val normalized = repo.add(fields + ("evidence" to linkedMapOf<Any?, Any?>(1 to "old", "1" to "new")))
            assertEquals(JsonObject(mapOf("1" to JsonPrimitive("new"))), normalized["evidence"])
            assertEquals("relation_not_found", assertFailsWith<CashRelationFailure> { CurrentCashRelationWriteRepository(CashSqlContext(connection, "other", db.sqlite)).get(exact["id"]) }.code)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun invalidCandidateBatchPreservesPriorRowsAndRevision() {
        val db = database()
        val before = detailDatabaseState(db)
        assertFailsWith<CashRelationFailure> {
            db.write { connection ->
                val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
                val repo = CurrentCashRelationWriteRepository(context)
                val row = repo.add(mapOf("kind" to "refund_offset", "primary_transaction_id" to 93001L,
                    "anchor_transaction_id" to 93001L, "primary_component_id" to 94001L, "anchor_component_id" to 94001L))
                val candidate = mapOf("candidate_transaction_id" to 93002L, "candidate_component_id" to 94002L, "expected_row_revision" to 1L)
                val saved = repo.setCandidates(row["id"], listOf(candidate), 1L)
                val failed = assertFailsWith<CashRelationFailure> { repo.setCandidates(row["id"], listOf(candidate,
                    mapOf("candidate_transaction_id" to 93003L, "candidate_component_id" to 94003L, "expected_row_revision" to 1L, "evidence" to listOf(1))), 2L) }
                assertEquals("relation_data_invalid", failed.code)
                assertEquals(saved, repo.get(row["id"]))
                assertEquals(2L, saved["row_revision"])
                assertEquals("relation_revision_changed", assertFailsWith<CashRelationFailure> { repo.replace(row["id"], emptyMap<String, Any?>(), 1L) }.code)
                throw failed
            }
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
