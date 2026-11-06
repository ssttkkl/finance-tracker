package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashMutationRecoveryOriginalTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MUTATION_RECOVERY_CONTRACT_DIR")))
    private fun names(method: String) = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject
        .filterValues { it.jsonObject["method"] == JsonPrimitive("test_$method") }.keys.also { assertTrue(it.isNotEmpty(), method) }
    private class Store(clock: () -> Instant, ttl: Int = 1800) : ParsedImportStore(ttl, clock) {
        val entries = linkedMapOf<String, ParsedImportObject>()
        var forbidReads = false
        var failCancel = false
        var failedCancelWrites = 0
        override fun readRaw(id: String): ParsedImportObject {
            check(!forbidReads) { "permanent consumption must precede staging reads" }
            return entries[id] ?: throw ParsedImportNotFound()
        }
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            if (failCancel && Json.parseToJsonElement(content.decodeToString()).jsonObject["cancelled"]?.jsonPrimitive?.booleanOrNull == true) {
                failedCancelWrites++; error("synthetic temporary cleanup failure")
            }
            entries[id] = ParsedImportObject(content.copyOf(), digest(content))
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private inner class Fixture(val name: String, ttl: Int = 1800) {
        private val directory = root().resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val input = case.getValue("input").jsonObject
        val params = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        val locals = case.getValue("locals").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val token = "original-import-owner"
        var now: Instant = Instant.parse("2026-10-22T00:00:00Z")
        val store = Store({ now }, ttl)
        val database = database()
        fun database(decorate: (java.sql.Connection) -> java.sql.Connection = { it }) = Database(
            config.getValue("jdbc_url").jsonPrimitive.content, System.getenv("FT_KOTLIN_TEST_DB_USER"),
            System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"), decorate)
        fun service(db: Database = database) = MutationPlanService(db, now = { now }, staging = store)
        val service = service()
        val id get() = input.getValue("transaction_id").jsonPrimitive.content
        fun plan(body: JsonObject = input) = service.preview(token, body)
        fun planId(plan: JsonObject) = plan.getValue("plan_id").jsonPrimitive.content
        fun contextId(plan: JsonObject) = plan.getValue("edit_context_id").jsonPrimitive.content
        fun commit(plan: JsonObject, key: String = "synthetic-mutation-confirm", actor: String = token) = service.commit(actor,
            planId(plan), plan.getValue("plan_revision").jsonPrimitive.long, key)
        fun resolve(plan: JsonObject, key: String = "synthetic-mutation-confirm", target: MutationPlanService = service) = target.resolve(token, planId(plan), key)
        fun detail() = CashRecordDetailService(database, now = { now }).detail(token, id)
        fun balances() = database.read { connection -> CashSqlContext(connection, workspace, database.sqlite, now = { now }).snapshot.load().value }
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(32, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
        fun role(value: String, other: Boolean = false) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, value).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace))
            .and(if (other) WORKSPACE_MEMBERSHIPS.USER_ID.ne(user) else WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
        fun next(plan: JsonObject? = null, note: String = "新计划") = JsonObject(input + mapOf(
            "expected_revision" to detail().getValue("row_revision"), "patch" to buildJsonObject { put("note", note) }) +
            if (plan == null) emptyMap() else mapOf("edit_context_id" to plan.getValue("edit_context_id"),
                "previous_plan_id" to plan.getValue("plan_id"), "expected_plan_revision" to plan.getValue("plan_revision")))
        fun importUnrelated() {
            val source = case.getValue("import_session").jsonObject
            val files = ParsedImportCodec().decode(case.getValue("import_session_id").jsonPrimitive.content,
                source.toString().encodeToByteArray()).files
            val staged = store.create(workspace, user, files)
            @Suppress("UNCHECKED_CAST") val body = cashSourceValue(source.getValue("preview_input")) as Map<String, Any?>
            val plan = CashImportHttpService(database, store).preview(token, staged.sessionId, 1, null, body)
            val view = sourceJson(plan).jsonObject
            CashImportCommandService(database, store, now = { now }).commit(token, view.getValue("plan_id").jsonPrimitive.content,
                view.getValue("session_revision").jsonPrimitive.long, "synthetic-unrelated-import")
        }
    }
    private fun status(value: String) = buildJsonObject { put("status", value) }
    private fun code(failure: RuntimeException): String = when (failure) {
        is MutationPlanFailure -> failure.code
        is MutationPlanFieldFailure -> failure.code
        is ReceiptConflict -> failure.code
        else -> throw failure
    }
    @Test fun realIndependentWorkspaceWriterIsTheOnlyPendingOutcome() {
        for (name in names("resolve_returns_pending_only_for_the_independent_workspace_writer")) {
            val f = Fixture(name); val plan = f.plan(); val before = f.state()
            f.database.write { writer ->
                assertTrue(writer.lockWorkspace(f.database.sqlite, f.workspace), name)
                assertEquals(status("pending"), f.resolve(plan), name)
            }
            assertEquals(before, f.state(), name); assertEquals(status("retry_original"), f.resolve(plan), name)
            val receipt = f.commit(plan); assertEquals(receipt, f.resolve(plan)["receipt"], name)
        }
    }
    @Test fun everyLocalDependencyChangeRejectsBeforeWritesAndKeepsContext() {
        for (name in names("current_dependency_change_rejects_before_writes_without_consuming_context")) {
            val f = Fixture(name); val plan = f.plan(); val before = f.detail(); val change = f.params.getValue("change").jsonPrimitive.content
            f.database.write { connection ->
                val context = CashSqlContext(connection, f.workspace, f.database.sqlite, now = { f.now }); val sql = context.sql
                when (change) {
                    "parent" -> assertEquals(1, sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, "后来保存的备注")
                        .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L)).where(CASH_TRANSACTIONS.ID.eq(f.id.toLong())).execute())
                    "issue" -> CashSourceWriteRepository(context).saveIssue(f.id, "source:pending", "source_update_pending",
                        pendingFields = mapOf("counterparty" to "新待采用"))
                    else -> {
                        val relation = f.locals.getValue("relation_id").jsonPrimitive.long
                        val update = sql.update(TRANSACTION_RELATIONS).set(TRANSACTION_RELATIONS.ROW_REVISION, TRANSACTION_RELATIONS.ROW_REVISION.plus(1L))
                        if (change == "relation") update.set(TRANSACTION_RELATIONS.DECISION_REASON, "后来修改的决定")
                        else update.set(TRANSACTION_RELATIONS.ACTIVE, false)
                        assertEquals(1, update.where(TRANSACTION_RELATIONS.ID.eq(relation)).execute())
                    }
                }
            }
            if (change == "members") CashProjectionService(f.database, f.workspace, now = { f.now }).rebuild()
            val current = f.detail(); val facts = f.state(); val balance = f.balances()
            if (change != "parent") assertEquals(before["row_revision"], current["row_revision"], name)
            assertTrue(code(assertFailsWith<RuntimeException> { f.commit(plan) }) in setOf("record_changed", "relation_context_changed"), name)
            assertEquals(current, f.detail(), name); assertEquals(facts, f.state(), name); assertEquals(balance, f.balances(), name)
            assertEquals(plan, f.service.plan(f.token, f.planId(plan)), name)
            if (change == "issue") f.database.read { connection ->
                val row = connection.jooq(f.database.sqlite).select(CASH_TRANSACTION_ISSUES.PENDING_FIELDS).from(CASH_TRANSACTION_ISSUES)
                    .where(CASH_TRANSACTION_ISSUES.ID.eq(f.locals.getValue("issue_id").jsonPrimitive.long)).fetchSingle()
                assertEquals(buildJsonObject { put("counterparty", "新待采用") }, row.value1(), name)
            }
        }
    }
    @Test fun unrelatedRealImportAndAccountRenameDoNotInvalidatePostedNote() {
        for (name in names("unrelated_import_and_account_rename_do_not_invalidate_formal_note_edit")) {
            val f = Fixture(name); val plan = f.plan(); f.importUnrelated()
            f.database.write { connection -> connection.jooq(f.database.sqlite).update(ACCOUNTS).set(ACCOUNTS.NAME, "后来重命名的账户")
                .set(ACCOUNTS.REVISION, ACCOUNTS.REVISION.plus(1L)).where(ACCOUNTS.ID.eq(101L)).execute() }
            val receipt = f.commit(plan); assertEquals(JsonPrimitive(1), receipt.getValue("summary").jsonObject["updated_posted"], name)
            assertEquals(JsonPrimitive("仍可保存"), f.detail().getValue("entry").jsonObject.getValue("fields").jsonObject["note"], name)
        }
    }
    @Test fun disabledAccountBlocksFinalizationAndAllowsExistingPostedNote() {
        for (name in names("account_disabled_after_preview_blocks_only_draft_finalization")) {
            val f = Fixture(name); val before = f.detail(); val plan = f.plan()
            f.database.write { connection -> connection.jooq(f.database.sqlite).update(ACCOUNTS).set(ACCOUNTS.ACTIVE, false)
                .set(ACCOUNTS.REVISION, ACCOUNTS.REVISION.plus(1L)).where(ACCOUNTS.ID.eq(101L)).execute() }
            if (f.params.getValue("draft").jsonPrimitive.boolean) {
                val facts = f.state(); assertEquals("account_unavailable", code(assertFailsWith<RuntimeException> { f.commit(plan) }), name)
                assertEquals(before, f.detail(), name); assertEquals(plan, f.service.plan(f.token, f.planId(plan)), name); assertEquals(facts, f.state(), name)
            } else assertEquals(JsonPrimitive(1), f.commit(plan).getValue("summary").jsonObject["updated_posted"], name)
        }
    }
    @Test fun permanentKeyAndContextFencesPrecedeBodyEvenForReplayAndResolve() {
        for (name in names("permanent_context_consumption_and_idempotency_are_checked_before_body")) {
            val f = Fixture(name); val plan = f.plan(); val receipt = f.commit(plan); val next = f.plan(f.next()); val before = f.state()
            f.store.forbidReads = true
            assertEquals(receipt, f.commit(plan), name)
            assertEquals("edit_context_committed", assertFailsWith<ReceiptConflict> { f.commit(plan, "synthetic-other-key") }.code, name)
            assertEquals("idempotency_conflict", assertFailsWith<ReceiptConflict> { f.commit(next) }.code, name)
            assertEquals("idempotency_conflict", assertFailsWith<ReceiptConflict> { f.resolve(next) }.code, name)
            assertEquals(receipt, f.resolve(plan, "synthetic-other-key")["receipt"], name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun cleanupFailureKeepsKnownSuccessAndPermanentReceipt() {
        for (name in names("cleanup_failure_after_commit_does_not_hide_known_success")) {
            val f = Fixture(name); val plan = f.plan(); f.store.failCancel = true
            val receipt = f.commit(plan); assertEquals(1, f.store.failedCancelWrites, name)
            assertTrue(f.contextId(plan) in f.store.entries, name)
            assertEquals("edit_context_committed", assertFailsWith<ReceiptConflict> { f.service.plan(f.token, f.planId(plan)) }.code, name)
            val before = f.state(); assertEquals(receipt, f.commit(plan), name); assertEquals(receipt, f.resolve(plan)["receipt"], name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun cancelledExpiredAndReplacedPlansAreBusinessRejectionsAndNotCommitted() {
        for (name in names("closed_or_replaced_plan_cannot_commit_and_resolves_authoritatively")) {
            val f = Fixture(name, ttl = 60); val before = f.detail(); val plan = f.plan()
            assertEquals(status("retry_original"), f.resolve(plan), name)
            when (f.params.getValue("ending").jsonPrimitive.content) {
                "cancel" -> f.service.cancel(f.token, f.contextId(plan), plan.getValue("plan_revision").jsonPrimitive.long)
                "expire" -> f.now = f.now.plusSeconds(60)
                else -> f.plan(f.next(plan, "新决定"))
            }
            val facts = f.state(); val failure = assertFailsWith<MutationPlanFailure>(name) { f.commit(plan) }
            assertTrue(failure.code in setOf("plan_changed", "plan_expired"), name)
            assertEquals(status("not_committed"), f.resolve(plan), name); assertEquals(before, f.detail(), name); assertEquals(facts, f.state(), name)
        }
    }
    @Test fun nonLockStorageFailuresPropagateFromTheActualIndependentWriteStage() {
        for (name in names("resolve_propagates_non_lock_storage_errors")) {
            val f = Fixture(name); val plan = f.plan(); val before = f.state(); val attempted = AtomicInteger()
            val db = f.database { connection ->
                if (attempted.incrementAndGet() == 2) throw StorageFailure(f.params.getValue("code").jsonPrimitive.content)
                connection
            }
            val failure = assertFailsWith<StorageFailure>(name) { f.resolve(plan, "synthetic-storage-error", f.service(db)) }
            assertEquals(2, attempted.get(), name); assertEquals(f.params.getValue("code").jsonPrimitive.content, failure.code, name)
            assertEquals(before, f.state(), name); assertEquals(plan, f.service.plan(f.token, f.planId(plan)), name)
        }
    }
    @Test fun commitReplayAndReceiptAlwaysRecheckLiveEditorAndPrivateOwner() {
        for (name in names("commit_and_replay_recheck_current_editor_and_private_owner")) {
            val f = Fixture(name); val plan = f.plan(); f.role("editor", other = true); f.role("viewer")
            val before = f.state(); assertFailsWith<AccessFailure>(name) { f.commit(plan) }
            assertFailsWith<MutationPlanStore.Forbidden>(name) { f.commit(plan, actor = "original-import-other") }
            assertEquals(before, f.state(), name); f.role("editor"); val receipt = f.commit(plan)
            assertEquals("receipt_not_found", assertFailsWith<ReceiptFailure> {
                ReceiptReadService(f.database, now = { f.now }).receipt("original-import-other", receipt.getValue("receipt_id").jsonPrimitive.content)
            }.code, name)
            f.role("viewer"); val saved = f.state(); assertFailsWith<AccessFailure>(name) { f.commit(plan) }; assertEquals(saved, f.state(), name)
        }
    }
}
