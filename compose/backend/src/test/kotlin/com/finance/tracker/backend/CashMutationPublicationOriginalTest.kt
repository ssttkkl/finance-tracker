package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashMutationPublicationOriginalTest {
    private fun root() = Path.of(requireNotNull(System.getenv("FT_KOTLIN_MUTATION_PUBLICATION_CONTRACT_DIR")))
    private fun names(method: String) = Json.parseToJsonElement(root().resolve("scenarios.json").readText()).jsonObject
        .filterValues { it.jsonObject["method"] == JsonPrimitive("test_$method") }.keys.also { assertTrue(it.isNotEmpty(), method) }
    private class Store(clock: () -> Instant, ttl: Int = 1800, codec: ParsedImportCodec = ParsedImportCodec(),
        val entries: MutableMap<String, ParsedImportObject> = linkedMapOf()) : ParsedImportStore(ttl, clock, codec) {
        var forbidReads = false
        var writes = 0
        override fun readRaw(id: String): ParsedImportObject {
            check(!forbidReads) { "consumed body read" }
            return entries[id] ?: throw ParsedImportNotFound()
        }
        override fun writeRaw(id: String, content: ByteArray, expected: String?, expiresAt: Instant) {
            if (entries[id]?.etag != expected) throw ParsedImportRevisionConflict()
            entries[id] = ParsedImportObject(content.copyOf(), digest(content)); writes++
        }
        override fun deleteRaw(id: String) { entries.remove(id) }
        override fun sweepExpired(cursor: String?, limit: Int, shouldStop: () -> Boolean) = ParsedImportSweep(0, 0, null)
    }
    private fun built(request: MutationPlanRequest): JsonObject {
        val items = (0..120).map { index -> buildJsonObject {
            put("preview_item_id", "item_$index"); put("confirmation_status", if (index % 2 == 0) "confirmed" else "pending")
            put("anticipated_state", "posted"); put("display", buildJsonObject { put("amount", "0") }); put("statistics_eligible", true)
            put("member_refs", buildJsonArray { add(buildJsonObject { put("transaction_id", request.transactionId) }) })
            put("attention_reasons", JsonArray(emptyList())); put("detail_available", true)
        } }
        return buildJsonObject {
            put("items", JsonArray(items)); put("summary", buildJsonObject { put("update_posted", 1) }); put("can_commit", true)
            put("operations", buildJsonArray { add(buildJsonObject {
                put("operation_ref", "op_1"); put("transaction_id", request.transactionId); put("private_execution", "server-only")
                put("values", buildJsonObject { put("note", request.patch["note"] ?: JsonPrimitive("冻结备注")); put("components", JsonArray(emptyList())) })
            }) })
            put("item_details", JsonObject(items.associate { item -> item.getValue("preview_item_id").jsonPrimitive.content to buildJsonObject {
                put("members", buildJsonArray { add(buildJsonObject {
                    put("transaction_id", request.transactionId); put("fields_ref", buildJsonObject { put("operation_ref", "op_1") }); put("row_revision", request.expectedRevision)
                }) })
                for (field in listOf("field_diffs", "issues", "relation_choices", "relation_effects")) put(field, JsonArray(emptyList()))
            } }))
        }
    }
    private inner class Fixture(val name: String, ttl: Int = 1800) {
        private val directory = root().resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val case = Json.parseToJsonElement(directory.resolve("case.json").readText()).jsonObject
        val input = case.getValue("input").jsonObject
        val base = case.getValue("base_input").jsonObject
        val params = case.getValue("metadata").jsonObject.getValue("params").jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        var now = Instant.parse("2026-10-21T00:00:00Z")
        val store = Store({ now }, ttl)
        val token = "original-import-owner"
        var calls = 0
        var builder = CashMutationPlanBuilder { _: CashSqlContext, request: MutationPlanRequest, _: JsonObject? -> built(request) }
        fun service(target: ParsedImportStore = store) = MutationPlanService(database, now = { now }, staging = target,
            planBuilder = CashMutationPlanBuilder { context, request, previous -> calls++; builder.build(context, request, previous) })
        val service = service()
        fun preview(body: JsonObject = base) = service.preview(token, body)
        fun id(plan: JsonObject) = plan.getValue("plan_id").jsonPrimitive.content
        fun contextId(plan: JsonObject) = plan.getValue("edit_context_id").jsonPrimitive.content
        fun again(plan: JsonObject, changes: Map<String, JsonElement> = emptyMap()) = JsonObject(base + mapOf(
            "edit_context_id" to plan.getValue("edit_context_id"), "previous_plan_id" to plan.getValue("plan_id"),
            "expected_plan_revision" to plan.getValue("plan_revision")) + changes)
        fun current(plan: JsonObject) = store.get(contextId(plan), workspace, user)
        fun detail(plan: JsonObject) = service.item(token, id(plan), "item_0")
        fun state() = database.read { connection ->
            val tables = DefaultSchema.DEFAULT_SCHEMA.tables; assertEquals(32, tables.size)
            tables.associate { table -> table.name to connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM \"${table.name}\"").use { rows ->
                    buildList { while (rows.next()) add(JsonArray((1..rows.metaData.columnCount).map { rows.getString(it)?.let(::JsonPrimitive) ?: JsonNull }).toString()) }.sorted()
                }
            } }
        }
        fun role(value: String) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, value).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace))
            .and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user)).execute() }
    }
    private fun fields(detail: JsonObject) = detail.getValue("members").jsonArray.single().jsonObject.getValue("fields").jsonObject
    private fun code(failure: RuntimeException): String = when (failure) {
        is MutationPlanFailure -> failure.code
        is MutationPlanFieldFailure -> failure.code
        is MutationPlanStore.NotFound, is MutationPlanStore.Expired -> "plan_expired"
        is MutationPlanStore.RevisionConflict, is MutationPlanStore.Closed -> "plan_changed"
        else -> throw failure
    }
    @Test fun firstPublicationHasNoFilesIsPrivateAndLeavesLedgerUnchanged() {
        for (name in names("first_publication_has_no_file_keeps_current_ledger_and_only_returns_public_fields")) {
            val f = Fixture(name); val before = f.state(); val plan = f.preview()
            assertEquals(JsonPrimitive(1), plan["plan_revision"], name); assertTrue(f.id(plan).startsWith("mp_"), name)
            assertEquals(buildJsonObject { put("confirmed", 61); put("pending", 60) }, plan["item_counts"], name)
            assertEquals(50, plan.getValue("items").jsonArray.size, name)
            assertTrue(setOf("operations", "preview_input", "session_id", "session_revision", "files").intersect(plan.keys).isEmpty(), name)
            val current = f.current(plan); assertEquals("mutation", current.workflowKind, name); assertTrue(current.files.isEmpty(), name)
            assertEquals(mapOf("transaction_id" to "1001", "version" to 1), current.contextData, name)
            val detail = f.detail(plan); assertEquals(JsonPrimitive("冻结备注"), fields(detail)["note"], name)
            assertFalse((plan.toString() + detail).contains("server-only"), name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun repreviewUsesCasKeepsTtlAndPreservesCurrentAfterRejectedInput() {
        for (name in names("repreview_is_cas_and_old_plan_is_invalid_but_failed_input_keeps_current")) {
            val f = Fixture(name); val before = f.state(); val old = f.preview()
            for (changes in listOf(mapOf("expected_plan_revision" to JsonPrimitive(0)), mapOf("previous_plan_id" to JsonNull),
                mapOf("transaction_id" to JsonPrimitive("1002")))) {
                assertFailsWith<RuntimeException>(name) { f.preview(f.again(old, changes)) }
                assertEquals(old, f.service.plan(f.token, f.id(old)), name)
            }
            val current = f.preview(f.again(old, mapOf("patch" to buildJsonObject { put("note", "新的冻结备注") })))
            assertEquals(JsonPrimitive(2), current["plan_revision"], name); assertNotEquals(f.id(old), f.id(current), name)
            assertEquals(old["expires_at"], current["expires_at"], name)
            assertEquals(current, f.service.currentPlan(f.token, f.contextId(old)), name)
            assertEquals("plan_changed", code(assertFailsWith<RuntimeException> { f.service.plan(f.token, f.id(old)) }), name)
            assertFailsWith<RuntimeException> { f.preview(f.again(old)) }
            assertEquals(JsonPrimitive("新的冻结备注"), fields(f.detail(current))["note"], name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun pagesDetailsAndReturnedObjectsStayFrozenAndCursorFilterBound() {
        for (name in names("pages_and_details_remain_frozen_and_plan_filter_bound")) {
            val f = Fixture(name); val plan = f.preview()
            f.database.write { connection -> connection.jooq(f.database.sqlite).update(CASH_TRANSACTIONS)
                .set(CASH_TRANSACTIONS.NOTE, "后来的当前备注").set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
                .where(CASH_TRANSACTIONS.ID.eq(1001L)).execute() }
            val before = f.state(); var cursor: String? = null; val ids = mutableListOf<String>()
            do {
                val page = f.service.items(f.token, f.id(plan), cursor, 17, "pending")
                ids += page.getValue("items").jsonArray.map { it.jsonObject.getValue("preview_item_id").jsonPrimitive.content }
                cursor = page["next_cursor"]?.jsonPrimitive?.contentOrNull
            } while (cursor != null)
            assertEquals(60, ids.size, name); assertEquals(60, ids.toSet().size, name)
            assertEquals(JsonPrimitive("冻结备注"), fields(f.detail(plan))["note"], name)
            val otherCursor = f.service.items(f.token, f.id(plan), null, 1, null).getValue("next_cursor").jsonPrimitive.content
            assertEquals("plan_cursor_invalid", code(assertFailsWith<RuntimeException> { f.service.items(f.token, f.id(plan), otherCursor, 50, "pending") }), name)
            val changed = JsonObject(plan + ("summary" to JsonObject(emptyMap())))
            assertTrue(changed.getValue("summary").jsonObject.isEmpty(), name)
            assertEquals(buildJsonObject { put("update_posted", 1) }, f.service.plan(f.token, f.id(plan))["summary"], name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun invalidInputNeverInvokesBuilderOrCreatesContext() {
        for (name in names("invalid_maintenance_input_never_invokes_builder_or_creates_context")) {
            val f = Fixture(name); val before = f.state()
            f.builder = CashMutationPlanBuilder { _, _, _ -> error("invalid input reached builder") }
            assertFailsWith<MutationPlanFailure>(name) { f.preview(f.input) }
            assertEquals(0, f.calls, name); assertEquals(0, f.store.writes, name); assertTrue(f.store.entries.isEmpty(), name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun staleParentAndOtherWorkspaceNeverInvokeBuilderOrPublish() {
        for (name in names("stale_parent_and_cross_workspace_record_cannot_create_plan")) {
            val f = Fixture(name); val before = f.state()
            f.builder = CashMutationPlanBuilder { _, _, _ -> error("stale or foreign record reached builder") }
            for (changes in listOf(mapOf("expected_revision" to JsonPrimitive(999)), mapOf("transaction_id" to JsonPrimitive("2001"))))
                assertFailsWith<MutationPlanFailure>(name) { f.preview(JsonObject(f.base + changes)) }
            assertEquals(0, f.calls, name); assertEquals(0, f.store.writes, name); assertTrue(f.store.entries.isEmpty(), name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun builderAndBudgetFailuresPreserveTheCompletePreviousObject() {
        for (name in names("failed_builder_or_plan_budget_keeps_previous_immutable_state")) {
            val f = Fixture(name); val old = f.preview(); val before = f.state(); val raw = f.store.entries.getValue(f.contextId(old))
            f.builder = CashMutationPlanBuilder { _, _, _ -> error("synthetic private builder failure") }
            assertEquals("synthetic private builder failure", assertFailsWith<IllegalStateException> { f.preview(f.again(old)) }.message, name)
            assertEquals(old, f.service.plan(f.token, f.id(old)), name)
            f.builder = CashMutationPlanBuilder { _, request, _ -> built(request) }
            val small = Store({ f.now }, codec = ParsedImportCodec(maxBytes = raw.content.size + 128), entries = f.store.entries)
            assertFailsWith<ParsedImportDataError>(name) {
                f.service(small).preview(f.token, f.again(old, mapOf("patch" to buildJsonObject { put("note", "超预算正文".repeat(5000)) })))
            }
            assertEquals(raw.etag, f.store.entries.getValue(f.contextId(old)).etag, name)
            assertContentEquals(raw.content, f.store.entries.getValue(f.contextId(old)).content, name)
            assertEquals(old, f.service.plan(f.token, f.id(old)), name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun liveMembershipCancelAndExpiryGateCurrentPublication() {
        for (name in names("expiry_cancel_and_live_membership_gate_the_current_plan")) {
            val f = Fixture(name, ttl = 60); val plan = f.preview()
            f.role("viewer"); assertEquals(plan, f.service.plan(f.token, f.id(plan)), name)
            assertFailsWith<AccessFailure>(name) { f.preview(f.again(plan)) }
            f.role("editor"); f.now = f.now.plusSeconds(30)
            val current = f.preview(f.again(plan)); assertEquals(plan["expires_at"], current["expires_at"], name)
            f.service.cancel(f.token, f.contextId(current), 2)
            assertEquals("plan_changed", code(assertFailsWith<RuntimeException> { f.service.plan(f.token, f.id(current)) }), name)
            val another = f.preview(); f.now = f.now.plusSeconds(60)
            assertEquals("plan_expired", code(assertFailsWith<RuntimeException> { f.service.plan(f.token, f.id(another)) }), name)
        }
    }
    @Test fun permanentConsumptionIsCheckedBeforeAnyClearedOrMissingBodyRead() {
        for (name in names("consumed_context_is_checked_before_its_cleared_or_missing_body")) {
            val f = Fixture(name); val plan = f.preview()
            val receipt = f.database.write { connection -> ReceiptRepository(connection, f.workspace, f.database.sqlite, now = { f.now })
                .create(f.user, "synthetic-mutation-consumer", "a".repeat(64), "edit_record", mapOf("status" to "completed"),
                    emptyList<Any?>(), planId = f.id(plan), editContextDigest = tokenDigest(f.contextId(plan))) }
            f.store.forbidReads = true
            for (action in listOf<() -> Any?>({ f.service.plan(f.token, f.id(plan)) }, { f.preview(f.again(plan)) })) {
                val failure = assertFailsWith<ReceiptConflict>(name, action)
                assertEquals("edit_context_committed", failure.code, name); assertEquals(receipt["receipt_id"]?.jsonPrimitive?.content, failure.receiptId, name)
            }
        }
    }
    @Test fun importWorkflowIsIsolatedAndCommitLoaderChecksActualRevisionType() {
        for (name in names("import_cannot_read_or_confirm_mutation_context_and_commit_loader_checks_plan_revision")) testApplication {
            val f = Fixture(name); val plan = f.preview(); val before = f.state()
            application { financeModule(f.database, mutationPlans = f.service) }
            assertFailsWith<ParsedImportNotFound>(name) { CashImportContextService(f.store, f.workspace, f.user).get(f.contextId(plan)) }
            val forged = "ip_" + f.id(plan).removePrefix("mp_")
            assertEquals("plan_expired", assertFailsWith<CashImportPlanError> {
                CashImportCommandService(f.database, f.store, now = { f.now }).commit(f.token, forged, 2, "synthetic-wrong-workflow")
            }.code, name)
            f.database.read { connection ->
                val principal = ReadServices(f.database, now = { f.now }).context(connection, f.token, write = true)
                val current = f.service.currentForCommit(connection, principal, f.id(plan), 1L)
                assertEquals("mutation", f.current(plan).workflowKind, name); assertEquals("1001", current.transactionId, name)
                assertEquals(plan["plan_id"], current.plan?.get("plan_id"), name)
                assertEquals("plan_changed", code(assertFailsWith<RuntimeException> { f.service.currentForCommit(connection, principal, f.id(plan), true) }), name)
            }
            val response = client.post("/api/v1/cash-mutations/plans/${f.id(plan)}/commit") {
                header(HttpHeaders.Authorization, "Bearer ${f.token}"); header("Idempotency-Key", "invalid-revision")
                contentType(ContentType.Application.Json); setBody("{\"expected_plan_revision\":true}")
            }
            val body = response.bodyAsText(); assertEquals(422, response.status.value, body)
            assertEquals(JsonPrimitive("import_payload_invalid"), Json.parseToJsonElement(body).jsonObject.getValue("error").jsonObject["code"], name)
            assertEquals(before, f.state(), name)
        }
    }
    @Test fun privateAndInvalidSummaryNeverPublishOrReplaceFrozenPlan() {
        for (name in names("private_or_invalid_summary_never_publishes_or_replaces_current_plan")) {
            val f = Fixture(name); val before = f.state()
            val malformed = CashMutationPlanBuilder { _, request, _ -> JsonObject(built(request) + ("summary" to f.params.getValue("summary"))) }
            f.builder = malformed
            assertEquals("plan_build_failed", code(assertFailsWith<RuntimeException> { f.preview() }), name)
            assertTrue(f.store.entries.isEmpty(), name); assertEquals(0, f.store.writes, name)
            f.builder = CashMutationPlanBuilder { _, request, _ -> built(request) }; val old = f.preview()
            val raw = f.store.entries.getValue(f.contextId(old)); f.builder = malformed
            assertEquals("plan_build_failed", code(assertFailsWith<RuntimeException> { f.preview(f.again(old)) }), name)
            assertEquals(raw.etag, f.store.entries.getValue(f.contextId(old)).etag, name)
            assertEquals(old, f.service.plan(f.token, f.id(old)), name); assertEquals(before, f.state(), name)
        }
    }
    @Test fun otherEditorCannotReadCurrentPlanOrCancelPrivateContext() {
        for (name in names("another_member_cannot_read_or_cancel_owned_edit_context")) {
            val f = Fixture(name); val plan = f.preview()
            f.database.write { connection -> connection.jooq(f.database.sqlite).update(WORKSPACE_MEMBERSHIPS).set(WORKSPACE_MEMBERSHIPS.ROLE, "editor")
                .where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)).and(WORKSPACE_MEMBERSHIPS.USER_ID.ne(f.user)).execute() }
            for (action in listOf<() -> Any?>({ f.service.plan("original-import-other", f.id(plan)) },
                { f.service.currentPlan("original-import-other", f.contextId(plan)) }, { f.service.cancel("original-import-other", f.contextId(plan), 1) }))
                assertFailsWith<MutationPlanStore.Forbidden>(name, action)
            assertEquals(plan, f.service.plan(f.token, f.id(plan)), name)
        }
    }
    @Test fun evenAnActuallyFlushedBuilderWriteRollsBackBeforePublication() {
        for (name in names("preview_rolls_back_even_accidentally_flushed_business_changes")) {
            val f = Fixture(name); val before = f.state(); var written = 0
            f.builder = CashMutationPlanBuilder { context, request, _ ->
                written += context.sql.update(CASH_TRANSACTIONS).set(CASH_TRANSACTIONS.NOTE, "不应提交的构建器改动")
                    .set(CASH_TRANSACTIONS.ROW_REVISION, CASH_TRANSACTIONS.ROW_REVISION.plus(1L))
                    .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(f.workspace)).and(CASH_TRANSACTIONS.ID.eq(1001L)).execute()
                built(request)
            }
            f.preview(); assertEquals(1, written, name); assertEquals(before, f.state(), name)
        }
    }
}
