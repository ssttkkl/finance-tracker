package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportCommitPreparationContractTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_COMMIT_PREPARATION_CONTRACT_DIR")))
    private fun database() = Json.parseToJsonElement(root().resolve("database.json").readText()).jsonObject.let {
        Database(it.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
    }
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun wire(value: Any?): JsonElement = when (value) {
        is JsonElement -> value
        is CashStoredDecimal -> JsonPrimitive(fixedCashDecimal(value))
        is Instant -> JsonPrimitive(ParsedImportCodec.timeText(value))
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to wire(it.value) })
        is Iterable<*> -> JsonArray(value.map(::wire))
        else -> sourceJson(value)
    }
    private fun state(context: CashSqlContext): JsonElement {
        fun rows(table: org.jooq.Table<*>, fields: String, order: String) = context.sql.selectFrom(table)
            .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace)).orderBy(order.split(',').map { table.field(it)!! }).fetch()
            .map { record -> fields.split(',').associateWith { record.get(it) } }
        return wire(mapOf(
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
    private fun row(raw: JsonElement): SourceOccurrence {
        val item = raw.jsonObject
        fun text(key: String) = item[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        return SourceOccurrence(text("source_type")!!, text("source_account_key"), text("file_digest")!!, text("parser_version")!!,
            text("table_key")!!, item.getValue("position").jsonPrimitive.int, map(cashSourceValue(item.getValue("fields"))),
            cashSourceCoverage(item.getValue("coverage")), text("provider_id"), text("stable_evidence"), text("document_position"))
    }
    private fun context(connection: java.sql.Connection, db: Database) = CashSqlContext(connection, "operation-workspace", db.sqlite, now = { Instant.parse("2026-10-01T09:00:00Z") })

    @Test fun boundedNewRowsMatchPythonAcrossThresholdAndBatchBoundary() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in Json.parseToJsonElement(root().resolve("batches.json").readText()).jsonArray.map { it.jsonObject }) db.write { connection ->
            val savepoint = connection.setSavepoint()
            if (!db.sqlite) for (name in listOf("cash_transactions", "cash_transaction_components")) connection.createStatement().use {
                it.execute("SELECT setval('${name}_id_seq', (SELECT max(id) FROM $name), true)")
            }
            val context = context(connection, db)
            val operations = maps(cashSourceValue(case.getValue("operations")))
            val original = CashRecordPolicy.copyValue(operations)
            val sources = case.getValue("sources").jsonObject.mapValues { row(it.value) }
            if (case["seed_binding"] == JsonPrimitive(true)) CashSourceWriteRepository(context).saveOccurrence(7601L, sources.getValue("row-0"))
            var error: String? = null
            var result = emptyMap<String, CashRecordWriteResult>()
            try {
                result = if (case["direct"] == JsonPrimitive(true)) {
                    val saved = CashImportNewRowsRepository(context).insert(operations.map { operation -> operation to maps(operation["source_updates"]).filter { it["bind"] == true }.map { sources.getValue(it["row_ref"] as String) } })
                    operations.map { it["operation_ref"] as String }.zip(saved).toMap()
                } else CashImportCommitPreparation(context).newBatches(operations, sources)
            } catch (failure: CashSourceBindingConflict) { error = failure.code }
            catch (failure: IllegalArgumentException) { error = failure.message }
            val values = result.mapValues { (_, saved) -> mapOf("transaction_id" to saved.transactionId, "mapping" to saved.componentIds,
                "before" to saved.before, "after" to saved.after, "touched" to saved.touched.sorted()) }
            assertEquals(case.getValue("expected"), wire(mapOf("result" to values, "error" to error, "rows" to state(context))), case.getValue("name").jsonPrimitive.content)
            assertEquals(original, operations)
            if (case.getValue("name") == JsonPrimitive("over_one_batch")) assertEquals(101, result.size)
            if (case.getValue("name") == JsonPrimitive("mixed_parts")) {
                assertEquals(2, result.getValue("op-1").componentIds.size)
                assertEquals("public batch note", context.entries.current(result.getValue("op-2").transactionId)!!.values.getValue("note").jsonPrimitive.content)
            }
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun documentExclusionAndRequiredOldProofMatchPython() {
        val db = database()
        val before = detailDatabaseState(db)
        for (case in Json.parseToJsonElement(root().resolve("documents.json").readText()).jsonArray.map { it.jsonObject }) db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = context(connection, db)
            val staged = ParsedImportCodec().decode(case.getValue("session_id").jsonPrimitive.content, case.getValue("content").toString().encodeToByteArray())
            if (case["seed_old"] == JsonPrimitive(true)) {
                val coverage = staged.files.single().document!!.coverage.copy(pageFingerprints = mapOf("1" to "c".repeat(64), "2" to "b".repeat(64)))
                val old = SourceOccurrence("alipay", "document-owner", "x".repeat(64), "doc-v1", "table", 1, emptyMap(), coverage, documentPosition = "p1:t0:r1")
                CashSourceWriteRepository(context).saveOccurrence(7601L, old)
            }
            val sources = CashImportSourcePlanner(context).prepare(staged.files)
            var error: String? = null
            try { CashImportCommitPreparation(context).saveEvidence(staged.files, sources, map(cashSourceValue(case.getValue("plan")))) }
            catch (failure: CashSourceBindingConflict) { error = failure.code }
            val pages = context.sql.selectFrom(CASH_SOURCE_EMPTY_PAGES).where(CASH_SOURCE_EMPTY_PAGES.WORKSPACE_ID.eq(context.workspace))
                .orderBy(CASH_SOURCE_EMPTY_PAGES.SOURCE_TYPE, CASH_SOURCE_EMPTY_PAGES.PARSER_VERSION, CASH_SOURCE_EMPTY_PAGES.DOCUMENT_KEY, CASH_SOURCE_EMPTY_PAGES.PAGE_NUMBER)
                .fetch().map { record -> listOf("source_type", "parser_version", "document_key", "page_number", "coverage").associateWith { record.get(it) } }
            assertEquals(case.getValue("expected"), wire(mapOf("error" to error, "pages" to pages)), case.getValue("name").jsonPrimitive.content)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun frozenCandidatesValidateSignatureAndCurrentMembership() {
        val db = database()
        for (case in Json.parseToJsonElement(root().resolve("candidates.json").readText()).jsonArray.map { it.jsonObject }) {
            var error: String? = null
            try { CashImportCommitPreparation.frozenCandidateSets(cashSourceValue(case.getValue("values"))) }
            catch (failure: IllegalArgumentException) { error = failure.message }
            assertEquals(case["error"], error?.let(::JsonPrimitive) ?: JsonNull)
        }
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = context(connection, db)
            val ids = listOf("7501", "7502")
            val key = sourceDigest("cash-source-candidates-v1", ids)
            val operation = mapOf("candidate_ids" to emptyList<Any?>(), "candidate_set_ref" to key)
            val sets = CashImportCommitPreparation.frozenCandidateSets(mapOf(key to ids))
            val preparation = CashImportCommitPreparation(context)
            assertEquals(ids, preparation.candidateOperation(operation, sets)["candidate_ids"])
            assertEquals(emptyList<Any?>(), operation["candidate_ids"])
            assertFailsWith<IllegalArgumentException> { preparation.candidateOperation(operation + ("candidate_ids" to listOf("7501")), sets) }
            assertFailsWith<IllegalArgumentException> { preparation.candidateOperation(operation + ("candidate_ids" to true), sets) }
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, context.now()).where(CASH_TRANSACTIONS.ID.eq(7502L)).execute()
            assertEquals("source_candidate_changed", assertFailsWith<CashSourceBindingConflict> { CashImportCommitPreparation(context).candidateOperation(operation, sets) }.code)
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
    }

    @Test fun bulkRefusesBoundIdentityEvenWhenItsParentWasDeleted() {
        val db = database()
        val before = detailDatabaseState(db)
        val case = Json.parseToJsonElement(root().resolve("batches.json").readText()).jsonArray.map { it.jsonObject }.first { it["name"] == JsonPrimitive("existing_binding") }
        db.write { connection ->
            val savepoint = connection.setSavepoint()
            val context = context(connection, db)
            val operations = maps(cashSourceValue(case.getValue("operations")))
            val sources = case.getValue("sources").jsonObject.mapValues { row(it.value) }
            CashSourceWriteRepository(context).saveOccurrence(7601L, sources.getValue("row-0"))
            context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.DELETED_AT, context.now()).where(CASH_TRANSACTIONS.ID.eq(7601L)).execute()
            val saved = state(context)
            assertEquals("source_identity_conflict", assertFailsWith<CashSourceBindingConflict> { CashImportCommitPreparation(context).newBatches(operations, sources) }.code)
            assertEquals(saved, state(context))
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
        }
        assertEquals(before, detailDatabaseState(db))
    }

    @Test fun lateBatchFailureRollsBackEveryParentPartAndAlias() {
        val db = database()
        val before = detailDatabaseState(db)
        val case = Json.parseToJsonElement(root().resolve("batches.json").readText()).jsonArray.map { it.jsonObject }.first { it["name"] == JsonPrimitive("duplicate_provider") }
        assertFailsWith<CashSourceBindingConflict> { db.write { connection ->
            val context = context(connection, db)
            val operations = maps(cashSourceValue(case.getValue("operations")))
            val sources = case.getValue("sources").jsonObject.mapValues { row(it.value) }
            val failure = assertFailsWith<CashSourceBindingConflict> { CashImportCommitPreparation(context).newBatches(operations, sources) }
            assertEquals("source_identity_conflict", failure.code)
            assertTrue(context.sql.fetchCount(CASH_TRANSACTIONS) > 3)
            throw failure
        } }
        assertEquals(before, detailDatabaseState(db))
    }
}
