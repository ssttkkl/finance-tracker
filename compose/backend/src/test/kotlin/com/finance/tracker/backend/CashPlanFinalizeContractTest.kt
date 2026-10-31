package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashPlanFinalizeContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_PLAN_FINALIZE_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun strings(value: Any?) = value as Map<String, String>
    private fun cases() = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is CashStoredDecimal -> JsonPrimitive(value.text.toBigDecimal().stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() })
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun state(context: CashSqlContext): JsonElement {
        fun rows(table: org.jooq.Table<*>, fields: String, order: String) = context.sql.selectFrom(table)
            .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace)).orderBy(table.field(order)!!).fetch().map { row -> fields.split(',').associateWith { row.get(it) } }
        val snapshot = context.sql.selectFrom(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(context.workspace)).fetchSingle()
        return wire(mapOf("snapshot" to mapOf("payload" to snapshot[LEDGER_SNAPSHOTS.PAYLOAD], "version" to snapshot[LEDGER_SNAPSHOTS.VERSION]),
            "entries" to rows(CASH_ENTRIES, "root_transaction_id,revision,note,counterparty,category_id,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit", "root_transaction_id"),
            "projections" to rows(CASH_PROJECTIONS, "root_cash_transaction_id,note,counterparty,category_id,net_amount,currency,member_count,attention_count,built_projection_version", "root_cash_transaction_id"),
            "projection_version" to context.sql.select(CASH_PROJECTION_STATES.PROJECTION_VERSION).from(CASH_PROJECTION_STATES).where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(context.workspace)).fetchSingle().value1()))
    }
    private fun changes(raw: JsonArray) = raw.map { pair -> pair.jsonArray.let { values ->
        val old = values[0].takeUnless { it == JsonNull }?.let { map(cashSourceValue(it)) }
        val new = values[1].takeUnless { it == JsonNull }?.let { map(cashSourceValue(it)) }
        old to new
    } }

    @Test fun completeFinalizeEffectsMatchPython() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
            CashProjectionService.initializeInSession(context)
            if (case["attention"] == JsonPrimitive(true)) {
                CashSourceWriteRepository(context).saveIssue(93001L, "attention", "source_update_pending")
                CurrentCashRelationWriteRepository(context).add(mapOf("kind" to "refund_offset", "primary_transaction_id" to 93006L, "primary_component_id" to 94006L,
                    "anchor_transaction_id" to 93006L, "anchor_component_id" to 94006L, "secondary_transaction_id" to 93002L, "secondary_component_id" to 94002L))
            }
            val finalizer = CashPlanFinalizer(context)
            var saved: Int? = null
            var error: String? = null
            try {
                finalizer.balances(changes(case.getValue("changes").jsonArray))
                saved = finalizer.saveEntryChoices(maps(cashSourceValue(case.getValue("edits"))), strings(cashSourceValue(case.getValue("identity"))), maps(cashSourceValue(case.getValue("relation_results"))))
            } catch (failure: CashImportPlanError) { error = failure.code }
            catch (failure: CashPolicyError) { error = failure.code }
            assertEquals(case.getValue("expected"), wire(mapOf("saved" to saved, "error" to error,
                "attention" to finalizer.attentionCount(setOf(93001L, 93002L, 93005L, 93006L)), "state" to state(context))), case.getValue("name").jsonPrimitive.content)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun lateEntryFailureRollsBackBalanceEntryAndProjection() {
        val db = database()
        val before = detailDatabaseState(db)
        val case = cases().first { it.getValue("name") == JsonPrimitive("late_edit_failure") }
        val failure = assertFailsWith<CashImportPlanError> { db.write { connection ->
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
            CashProjectionService.initializeInSession(context)
            val finalizer = CashPlanFinalizer(context)
            finalizer.balances(changes(case.getValue("changes").jsonArray))
            val error = assertFailsWith<CashImportPlanError> { finalizer.saveEntryChoices(maps(cashSourceValue(case.getValue("edits"))), emptyMap(), emptyList()) }
            assertEquals("plan_changed", error.code)
            assertEquals("whole entry note", context.entries.current(93002L)!!.values.getValue("note").jsonPrimitive.content)
            assertEquals(2L, context.sql.select(LEDGER_SNAPSHOTS.VERSION).from(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(context.workspace)).fetchSingle().value1())
            throw error
        } }
        assertEquals("plan_changed", failure.code)
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun projectionValidationFailureRollsBackEarlierBalanceAndPublicEdit() {
        val db = database()
        val before = detailDatabaseState(db)
        val case = cases().first { it.getValue("name") == JsonPrimitive("new_expense") }
        assertFailsWith<CashProjectionFailure> { db.write { connection ->
            val context = CashSqlContext(connection, "relation-workspace", db.sqlite)
            CashProjectionService.initializeInSession(context)
            val finalizer = CashPlanFinalizer(context)
            finalizer.balances(changes(case.getValue("changes").jsonArray))
            context.sql.update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.AMOUNT, CashStoredDecimal("-99"))
                .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(context.workspace).and(CASH_TRANSACTION_COMPONENTS.ID.eq(94001L))).execute()
            val edit = mapOf("target" to mapOf("parent_ids" to listOf("93001", "93005"), "relation_dependencies" to emptyList<Any?>()), "patch" to mapOf("note" to "before failed publication"))
            val failure = assertFailsWith<CashProjectionFailure> { finalizer.saveEntryChoices(listOf(edit), emptyMap(), emptyList()) }
            assertEquals("before failed publication", context.entries.current(93001L)!!.values.getValue("note").jsonPrimitive.content)
            assertEquals(2L, context.sql.select(LEDGER_SNAPSHOTS.VERSION).from(LEDGER_SNAPSHOTS).where(LEDGER_SNAPSHOTS.WORKSPACE_ID.eq(context.workspace)).fetchSingle().value1())
            throw failure
        } }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun rowResultsKeepPerRowOutcomeAndFrozenInputs() {
        val raw = Json.parseToJsonElement(root().resolve("row-results.json").readText()).jsonObject
        val plan = map(cashSourceValue(raw.getValue("plan")))
        val actual = map(cashSourceValue(raw.getValue("actual"))).mapValues { map(it.value) }
        val original = CashRecordPolicy.copyValue(actual)
        assertEquals(raw.getValue("expected"), wire(CashPlanFinalizer.rowResults(plan, actual)))
        assertEquals(original, actual)
        assertEquals("created_posted", actual.getValue("op")["outcome"])
    }
}
