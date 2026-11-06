package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class LedgerCashWriteVersionContractTest {
    private fun version(db: Database, workspace: String) = db.read { c -> c.jooq(db.sqlite).select(WORKSPACES.LEDGER_SEQ)
        .from(WORKSPACES).where(WORKSPACES.ID.eq(workspace)).fetchSingle(WORKSPACES.LEDGER_SEQ)!! }
    private fun fixture(action: (Database, String, String, Long) -> Unit) = withLedgerTestWorkspace { db, workspace, token ->
        val account = AccountService(db).create(token, buildJsonObject {
            put("name", "Cash write account"); put("type", "cash"); put("currencies", JsonArray(listOf(JsonPrimitive("CNY"))))
        }, "create").getValue("account_id").jsonPrimitive.long
        action(db, workspace, token, account)
    }
    private fun values(account: Long): Map<String, Any?> = mapOf("state" to "posted", "account_id" to account.toString(),
        "amount" to "-1.00", "currency" to "CNY", "occurred_at" to Instant.parse("2026-10-01T09:00:00Z"),
        "cash_granularity" to "atomic", "record_type" to "consumption", "record_subtype" to "not_applicable",
        "counterparty" to "Merchant", "counterparty_account" to "", "counterparty_account_attrs" to emptyList<String>(),
        "note" to "", "category_id" to null, "source_payload" to emptyMap<String, Any?>(),
        "components" to listOf(mapOf("ordinal" to 0, "account_id" to account.toString(), "amount" to "-1.00", "currency" to "CNY")))
    private fun operation(values: Map<String, Any?>, id: Long? = null, current: Map<String, Any?>? = null, duplicate: Boolean = false) =
        mapOf<String, Any?>("values" to values, "transaction_id" to id?.toString(), "current" to current,
            "manual_fields" to emptyList<String>(), "planned_action" to if (duplicate) "duplicate" else "write",
            "preview_components" to (values.getValue("components") as List<*>).map { raw ->
                val part = raw as Map<*, *>; mapOf("ordinal" to part["ordinal"], "component_id" to "virtual-${part["ordinal"]}") },
            "source_updates" to emptyList<Any>(), "issues" to emptyList<Any>(), "expected_issues" to emptyList<Any>(),
            "retained_issue_ids" to emptyList<Any>(), "resolved_issue_ids" to emptyList<Any>(), "pending_fields" to emptyMap<String, Any?>())
    private fun write(db: Database, workspace: String, action: (CashSqlContext) -> Unit) = db.write { c ->
        check(c.lockWorkspace(db.sqlite, workspace)); action(CashSqlContext(c, workspace, db.sqlite))
    }
    @Test fun ordinaryWriterCreatesUpdatesAndKeepsDuplicatesAtTheSameVersion() = fixture { db, workspace, _, account ->
        val initial = version(db, workspace); val input = values(account)
        lateinit var saved: CashRecordWriteResult
        write(db, workspace) { saved = CashRecordWriter(it).save(operation(input), emptyMap()) }
        assertEquals(initial + 1, version(db, workspace))
        @Suppress("UNCHECKED_CAST") val parts = (input.getValue("components") as List<Map<String, Any?>>).map {
            it + ("component_id" to saved.componentIds.getValue("virtual-0")) }
        val current = input + ("components" to parts)
        write(db, workspace) { CashRecordWriter(it).save(operation(current, saved.transactionId, current, true), emptyMap()) }
        assertEquals(initial + 1, version(db, workspace))
        val updated = current + ("note" to "source update")
        write(db, workspace) { CashRecordWriter(it).save(operation(updated, saved.transactionId, current), emptyMap()) }
        assertEquals(initial + 2, version(db, workspace))
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { write(db, workspace) { CashRecordWriter(it).save(operation(input), emptyMap()); throw Rejected() } }
        assertEquals(initial + 2, version(db, workspace))
        db.read { c -> assertEquals(1, c.jooq(db.sqlite).fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace))) }
    }
    @Test fun unchangedDraftComponentsPreserveMetadataAndDoNotAdvance() = fixture { db, workspace, _, account ->
        val input = values(account) + ("state" to "draft")
        lateinit var saved: CashRecordWriteResult
        write(db, workspace) { saved = CashRecordWriter(it).save(operation(input), emptyMap()) }
        @Suppress("UNCHECKED_CAST") val parts = (input.getValue("components") as List<Map<String, Any?>>).map {
            it + ("component_id" to saved.componentIds.getValue("virtual-0")) }
        val current = input + ("components" to parts)
        fun components() = db.read { c -> c.jooq(db.sqlite).selectFrom(CASH_TRANSACTION_COMPONENTS)
            .where(CASH_TRANSACTION_COMPONENTS.WORKSPACE_ID.eq(workspace)).fetch().map { it.intoMap() } }
        val initial = version(db, workspace); val before = components()
        write(db, workspace) { CashRecordWriter(it).save(operation(current, saved.transactionId, current, true), emptyMap()) }
        assertEquals(initial, version(db, workspace)); assertEquals(before, components())
    }
    @Test fun draftAmountRepresentationChangesRetainExistingBackendStorageSemantics() = fixture { db, workspace, _, account ->
        for ((original, updated) in listOf("-1.0" to "-1.00", "-0.0" to "0.00")) {
            val part = mapOf<String, Any?>("ordinal" to 0, "account_id" to account.toString(), "amount" to original, "currency" to "CNY")
            val input = values(account) + mapOf("state" to "draft", "amount" to original, "components" to listOf(part))
            lateinit var saved: CashRecordWriteResult
            write(db, workspace) { saved = CashRecordWriter(it).save(operation(input), emptyMap()) }
            val current = input + ("components" to listOf(part + ("component_id" to saved.componentIds.getValue("virtual-0"))))
            val desired = current + ("components" to listOf(part + mapOf("component_id" to saved.componentIds.getValue("virtual-0"), "amount" to updated)))
            val initial = version(db, workspace)
            write(db, workspace) { CashRecordWriter(it).save(operation(desired, saved.transactionId, current, true), emptyMap()) }
            assertEquals(initial + if (db.sqlite) 1 else 0, version(db, workspace))
            db.read { c ->
                val amount = c.jooq(db.sqlite).select(CASH_TRANSACTION_COMPONENTS.AMOUNT).from(CASH_TRANSACTION_COMPONENTS)
                    .where(CASH_TRANSACTION_COMPONENTS.ID.eq(saved.componentIds.getValue("virtual-0").toLong())).fetchSingle().value1()!!
                if (db.sqlite) assertEquals(updated, amount.text)
                else assertEquals(CashAmount.parse(updated), CashAmount.parse(amount.text))
            }
        }
    }
    @Test fun draftComponentReorderAndReplacementKeepIdentitiesAndMetadataOrRetireThemAtomically() = fixture { db, workspace, _, account ->
        val parts = listOf("-1.00", "-2.00").mapIndexed { ordinal, amount -> mapOf<String, Any?>("ordinal" to ordinal,
            "account_id" to account.toString(), "amount" to amount, "currency" to "CNY") }
        val input = values(account) + mapOf("state" to "draft", "cash_granularity" to "aggregate", "account_id" to null,
            "amount" to "-3.00", "components" to parts)
        lateinit var saved: CashRecordWriteResult
        write(db, workspace) { saved = CashRecordWriter(it).save(operation(input), emptyMap()) }
        val ids = (0..1).map { saved.componentIds.getValue("virtual-$it").toLong() }
        write(db, workspace) { it.sql.update(CASH_TRANSACTION_COMPONENTS).set(CASH_TRANSACTION_COMPONENTS.LABEL, "retained")
            .set(CASH_TRANSACTION_COMPONENTS.SOURCE_KEY, "source-key")
            .set(CASH_TRANSACTION_COMPONENTS.METADATA_JSON, buildJsonObject { put("keep", true) })
            .where(CASH_TRANSACTION_COMPONENTS.ID.`in`(ids)).execute() }
        val current = input + ("components" to parts.mapIndexed { i, part -> part + ("component_id" to ids[i].toString()) })
        val reordered = input + ("components" to parts.mapIndexed { i, part -> part + mapOf("component_id" to ids[i].toString(), "ordinal" to 1 - i) })
        val initial = version(db, workspace)
        write(db, workspace) { CashRecordWriter(it).save(operation(reordered, saved.transactionId, current), emptyMap()) }
        assertEquals(initial + 1, version(db, workspace))
        db.read { c ->
            val table = CASH_TRANSACTION_COMPONENTS
            val rows = c.jooq(db.sqlite).selectFrom(table).where(table.ID.`in`(ids)).fetch().associateBy { it[table.ID]!! }
            for ((i, id) in ids.withIndex()) {
                val row = rows.getValue(id); assertEquals((1 - i).toLong(), row[table.ORDINAL]); assertEquals("retained", row[table.LABEL])
                assertEquals("source-key", row[table.SOURCE_KEY]); assertEquals(buildJsonObject { put("keep", true) }, row[table.METADATA_JSON])
            }
        }
        val replacement = values(account) + mapOf("state" to "draft", "amount" to "-3.00", "components" to listOf(parts[0] + ("amount" to "-3.00")))
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { write(db, workspace) {
            CashRecordWriter(it).save(operation(replacement, saved.transactionId, reordered), emptyMap()); throw Rejected() } }
        assertEquals(initial + 1, version(db, workspace))
        write(db, workspace) { CashRecordWriter(it).save(operation(replacement, saved.transactionId, reordered), emptyMap()) }
        assertEquals(initial + 2, version(db, workspace))
        db.read { c ->
            val table = CASH_TRANSACTION_COMPONENTS
            val row = c.jooq(db.sqlite).selectFrom(table).where(table.WORKSPACE_ID.eq(workspace)).fetchSingle()
            assertTrue(row[table.ID] !in ids); assertEquals(0L, row[table.ORDINAL]); assertEquals("", row[table.LABEL])
            assertEquals(JsonObject(emptyMap()), row[table.METADATA_JSON]); assertEquals(CashAmount.parse("-3"), CashAmount.parse(row[table.AMOUNT]!!.text))
        }
    }
    @Test fun newRowBatchAdvancesOnceAndLateDuplicateBindingFailureRollsBack() = fixture { db, workspace, _, account ->
        val initial = version(db, workspace)
        write(db, workspace) { context -> CashImportNewRowsRepository(context).insert(List(3) { operation(values(account)) to emptyList() }) }
        assertEquals(initial + 1, version(db, workspace))
        val source = SourceOccurrence("alipay", "owner", "a".repeat(64), "fixture-v1", "table", 1, emptyMap(), providerId = "same")
        assertEquals("source_identity_conflict", assertFailsWith<CashSourceBindingConflict> {
            write(db, workspace) { CashImportNewRowsRepository(it).insert(List(2) { operation(values(account)) to listOf(source) }) }
        }.code)
        assertEquals(initial + 1, version(db, workspace))
        db.read { c -> assertEquals(3, c.jooq(db.sqlite).fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace))) }
    }
    @Test fun draftMergeWithoutBindingsStillAdvancesAndRollsBackOnFailure() = fixture { db, workspace, _, account ->
        val ids = db.write { c ->
            check(c.lockWorkspace(db.sqlite, workspace)); val context = CashSqlContext(c, workspace, db.sqlite)
            (1..2).map { context.insertAtomicCash(account, Instant.parse("2026-10-01T09:00:00Z"), CashAmount.parse("-1"),
                "CNY", "consumption", "not_applicable", "import").first }.also { ids ->
                context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.STATE, "draft").where(CASH_TRANSACTIONS.ID.eq(ids[0])).execute() }
        }
        val op = mapOf<String, Any?>("resolved_issue_ids" to emptyList<Any>(), "merge_draft" to mapOf(
            "source_transaction_id" to ids[0], "target_transaction_id" to ids[1], "pending_fields" to emptyMap<String, Any?>(),
            "reference_moves" to mapOf("issues" to emptyList<Any>(), "issue_candidates" to emptyList<Any>(), "relation_candidates" to emptyList<Any>()),
            "references" to mapOf("issues" to emptyList<Any>(), "relations" to emptyList<Any>()), "relations" to emptyList<Any>()))
        val initial = version(db, workspace)
        class Rejected : RuntimeException()
        assertFailsWith<Rejected> { write(db, workspace) { CashDraftMergeWriter(it).save(op); throw Rejected() } }
        assertEquals(initial, version(db, workspace))
        write(db, workspace) { CashDraftMergeWriter(it).prepareRecordUpdates(op) }
        assertEquals(initial, version(db, workspace))
        write(db, workspace) { CashDraftMergeWriter(it).save(op) }
        assertEquals(initial + 1, version(db, workspace))
    }
    @Test fun manualCreationAndDeletionAdvanceEvenWhenProjectionIsUnavailable() = fixture { db, workspace, token, _ ->
        db.write { c -> c.jooq(db.sqlite).update(CASH_PROJECTION_STATES).set(CASH_PROJECTION_STATES.AVAILABILITY, "uninitialized")
            .where(CASH_PROJECTION_STATES.WORKSPACE_ID.eq(workspace)).execute() }
        val initial = version(db, workspace)
        val body = buildJsonObject { put("account_name", "Cash write account"); put("currency", "CNY"); put("amount", "-2")
            put("occurred_at", "2026-10-01"); put("record_type", "consumption") }
        val result = ManualCashService(db).create(token, body)
        assertEquals(initial + 1, version(db, workspace))
        val id = result.getValue("record").jsonObject.getValue("id").jsonPrimitive.content
        CashDeletionService(db).deleteRecord(token, id)
        assertEquals(initial + 2, version(db, workspace))
        assertFailsWith<CashDeletionFailure> { CashDeletionService(db).deleteRecord(token, id) }
        assertEquals(initial + 2, version(db, workspace))
    }
}
