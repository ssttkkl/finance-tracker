package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashRecordWriterContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_RECORD_WRITER_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is CashAmount -> JsonPrimitive(fixedCashDecimal(CashStoredDecimal(value.toString())))
        is Instant -> JsonPrimitive(ParsedImportCodec.timeText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun state(context: CashSqlContext): JsonElement {
        fun rows(table: org.jooq.Table<*>, fields: String, order: String): List<Map<String, Any?>> = context.sql.selectFrom(table)
            .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace))
            .orderBy(order.split(',').map { table.field(it)!! }).fetch().map { record -> fields.split(',').associateWith { record.get(it) } }
        return wire(linkedMapOf(
            "parents" to rows(CASH_TRANSACTIONS, "id,state,record_origin,source_type,record_id,source_payload,relation_metadata,manual_overrides,row_revision,account_id,amount,currency,occurred_at,cash_granularity,counterparty,note,category_id,counterparty_account,counterparty_account_attrs,record_type,record_subtype", "id"),
            "components" to rows(CASH_TRANSACTION_COMPONENTS, "id,cash_transaction_id,account_id,amount,currency,ordinal,label,source_key,metadata_json", "id"),
            "bindings" to rows(CASH_SOURCE_BINDINGS, "transaction_id,source_type,source_scope_kind,source_scope_key,identity_kind,identity_key,identity_strength,stable_evidence_key,coverage,revision", "transaction_id,source_scope_kind,source_scope_key,identity_kind,identity_key"),
            "issues" to rows(CASH_TRANSACTION_ISSUES, "id,transaction_id,issue_key,code,field_path,pending_fields,pending_source_payload,origin,revision", "id"),
            "issue_candidates" to rows(CASH_ISSUE_CANDIDATES, "issue_id,candidate_transaction_id,expected_row_revision", "issue_id,candidate_transaction_id"),
            "relations" to rows(TRANSACTION_RELATIONS, "id,state,active,row_revision,primary_transaction_id,secondary_transaction_id,anchor_transaction_id,primary_component_id,secondary_component_id,anchor_component_id,primary_role,secondary_role,decision_source,decided_by,decision_reason", "id"),
            "relation_candidates" to rows(CASH_RELATION_CANDIDATES, "relation_id,candidate_transaction_id,candidate_component_id,expected_row_revision,validity", "relation_id,candidate_transaction_id"),
            "funding" to rows(CASH_INVESTMENT_FUNDING_RELATIONS, "id,status,active_slot,decided_by,decision_reason", "id"),
            "entries" to rows(CASH_ENTRIES, "root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit", "root_transaction_id")))
    }
    private fun row(value: JsonObject): SourceOccurrence {
        fun text(key: String) = value[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        @Suppress("UNCHECKED_CAST") val fields = cashSourceValue(value.getValue("fields")) as Map<String, Any?>
        return SourceOccurrence(text("source_type")!!, text("source_account_key"), text("file_digest")!!, text("parser_version")!!,
            text("table_key")!!, value.getValue("position").jsonPrimitive.int, fields, cashSourceCoverage(value.getValue("coverage")),
            text("provider_id"), text("stable_evidence"), text("document_position"))
    }

    @Test fun fullRowWritesMatchPythonAndRollBack() {
        val db = database()
        val before = detailDatabaseState(db)
        for (raw in Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray) {
            val case = raw.jsonObject
            @Suppress("UNCHECKED_CAST") val operations = cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>
            val sources = case.getValue("source_rows").jsonObject.mapValues { row(it.value.jsonObject) }
            db.write { connection ->
                val savepoint = connection.setSavepoint()
                if (!db.sqlite) for (name in listOf("cash_transactions", "cash_transaction_components", "cash_transaction_issues"))
                    connection.createStatement().use { it.execute("SELECT setval('${name}_id_seq', (SELECT max(id) FROM $name), true)") }
                val context = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })
                val results = mutableListOf<Map<String, Any?>>()
                var error: String? = null
                try {
                    for (operation in operations) {
                        val saved = CashRecordWriter(context).save(operation, sources)
                        results += linkedMapOf("transaction_id" to saved.transactionId, "mapping" to saved.componentIds,
                            "before" to saved.before, "after" to saved.after, "touched" to saved.touched.sorted(),
                            "issues_unchanged" to cashImportIssuesUnchanged(operation))
                    }
                } catch (failure: CashRelationFailure) { error = failure.code }
                catch (failure: CashPolicyError) { error = failure.code }
                catch (failure: CashSourceBindingConflict) { error = failure.code }
                assertEquals(case["error"]?.jsonPrimitive?.content, error, case.getValue("name").jsonPrimitive.content)
                if (error == null) assertEquals(case.getValue("result"), wire(results), case.getValue("name").jsonPrimitive.content)
                assertEquals(case.getValue("expected"), state(context), case.getValue("name").jsonPrimitive.content)
                when (case.getValue("name").jsonPrimitive.content) {
                    "type_mirror" -> {
                        assertFalse(context.sql.select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.ID.eq(1)).fetchSingle().value1()!!)
                        assertTrue(context.sql.select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.ID.eq(2)).fetchSingle().value1()!!)
                    }
                    "type_negative_refund" -> assertTrue(context.sql.select(TRANSACTION_RELATIONS.ACTIVE).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.ID.`in`(1L, 2L)).fetch().all { it.value1() == true })
                    "type_funding" -> assertEquals("rejected", context.sql.select(CASH_INVESTMENT_FUNDING_RELATIONS.STATUS).from(CASH_INVESTMENT_FUNDING_RELATIONS).fetchSingle().value1())
                    "reorder_draft" -> {
                        val p = CASH_TRANSACTION_COMPONENTS
                        val part = context.sql.selectFrom(p).where(p.ID.eq(9901)).fetchSingle()
                        assertEquals(1L, part[p.ORDINAL])
                        assertEquals("part-0", part[p.LABEL])
                        assertEquals(buildJsonObject { put("preserved", true) }, part[p.METADATA_JSON])
                    }
                    "replace_draft" -> {
                        val p = CASH_TRANSACTION_COMPONENTS
                        assertFalse(context.sql.fetchExists(p, p.ID.`in`(9901L, 9902L)))
                        val c = CASH_RELATION_CANDIDATES
                        val candidate = context.sql.selectFrom(c).where(c.RELATION_ID.eq(80101)).fetchSingle()
                        assertNull(candidate[c.CANDIDATE_COMPONENT_ID])
                        assertEquals("stale", candidate[c.VALIDITY])
                    }
                    "public_edit" -> {
                        assertEquals("public note", context.entries.current(7501)!!.values["note"]!!.jsonPrimitive.content)
                        assertEquals(1L, context.sql.select(CASH_TRANSACTIONS.ROW_REVISION).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(7501)).fetchSingle().value1())
                    }
                }
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
            }
            assertEquals(case.getValue("operations"), wire(operations))
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun candidatesInvalidateOnceForActualRevisionOrActiveEndpointDependencies() {
        val db = database()
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            assertTrue(context.markCashRelationCandidatesStale(setOf(7501)).isEmpty())
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(7501)).execute()
            assertEquals(setOf(80102L), context.markCashRelationCandidatesStale(setOf(7501)))
            assertTrue(context.markCashRelationCandidatesStale(setOf(7501, 7507)).isEmpty())
            assertEquals(2L, context.sql.select(TRANSACTION_RELATIONS.ROW_REVISION).from(TRANSACTION_RELATIONS).where(TRANSACTION_RELATIONS.ID.eq(80102)).fetchSingle().value1())
            connection.rollback(savepoint)
            assertEquals(setOf(80102L), context.markCashRelationCandidatesStale(setOf(7507)))
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
    }

    @Test fun changedDependencyAndConfirmedComponentDeletionRollBackEntireWriterTransaction() {
        val db = database()
        val before = detailDatabaseState(db)
        assertEquals("relation_context_changed", assertFailsWith<CashRelationFailure> {
            db.write { connection ->
                val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
                val service = CashRelationTypeUpdateService(context)
                val expected = CashRelationContextService(context).load(setOf(7501))
                context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(7503)).execute()
                context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE, "investment_out")
                    .set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(7501)).execute()
                service.maintain(expected, setOf(7501), publish = false)
            }
        }.code)
        assertEquals(before, detailDatabaseState(db))
        val case = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "delete_confirmed_component" }
        @Suppress("UNCHECKED_CAST") val operation = (cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>).single()
        assertEquals("relation_context_changed", assertFailsWith<CashRelationFailure> {
            db.write { connection -> CashRecordWriter(CashSqlContext(connection, "operation-workspace", db.sqlite)).save(operation, emptyMap()) }
        }.code)
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun typeChangePublishesOldGroupAndKeepsUnrelatedProjection() {
        val db = database()
        val before = detailDatabaseState(db)
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
            CashProjectionService.initializeInSession(context)
            val projection = CASH_PROJECTIONS
            fun untouched() = context.sql.select(projection.ID, projection.MEMBER_COUNT, projection.NET_AMOUNT).from(projection)
                .where(projection.ROOT_CASH_TRANSACTION_ID.eq(7507)).fetchSingle().let { listOf(it.value1(), it.value2(), it.value3()?.text) }
            val original = untouched()
            val expected = CashRelationContextService(context).load(setOf(7501))
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.RECORD_TYPE, "investment_out")
                .set(CASH_TRANSACTIONS.ROW_REVISION, 2L).where(CASH_TRANSACTIONS.ID.eq(7501)).execute()
            val result = CashRelationTypeUpdateService(context).maintain(expected, setOf(7501))
            assertEquals(setOf(1L), result.removedRelationIds)
            assertEquals(setOf(7501L, 7503L, 7504L), result.affectedTransactionIds)
            assertNotNull(result.projectionStatus)
            assertEquals(original, untouched())
            assertEquals(setOf(7501L, 7503L), context.sql.select(projection.ROOT_CASH_TRANSACTION_ID).from(projection)
                .where(projection.ROOT_CASH_TRANSACTION_ID.`in`(7501L, 7503L, 7504L)).fetch(projection.ROOT_CASH_TRANSACTION_ID).toSet())
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }
}
