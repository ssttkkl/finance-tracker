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
                catch (failure: com.finance.tracker.business.domain.CashPolicyError) { error = failure.code }
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

    @Test fun duplicateImportWithANewBindingRefreshesProjectionButIdenticalReimportDoesNot() {
        val db = database()
        val before = detailDatabaseState(db)
        val input = Json.parseToJsonElement(root().resolve("preparation/input-session.json").readText()).jsonObject
        val original = ParsedImportCodec().decode(input.getValue("session_id").jsonPrimitive.content,
            input.getValue("content").toString().toByteArray())
        val file = original.files.first().let { file ->
            val document = file.document!!
            file.copy(document = document.copy(rows = document.rows.take(1), declaredCount = 1, observedCount = 1))
        }
        val cases = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray
        val normal = cases.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "normal" }
        val occurrence = row(normal.getValue("source_rows").jsonObject.getValue("${file.fileRef}:r1").jsonObject)
        val selected = Json.parseToJsonElement(root().resolve("preparation/business-plan-cases.json").readText()).jsonArray
            .map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "selected" }
        @Suppress("UNCHECKED_CAST") val previewInput = (cashSourceValue(selected.getValue("input")) as Map<String, Any?>).let {
            it + mapOf("row_choices" to emptyList<Any?>(),
                "account_selections" to (it.getValue("account_selections") as List<*>).take(1))
        }
        val rollback = IllegalStateException("rollback synthetic duplicate import")
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                db.write { connection ->
                    assertTrue(connection.lockWorkspace(db.sqlite, "operation-workspace"))
                    val context = CashSqlContext(connection, "operation-workspace", db.sqlite,
                        now = { Instant.parse("2026-10-01T09:00:00Z") })
                    val bindings = CASH_SOURCE_BINDINGS
                    context.sql.deleteFrom(CASH_TRANSACTION_ISSUES)
                        .where(CASH_TRANSACTION_ISSUES.WORKSPACE_ID.eq(context.workspace))
                        .and(CASH_TRANSACTION_ISSUES.TRANSACTION_ID.eq(7501)).execute()
                    context.sql.deleteFrom(TRANSACTION_RELATIONS)
                        .where(TRANSACTION_RELATIONS.WORKSPACE_ID.eq(context.workspace))
                        .and(TRANSACTION_RELATIONS.STATE.eq("draft")).execute()
                    CashSourceWriteRepository(context).saveOccurrence(7501, occurrence)
                    assertEquals(1, context.sql.deleteFrom(bindings).where(bindings.WORKSPACE_ID.eq(context.workspace))
                        .and(bindings.TRANSACTION_ID.eq(7501)).and(bindings.IDENTITY_KIND.eq("provider_id")).execute())
                    CashProjectionService.initializeInSession(context)
                    val projections = CashProjectionRepository(context)
                    val oldVersion = projections.status().getValue("projection_version").jsonPrimitive.long
                    val oldDigest = projections.sources.sourceDigest()
                    val oldKey = cashSourceOrderKeys(context, readCashFactRows(context, setOf(7501))).getValue(7501)
                    val sourceIds = context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS)
                        .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).fetch(CASH_TRANSACTIONS.ID)
                    fun commit(sessionId: String, key: String): JsonObject {
                        val session = original.copy(sessionId = sessionId, files = listOf(file), previewInput = previewInput)
                        val plan = CashImportBusinessPlanBuilder(context).build(session.files, previewInput, null)
                        assertEquals(true, plan["can_commit"])
                        val operations = (plan.getValue("operations") as List<*>).map { it as Map<*, *> }
                        assertEquals(1, operations.size)
                        assertEquals("duplicate", operations.single()["planned_action"])
                        assertEquals(emptyList<Any?>(), operations.single()["issues"])
                        @Suppress("UNCHECKED_CAST")
                        assertTrue(cashImportIssuesUnchanged(operations.single() as Map<String, Any?>))
                        return CashImportCommitWriter(context, "owner", CashImportCommitBoundary {}).apply(
                            session.copy(plan = plan), "binding-plan-$key", key, tokenDigest(key))
                    }
                    val receipt = commit("binding-first-session", "binding-first-key")
                    assertEquals(oldVersion + 1, projections.status().getValue("projection_version").jsonPrimitive.long)
                    assertEquals(oldVersion + 1, receipt.getValue("projection_version").jsonPrimitive.long)
                    assertNotEquals(oldDigest, projections.sources.sourceDigest())
                    assertNotEquals(oldKey, cashSourceOrderKeys(context, readCashFactRows(context, setOf(7501))).getValue(7501))
                    assertEquals(1, context.sql.fetchCount(bindings, bindings.WORKSPACE_ID.eq(context.workspace)
                        .and(bindings.TRANSACTION_ID.eq(7501)).and(bindings.IDENTITY_KIND.eq("provider_id"))))
                    val afterBinding = state(context)
                    val afterDigest = projections.sources.sourceDigest()
                    val repeated = commit("binding-repeat-session", "binding-repeat-key")
                    assertEquals(oldVersion + 1, repeated.getValue("projection_version").jsonPrimitive.long)
                    assertEquals(oldVersion + 1, projections.status().getValue("projection_version").jsonPrimitive.long)
                    assertEquals(afterBinding, state(context))
                    assertEquals(afterDigest, projections.sources.sourceDigest())
                    assertEquals(sourceIds, context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS)
                        .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace)).fetch(CASH_TRANSACTIONS.ID))
                    throw rollback
                }
            }
            assertSame(rollback, thrown)
        } finally { assertEquals(before, detailDatabaseState(db)) }
    }

    @Test fun allSourceUpdatesRunAndOnlyActualBindingChangesTouchTheRecord() {
        val db = database()
        val before = detailDatabaseState(db)
        val normal = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "normal" }
        @Suppress("UNCHECKED_CAST")
        val original = (cashSourceValue(normal.getValue("operations")) as List<Map<String, Any?>>).first()
        val firstRef = (original.getValue("source_updates") as List<*>).single().let { (it as Map<*, *>)["row_ref"] as String }
        val first = row(normal.getValue("source_rows").jsonObject.getValue(firstRef).jsonObject)
        val secondRef = "second-file:r1"
        val second = first.copy(fileDigest = "c".repeat(64))
        val operation = original + ("source_updates" to listOf(
            mapOf("row_ref" to firstRef, "bind" to true, "uncertain" to false),
            mapOf("row_ref" to secondRef, "bind" to true, "uncertain" to false)))
        val rollback = IllegalStateException("rollback synthetic source updates")
        try {
            val thrown = assertFailsWith<IllegalStateException> {
                db.write { connection ->
                    val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
                    val writer = CashRecordWriter(context)
                    val sources = mapOf(firstRef to first, secondRef to second)
                    assertEquals(setOf(7501L), writer.save(operation, sources).touched)
                    for (occurrence in sources.values) assertEquals(occurrence.bindingSpecs.map { it.key }.toSet(),
                        CashSourceReadRepository(context).forKeys(occurrence.bindingSpecs, emptyList()).map { it.bindingKey }.toSet())
                    val saved = state(context)
                    assertTrue(writer.save(operation, sources).touched.isEmpty())
                    assertEquals(saved, state(context))
                    val repository = CashSourceWriteRepository(context)
                    assertFalse(repository.saveOccurrence(7501, first))
                    val revised = first.copy(coverage = first.coverage.copy(filters = mapOf("synthetic" to "changed")))
                    assertTrue(repository.saveOccurrence(7501, revised))
                    val updated = state(context)
                    assertNotEquals(saved, updated)
                    assertFalse(repository.saveOccurrence(7501, revised))
                    assertEquals(updated, state(context))
                    throw rollback
                }
            }
            assertSame(rollback, thrown)
        } finally { assertEquals(before, detailDatabaseState(db)) }
    }

    @Test fun invalidOccurrenceTimeRollsBackAnEarlierWriteInTheSameTransaction() {
        val db = database()
        val before = detailDatabaseState(db)
        val case = Json.parseToJsonElement(root().resolve("cases.json").readText()).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "public_edit" }
        @Suppress("UNCHECKED_CAST") val operation = (cashSourceValue(case.getValue("operations")) as List<Map<String, Any?>>).single()
        val sources = case.getValue("source_rows").jsonObject.mapValues { row(it.value.jsonObject) }
        @Suppress("UNCHECKED_CAST") val values = operation.getValue("values") as Map<String, Any?>
        for (date in listOf("1899-12-31T23:59:59.999999Z", "2101-01-01T00:00:00Z")) {
            try {
                assertFailsWith<InvalidCashValue>(date) {
                    db.write { connection ->
                        val context = CashSqlContext(connection, "operation-workspace", db.sqlite)
                        val writer = CashRecordWriter(context)
                        assertNotEquals(JsonPrimitive("public note"), context.entries.current(7501)!!.values["note"])
                        writer.save(operation, sources)
                        assertEquals(JsonPrimitive("public note"), context.entries.current(7501)!!.values["note"])
                        writer.save((operation - "entry_edit") + ("values" to (values + ("occurred_at" to date))), sources)
                        error("out-of-range time was accepted")
                    }
                }
            } finally { assertEquals(before, detailDatabaseState(db)) }
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
