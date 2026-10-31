package com.finance.tracker.backend

import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

class CashImportPreviewSnapshotContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PREPARATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun text(row: JsonObject, key: String) = row[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
    private fun instant(value: Instant): String {
        val time = value.atOffset(ZoneOffset.UTC)
        val base = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(time)
        return base + (if (time.nano == 0) "" else "." + (time.nano / 1000).toString().padStart(6, '0')) + "+00:00"
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Instant -> JsonPrimitive(instant(value))
        is CashAmount -> JsonPrimitive(fixedCashDecimal(CashStoredDecimal(value.toString())))
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is CashDecimalLiteral -> JsonPrimitive(value.text)
        is BigDecimal -> JsonPrimitive(value.toPlainString())
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun summary(snapshot: CashImportPreviewSnapshot): JsonObject = wire(linkedMapOf(
        "values" to snapshot.values,
        "facts" to snapshot.facts.mapValues { (_, f) -> mapOf(
            "id" to f.id, "account_id" to f.accountId, "occurred_at" to f.occurredAt, "amount" to f.amount,
            "currency" to f.currency, "counterparty" to f.counterparty, "category_id" to f.categoryId, "note" to f.note,
            "source_type" to f.sourceType, "record_id" to f.recordId, "funding_relation_id" to f.fundingRelationId,
            "cash_granularity" to f.cashGranularity, "state" to f.state, "created_at" to f.createdAt,
            "needs_attention" to f.needsAttention,
        ) },
        "components" to snapshot.components.mapValues { (_, p) -> mapOf(
            "component_id" to p.componentId, "transaction_id" to p.transactionId, "amount" to p.amount,
            "currency" to p.currency, "account_id" to p.accountId, "record_type" to p.recordType,
            "record_subtype" to p.recordSubtype, "state" to p.state, "active" to p.active,
        ) },
        "edges" to snapshot.edges.mapKeys { it.key.toString() }.mapValues { (_, e) -> mapOf(
            "id" to e.id, "kind" to e.kind, "primary_transaction_id" to e.primaryTransactionId,
            "secondary_transaction_id" to e.secondaryTransactionId, "primary_component_id" to e.primaryComponentId,
            "secondary_component_id" to e.secondaryComponentId, "primary_role" to e.primaryRole,
            "secondary_role" to e.secondaryRole, "subtype" to e.subtype, "applied_amount" to e.appliedAmount,
            "state" to e.state, "active" to e.active,
        ) },
        "funding" to snapshot.funding.mapKeys { it.key.toString() }.mapValues { (_, f) ->
            listOf(f.id, f.componentId, f.investmentEventId, f.direction)
        },
        "parent_ops" to snapshot.parentOps.mapValues { it.value["operation_ref"] },
        "entry_public" to snapshot.entryPublic,
        "removed_relation_ids" to snapshot.removedRelations.sorted(),
        "removed_funding_ids" to snapshot.removedFunding.sorted(),
        "operations" to snapshot.operations,
        "rows" to snapshot.rows,
        "projections" to snapshot.build().projections.map { item -> mapOf(
            "projection_id" to item.projectionId, "net_amount" to item.netAmount,
            "economic_type" to item.economicType?.value, "member_ids" to item.memberIds,
            "relation_ids" to item.relations.map { it.id }, "compositions" to item.compositions,
            "funding_relation_id" to item.fundingRelationId, "state" to item.state,
            "attention_count" to item.attentionCount,
        ) },
    )).jsonObject

    private fun cases() = Json.parseToJsonElement(root().resolve("snapshot-cases.json").readText()).jsonArray.map { it.jsonObject }

    private fun snapshot(db: Database, case: JsonObject): CashImportPreviewSnapshot {
        @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>
        @Suppress("UNCHECKED_CAST") val rows = cashSourceValue(case.getValue("rows")) as List<Map<String, Any?>>
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
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            val prepared = CashImportPreviewPreparation(context).prepare(operations, sourceRows, choices, previous, extra)
            CashImportPreviewSnapshotBuilder(context, now = { Instant.parse("2026-10-01T09:00:00Z") }).build(prepared, rows)
        }
        assertEquals(case.getValue("operations"), wire(operations))
        return actual
    }

    @Test fun snapshotMatchesPythonOnBothBackendsWithoutWrites() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in cases()) {
            assertEquals(case.getValue("snapshot"), summary(snapshot(db, case)), case.getValue("name").jsonPrimitive.content)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun typeUpdatesRemoveOnlyInvalidAssociationsAndRestoreTheWholeRejectedOperation() {
        val db = database()
        val before = detailDatabaseState(db)
        val cases = cases().associateBy { it.getValue("name").jsonPrimitive.content }
        val removed = snapshot(db, cases.getValue("type_change_remove_mirror"))
        assertEquals(1, removed.removedRelations.size)
        assertFalse(removed.edges.getValue(removed.removedRelations.single()).active as Boolean)
        assertTrue(removed.edges.values.single { it.kind == "refund_offset" }.active as Boolean)
        assertEquals("investment_out", removed.values.getValue("7501")["record_type"])

        val restored = snapshot(db, cases.getValue("type_change_restore_graph_error"))
        assertEquals("consumption", restored.values.getValue("7501")["record_type"])
        assertTrue(restored.removedRelations.isEmpty())
        assertTrue(restored.edges.values.all { it.active == true })
        assertFalse("7501" in restored.parentOps)
        assertTrue(restored.operations.none { it["operation_ref"] == "op_1" })
        val rejected = restored.rows.single { it["row_ref"] == "f_a" + "0".repeat(31) + ":r1" }
        assertEquals("skip", rejected["planned_action"])
        assertEquals("relation_endpoint_incompatible", rejected["code"])
        assertNull(rejected["operation_ref"])

        val funding = snapshot(db, cases.getValue("type_change_remove_funding"))
        assertEquals(1, funding.removedFunding.size)
        assertTrue(funding.funding.isEmpty())
        val expense = funding.build().projections.single { it.projectionId == "cash:7505" }
        assertEquals(0, BigDecimal("-7.00").compareTo(expense.netAmount!!.arithmeticValue()))
        assertEquals(CashEconomicType.EXPENSE, expense.economicType)
        assertNull(expense.fundingRelationId)

        val aggregate = snapshot(db, cases.getValue("type_change_aggregate"))
        assertNull(aggregate.facts.getValue("7506").accountId)
        assertEquals(2, aggregate.components.values.count { it.transactionId == "7506" })
        assertEquals(0, BigDecimal("-12.30").compareTo(aggregate.build().projections.single {
            it.projectionId == "cash:7506"
        }.netAmount!!.arithmeticValue()))
        assertEquals(before, detailDatabaseState(db))
    }
}
