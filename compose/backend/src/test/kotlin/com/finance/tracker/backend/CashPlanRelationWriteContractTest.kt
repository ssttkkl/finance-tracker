package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashPlanRelationWriteContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PLAN_RELATION_WRITE_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun strings(value: Any?) = value as Map<String, String>
    private fun cases() = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is Instant -> JsonPrimitive("<time>")
        is CashStoredDecimal -> JsonPrimitive(value.text.toBigDecimal().stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() })
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun state(context: CashSqlContext): JsonElement {
        val repo = CurrentCashRelationWriteRepository(context)
        val rows = context.sql.select(TRANSACTION_RELATIONS.ID).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
            .orderBy(TRANSACTION_RELATIONS.ID).fetch().map { repo.get(it.value1()) }
        fun rows(table: org.jooq.Table<*>, fields: String, order: String) = context.sql.selectFrom(table)
            .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace)).orderBy(table.field(order)!!).fetch().map { row -> fields.split(',').associateWith { row.get(it) } }
        return wire(mapOf("relations" to rows,
            "issues" to rows(CASH_TRANSACTION_ISSUES, "transaction_id,issue_key,code,revision,field_path,pending_fields,pending_source_payload,origin", "transaction_id"),
            "entries" to rows(CASH_ENTRIES, "root_transaction_id,revision,note,counterparty,category_id,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit", "root_transaction_id")))
    }
    private fun mutate(context: CashSqlContext, mutation: String, results: List<Map<String, Any?>>) {
        val id = results.first().getValue("relation_id").toString().toLong()
        val relation = TRANSACTION_RELATIONS
        fun update(values: Map<org.jooq.Field<*>, Any?>) { context.sql.update(relation).set(values).set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L))
            .where(relation.WORKSPACE_ID.eq(context.workspace).and(relation.ID.eq(id))).execute() }
        when (mutation) {
            "amount" -> update(mapOf(relation.APPLIED_AMOUNT to CashStoredDecimal("11")))
            "anchor" -> { val row = context.sql.selectFrom(relation).where(relation.ID.eq(id)).fetchSingle(); update(mapOf(relation.ANCHOR_TRANSACTION_ID to row[relation.SECONDARY_TRANSACTION_ID], relation.ANCHOR_COMPONENT_ID to row[relation.SECONDARY_COMPONENT_ID])) }
            "evidence" -> update(mapOf(relation.EVIDENCE to JsonObject(mapOf("changed" to JsonPrimitive(true)))))
            "state" -> update(mapOf(relation.STATE to "ignored"))
            "candidate_revision" -> context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(93002L)).execute()
            "candidate_stale" -> context.sql.update(CASH_RELATION_CANDIDATES).set(CASH_RELATION_CANDIDATES.VALIDITY, "stale").where(CASH_RELATION_CANDIDATES.RELATION_ID.eq(id)).execute()
            "joint_inactive" -> context.sql.update(relation).set(relation.ACTIVE, false).set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L))
                .where(relation.ID.eq(results[1].getValue("relation_id").toString().toLong())).execute()
            "joint_extra" -> CurrentCashRelationWriteRepository(context).add(mapOf("kind" to "payment_mirror", "state" to "ignored", "active" to false,
                "primary_transaction_id" to 93007L, "anchor_transaction_id" to 93007L, "primary_component_id" to 94008L,
                "anchor_component_id" to 94008L, "decision_group_id" to "joint"))
        }
    }

    @Test fun completeGroupWritesAndFailuresMatchPython() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) db.write { connection ->
            val savepoint = connection.setSavepoint()
            if (!db.sqlite) for (name in listOf("transaction_relations", "cash_transaction_issues")) connection.createStatement().use {
                it.execute("SELECT setval('${name}_id_seq', COALESCE((SELECT max(id) FROM $name), 1), EXISTS(SELECT 1 FROM $name))")
            }
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
            val setup = case["setup"]?.jsonObject
            if (setup != null) {
                val updates = setup.filterKeys { it != "id" }.mapKeys { CASH_TRANSACTIONS.field(it.key)!! }.mapValues { (field, raw) ->
                    if (field.name == "deleted_at") context.now() else cashSourceValue(raw)
                }
                context.sql.update(CASH_TRANSACTIONS).set(updates).where(CASH_TRANSACTIONS.ID.eq(setup.getValue("id").jsonPrimitive.long)).execute()
            }
            val writer = CashPlanRelationWriter(context, "writer-user")
            val plan = map(cashSourceValue(case.getValue("plan")))
            val identity = strings(cashSourceValue(case.getValue("identity")))
            val components = strings(cashSourceValue(case.getValue("components")))
            val affected = mutableSetOf<Long>()
            var results = emptyList<Map<String, Any?>>()
            var error: String? = null
            var existing: Map<String, Long>? = null
            try {
                when (case.getValue("mode").jsonPrimitive.content) {
                    "discovery" -> { writer.discoveryIssues(plan, identity, affected); results = writer.discoveryResults(plan, identity) }
                    "write" -> existing = writer.writeGroup(maps(plan["relation_groups"]), identity, components, "direct", emptySet(),
                        case.getValue("unavailable").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    else -> {
                        val fresh = case["fresh"]?.let { mapOf("relation_groups" to cashSourceValue(it)) } ?: plan
                        val manual = case.getValue("manual").jsonArray.map { it.jsonPrimitive.content }.toSet()
                        results = writer.saveGroups(plan, fresh, identity, components, affected, manual)
                        if (case["repeat"] == JsonPrimitive(true)) { affected.clear(); results = writer.saveGroups(plan, plan, identity, components, affected, manual) }
                        if (case.getValue("mode") == JsonPrimitive("existing")) {
                            mutate(context, case.getValue("mutation").jsonPrimitive.content, results)
                            existing = writer.existingGroups(maps(plan["relation_groups"]), identity, components)
                        }
                    }
                }
            } catch (failure: CashRelationFailure) { error = failure.code }
            catch (failure: CashPolicyError) { error = failure.code }
            val actual = wire(mapOf("results" to results, "affected" to affected.sorted(), "error" to error, "existing" to existing, "state" to state(context)))
            assertEquals(case.getValue("expected"), actual, case.getValue("name").jsonPrimitive.content)
            if (case.getValue("name") == JsonPrimitive("joint_skip")) {
                assertEquals(1, actual.jsonObject.getValue("state").jsonObject.getValue("relations").jsonArray.size)
                assertEquals(setOf(93001L, 93002L, 93003L, 93004L), affected)
            }
            if (case.getValue("name") == JsonPrimitive("duplicate_candidates")) assertTrue(affected.isEmpty())
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun lateGroupFailureRollsBackEarlierRelationAndCommonEdit() {
        val db = database()
        val before = detailDatabaseState(db)
        val input = map(cashSourceValue(cases().first { it.getValue("name") == JsonPrimitive("late_group_failure") }))
        val plan = map(input["plan"]).toMutableMap()
        val groups = maps(plan["relation_groups"]).mapIndexed { index, group -> if (index == 0) group + ("common_fields" to mapOf("note" to "late rollback")) else group }
        plan["relation_groups"] = groups
        val error = assertFailsWith<CashRelationFailure> { db.write { connection ->
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
            val writer = CashPlanRelationWriter(context, "writer-user")
            val failure = assertFailsWith<CashRelationFailure> { writer.saveGroups(plan, mapOf("relation_groups" to listOf(groups.first())), emptyMap(), emptyMap(), mutableSetOf(), emptySet()) }
            assertEquals("relation_context_changed", failure.code)
            assertEquals(2, context.sql.fetchCount(TRANSACTION_RELATIONS))
            assertEquals("late rollback", context.entries.current(93001L)!!.values.getValue("note").jsonPrimitive.content)
            throw failure
        } }
        assertEquals("relation_context_changed", error.code)
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun jointDuplicateRequiresEveryOriginalMemberAndPreservesRevision() {
        val db = database()
        val before = detailDatabaseState(db)
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
            val plan = map(cashSourceValue(cases().first { it.getValue("name") == JsonPrimitive("joint") }.getValue("plan")))
            val writer = CashPlanRelationWriter(context, "writer-user")
            val saved = writer.saveGroups(plan, plan, emptyMap(), emptyMap(), mutableSetOf(), emptySet())
            val snapshot = state(context)
            val affected = mutableSetOf<Long>()
            val replay = writer.saveGroups(plan, mapOf("relation_groups" to emptyList<Any?>()), emptyMap(), emptyMap(), affected, emptySet())
            assertTrue(replay.all { it["outcome"] == "duplicate" })
            assertTrue(affected.isEmpty())
            assertEquals(snapshot, state(context))
            assertNull(writer.existingGroups(maps(plan["relation_groups"]).take(1), emptyMap(), emptyMap()))
            assertEquals(snapshot, state(context))
            assertEquals(2, saved.size)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
