package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.DefaultSchema
import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportOriginalPublicationTest {
    private fun map(value: Any?) = value as Map<*, *>
    private fun rows(value: Any?) = value as List<*>
    private fun mutable(value: Any?) = value as MutableMap<String, Any?>
    private fun error(code: String, block: () -> Any?) = assertEquals(code, assertFailsWith<CashImportPlanError>(block = block).code)

    private fun built(): Map<String, Any?> {
        val items = (0..120).map { index -> linkedMapOf<String, Any?>(
            "preview_item_id" to "item_$index", "confirmation_status" to if (index % 2 == 0) "confirmed" else "pending",
            "anticipated_state" to if (index % 2 == 0) "posted" else "draft",
            "display" to mutableMapOf("amount" to if (index % 2 == 0) "-20.00" else null),
            "statistics_eligible" to (index % 2 == 0), "member_refs" to listOf(mutableMapOf("row_ref" to "f:r$index")),
            "attention_reasons" to emptyList<Any?>(), "detail_available" to true) }
        return linkedMapOf("items" to items, "summary" to mutableMapOf("create_posted" to 61, "create_draft" to 60),
            "file_results" to emptyList<Any?>(), "can_commit" to true,
            "processing_details" to listOf(mutableMapOf("row_ref" to "skipped:r1", "planned_action" to "skip", "code" to "row_unreadable")),
            "item_details" to items.associate { it.getValue("preview_item_id") as String to mutableMapOf(
                "members" to it.getValue("member_refs"), "field_diffs" to emptyList<Any?>(), "issues" to emptyList<Any?>(),
                "relation_choices" to emptyList<Any?>(), "relation_effects" to emptyList<Any?>()) },
            "operations" to listOf(mapOf("operation_ref" to "server_only", "private_source" to "not-a-public-plan-field")))
    }

    private inner class Fixture(name: String) {
        private val directory = Path.of(requireNotNull(System.getenv("FT_KOTLIN_IMPORT_PUBLICATION_CONTRACT_DIR"))).resolve(name)
        private val config = Json.parseToJsonElement(directory.resolve("database.json").readText()).jsonObject
        val workspace = config.getValue("workspace").jsonPrimitive.content
        val user = config.getValue("user").jsonPrimitive.content
        val database = Database(config.getValue("jdbc_url").jsonPrimitive.content,
            System.getenv("FT_KOTLIN_TEST_DB_USER"), System.getenv("FT_KOTLIN_TEST_DB_PASSWORD"))
        var now = Instant.parse("2026-10-21T00:00:00Z")
        val store = InMemoryParsedImportStore(clock = { now })
        val token = "original-import-owner"
        var builder: CashImportPlanBuilder = CashImportPlanBuilder { _, _, _, _ -> built() }
        val service = CashImportHttpService(database, store, CashImportPlanBuilder { context, files, input, previous ->
            builder.build(context, files, input, previous)
        })
        private val document = ParsedCashDocument("alipay", "csv:0", "fixture-v1", listOf(ParsedCashRow(8,
            mutableMapOf("amount" to "-20.00", "currency" to "CNY", "occurred_at" to "2026-08-15T02:00:00+00:00",
                "note" to "原始文本", "source_payload" to mapOf("金额" to "20.00")), "fixture-bank", "001")),
            "fixture-owner", "fixture-owner", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), 1, 1)
        fun create(): ParsedImportSession {
            val parser = CashImportHttpService(database, store) { _, _ -> document }
            val result = parser.create(token, listOf(CashImportUpload("client-1", "synthetic.csv", "synthetic-original".encodeToByteArray())))
            return current(result.getValue("session_id") as String)
        }
        fun current(id: String) = store.get(id, workspace, user)
        fun preview(session: ParsedImportSession, old: Map<String, Any?>? = null, input: Map<String, Any?> = emptyMap()) =
            service.preview(token, session.sessionId, session.revision.toLong(), old?.get("plan_id") as String?, input)
        fun id(plan: Map<String, Any?>) = plan.getValue("plan_id") as String
        fun items(plan: Map<String, Any?>, cursor: String? = null, start: Int? = null, limit: Int = 50, status: String? = null) =
            service.planItems(token, id(plan), cursor, start, limit, status)
        fun detail(plan: Map<String, Any?>) = service.planItem(token, id(plan), "item_0")
        fun processing(plan: Map<String, Any?>, cursor: String? = null) = service.planProcessing(token, id(plan), cursor, 50)
        fun snapshot() = database.read { connection ->
            DefaultSchema.DEFAULT_SCHEMA.tables.sortedBy { it.name }.associate { table ->
                val values = connection.createStatement().use { statement -> statement.executeQuery("SELECT * FROM \"${table.name}\"").use { result ->
                    val metadata = result.metaData
                    buildList { while (result.next()) add(JsonArray((1..metadata.columnCount).map { index ->
                        result.getString(index)?.let(::JsonPrimitive) ?: JsonNull
                    }).toString()) }.sorted()
                } }
                table.name to values
            }
        }
        fun role(role: String) = database.write { connection -> connection.jooq(database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, role).where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(workspace)
                .and(WORKSPACE_MEMBERSHIPS.USER_ID.eq(user))).execute() }
        fun consume(id: String) = database.write { connection -> ReceiptRepository(connection, workspace, database.sqlite)
            .create(user = user, key = "committed", requestDigest = "a".repeat(64), operation = "import", planId = "plan-1",
                sessionDigest = ParsedImportStore.digest(id.encodeToByteArray()), resultSummary = mapOf("status" to "no_changes"),
                results = emptyList<Any?>()) }
    }

    @Test fun publicationIncrementsRevisionPreservesTtlAndNeverWritesLedger() {
        val f = Fixture("publication"); val created = f.create(); val before = f.snapshot()
        val plan = f.preview(created)
        assertEquals(BigInteger.TWO, plan["session_revision"])
        assertEquals(ParsedImportCodec.timeText(created.expiresAt), plan["expires_at"])
        assertEquals(50, rows(plan["items"]).size)
        assertEquals(mapOf("confirmed" to 61, "pending" to 60), plan["item_counts"])
        assertNotNull(map(plan["pages"])["items"]); assertFalse("operations" in plan)
        val current = f.current(created.sessionId)
        assertEquals(BigInteger.TWO, current.revision); assertEquals(f.id(plan), current.plan!!["plan_id"])
        assertEquals(created.files, current.files)
        assertEquals(mapOf("account_selections" to emptyList<Any?>(), "row_choices" to emptyList<Any?>(),
            "relation_choices" to emptyList<Any?>(), "entry_choices" to emptyList<Any?>()), current.previewInput)
        assertEquals(before, f.snapshot())
    }

    @Test fun mutableBuilderInputsAndReturnValuesCannotChangeSavedData() {
        val f = Fixture("immutable"); val created = f.create()
        f.builder = CashImportPlanBuilder { _, files, input, _ ->
            mutable(files[0].document!!.rows[0].fields)["note"] = "污染输入"
            (input.getValue("row_choices") as MutableList<Any?>).add(mapOf("row_ref" to "fake", "action" to "apply"))
            built()
        }
        val plan = f.preview(created)
        mutable(plan["summary"])["create_posted"] = 0
        mutable(map(rows(plan["items"])[0])["display"])["amount"] = "0"
        (f.detail(plan)["members"] as MutableList<*>).clear()
        val restored = f.service.plan(f.token, f.id(plan))
        assertEquals(61, map(restored["summary"])["create_posted"])
        assertEquals("-20.00", map(map(rows(restored["items"])[0])["display"])["amount"])
        assertTrue(rows(f.detail(plan)["members"]).isNotEmpty())
        assertEquals(created.files, f.current(created.sessionId).files)
        assertEquals(emptyList<Any?>(), f.current(created.sessionId).previewInput["row_choices"])
    }

    @Test fun fullRepreviewReplacesPlanAndRejectsStaleRevisionAndPreviousId() {
        val f = Fixture("repreview"); val created = f.create(); val old = f.preview(created)
        for ((revision, previous) in listOf(1L to f.id(old), 2L to null, 2L to "another")) {
            error("plan_changed") { f.service.preview(f.token, created.sessionId, revision, previous, emptyMap()) }
        }
        val choices = listOf(mapOf("row_ref" to "f:r1", "action" to "keep_existing"))
        val new = f.preview(f.current(created.sessionId), old, mapOf("row_choices" to choices))
        assertNotEquals(f.id(old), f.id(new)); assertEquals(BigInteger.valueOf(3), new["session_revision"])
        assertEquals(choices, f.current(created.sessionId).previewInput["row_choices"])
        error("plan_changed") { f.service.plan(f.token, f.id(old)) }
    }

    @Test fun filteredPagesBindCursorToPlanKindAndFilter() = testApplication {
        val f = Fixture("paging"); val plan = f.preview(f.create()); val ids = mutableListOf<Any?>(); var cursor: String? = null
        do {
            val page = f.items(plan, cursor, limit = 17, status = "pending")
            assertTrue(rows(page["items"]).all { map(it)["confirmation_status"] == "pending" })
            ids.addAll(rows(page["items"]).map { map(it)["preview_item_id"] }); cursor = page["next_cursor"] as String?
        } while (cursor != null)
        assertEquals(60, ids.size); assertEquals(60, ids.toSet().size)
        assertEquals("row_unreadable", map(rows(f.processing(plan)["items"])[0])["code"])
        cursor = f.items(plan, limit = 1)["next_cursor"] as String
        error("plan_cursor_invalid") { f.items(plan, cursor, status = "pending") }
        error("plan_cursor_invalid") { f.processing(plan, cursor) }
        val another = f.preview(f.create())
        error("plan_cursor_invalid") { f.items(another, cursor) }
        for (limit in listOf(0, 201)) error("plan_page_invalid") { f.items(plan, limit = limit) }
        application { financeModule(f.database, cashImports = f.service) }
        val invalid = client.get("/api/v1/cash-import/plans/${f.id(plan)}/items?limit=true") {
            header(HttpHeaders.Authorization, "Bearer ${f.token}")
        }
        assertEquals(422, invalid.status.value)
        assertEquals("import_payload_invalid", Json.parseToJsonElement(invalid.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
    }

    @Test fun absolutePositionsUseFilteredTotalsAndRejectOutOfBounds() {
        val f = Fixture("absolute"); val plan = f.preview(f.create())
        val page = f.items(plan, start = 10, limit = 20, status = "pending")
        assertEquals(10, page["start_index"]); assertEquals(60, page["total_count"])
        assertEquals((21..59 step 2).map { "item_$it" }, rows(page["items"]).map { map(it)["preview_item_id"] })
        assertNotNull(page["next_cursor"])
        assertEquals(mapOf("items" to emptyList<Any?>(), "next_cursor" to null, "start_index" to 60, "total_count" to 60),
            f.items(plan, start = 60, limit = 20, status = "pending"))
        error("plan_page_invalid") { f.items(plan, start = 61, status = "pending") }
        error("plan_page_invalid") { f.items(plan, cursor = "cursor", start = 0) }
    }

    @Test fun fileChangesCancellationAndExpiryInvalidatePlan() {
        val f = Fixture("invalidation"); val created = f.create(); val plan = f.preview(created)
        f.service.removeFile(f.token, created.sessionId, created.files[0].fileRef, 2)
        error("plan_changed") { f.service.plan(f.token, f.id(plan)) }
        val other = f.create(); val cancelled = f.preview(other)
        f.service.cancel(f.token, other.sessionId)
        error("plan_changed") { f.service.plan(f.token, f.id(cancelled)) }
        val expired = f.preview(f.create()); f.now = f.now.plusSeconds(1801)
        error("plan_expired") { f.service.plan(f.token, f.id(expired)) }
    }

    @Test fun consumedContextTakesPriorityOverCancelledStaging() {
        val f = Fixture("consumed"); val created = f.create(); val plan = f.preview(created); val receipt = f.consume(created.sessionId)
        f.store.cancel(created.sessionId, f.workspace, f.user, 2)
        for (operation in listOf<() -> Any?>({ f.service.preview(f.token, created.sessionId, 2, f.id(plan), emptyMap()) },
            { f.service.plan(f.token, f.id(plan)) })) {
            val failure = assertFailsWith<ReceiptConflict>(block = operation)
            assertEquals("session_committed", failure.code)
            assertEquals(receipt.getValue("receipt_id").jsonPrimitive.content, failure.receiptId)
        }
    }

    @Test fun viewerCannotPublishAndOtherEditorCannotReadPlan() {
        val f = Fixture("permission"); val created = f.create(); val plan = f.preview(created)
        f.role("viewer")
        assertFailsWith<AccessFailure> { f.service.preview(f.token, created.sessionId, 2, f.id(plan), emptyMap()) }
        f.database.write { connection -> connection.jooq(f.database.sqlite).update(WORKSPACE_MEMBERSHIPS)
            .set(WORKSPACE_MEMBERSHIPS.ROLE, "editor").where(WORKSPACE_MEMBERSHIPS.WORKSPACE_ID.eq(f.workspace)
                .and(WORKSPACE_MEMBERSHIPS.USER_ID.ne(f.user))).execute() }
        assertFailsWith<ParsedImportForbidden> { f.service.plan("original-import-other", f.id(plan)) }
    }

    @Test fun builderFailurePreservesPreviousImmutablePlan() {
        val f = Fixture("builder_failure"); val created = f.create(); val old = f.preview(created); val before = f.current(created.sessionId)
        val financial = f.snapshot()
        f.builder = CashImportPlanBuilder { _, _, _, _ -> throw IllegalStateException("synthetic builder failure") }
        assertEquals("synthetic builder failure", assertFailsWith<IllegalStateException> { f.preview(before, old) }.message)
        assertEquals(before, f.current(created.sessionId)); assertEquals(financial, f.snapshot())
    }

    @Test fun invalidSelectionStructuresCannotPublishOrIncreaseRevision() {
        val f = Fixture("invalid_input"); val created = f.create()
        val patches = listOf(
            mapOf("row_choices" to listOf(mapOf("row_ref" to "a:r1", "action" to emptyList<Any?>()))),
            mapOf("row_choices" to listOf(mapOf("row_ref" to "a:r1", "action" to "apply", "patch" to emptyList<Any?>()))),
            mapOf("row_choices" to List(2) { mapOf("row_ref" to "a:r1", "action" to "apply") }),
            mapOf("account_selections" to listOf(mapOf("source_account_ref" to "a", "account_id" to "1", "expected_account_revision" to true))),
            mapOf("account_selections" to listOf(mapOf("source_account_ref" to "a", "account_id" to null, "expected_account_revision" to 1))),
            mapOf("relation_choices" to listOf(mapOf("suggestion_id" to "a", "action" to "accept", "operation_graph" to emptyMap<String, Any?>()))))
        for (patch in patches) {
            error("plan_input_invalid") { f.preview(created, input = patch) }
            assertEquals(created, f.current(created.sessionId))
        }
    }

    @Test fun publicItemRowsDropServerExecutionFields() {
        val f = Fixture("private_items")
        f.builder = CashImportPlanBuilder { _, _, _, _ -> built().also { plan ->
            rows(plan["items"]).forEach { mutable(it)["private_operation"] = mapOf("technical" to "server-only") }
        } }
        val plan = f.preview(f.create())
        assertTrue(rows(plan["items"]).all { "private_operation" !in map(it) })
        assertTrue(rows(f.items(plan)["items"]).all { "private_operation" !in map(it) })
    }

    @Test fun unreadableBuilderOutputCannotReplaceOldPlan() {
        val f = Fixture("malformed"); val created = f.create(); val old = f.preview(created); val before = f.current(created.sessionId)
        val financial = f.snapshot()
        val malformed = listOf(mapOf("processing_details" to null), mapOf("item_details" to emptyList<Any?>()),
            mapOf("file_results" to null), mapOf("item_details" to mapOf("item_0" to mapOf("members" to null))))
        for (patch in malformed) {
            f.builder = CashImportPlanBuilder { _, _, _, _ -> built() + patch }
            error("plan_build_failed") { f.preview(before, old) }
            assertEquals(before, f.current(created.sessionId)); assertEquals(financial, f.snapshot())
        }
    }

    @Test fun processingCursorStartsAtFirstUndeliveredItem() {
        val f = Fixture("processing_cursor")
        f.builder = CashImportPlanBuilder { _, _, _, _ -> built() + ("processing_details" to (0..120).map {
            mapOf("row_ref" to "skip:r$it", "code" to "row_unreadable") }) }
        val plan = f.preview(f.create()); var cursor = map(plan["pages"])["processing_details"] as String?; val refs = mutableListOf<String>()
        do {
            val page = f.processing(plan, cursor); refs.addAll(rows(page["items"]).map { map(it)["row_ref"] as String })
            cursor = page["next_cursor"] as String?
        } while (cursor != null)
        assertEquals((0..120).map { "skip:r$it" }, refs)
    }

    @Test fun publicNestedFieldsDropPrivateExecutionData() {
        val f = Fixture("private_nested")
        f.builder = CashImportPlanBuilder { _, _, _, _ -> built().also { plan ->
            val detail = mutable(map(plan["item_details"])["item_0"])
            detail["private_operation"] = mapOf("secret" to "server-only")
            mutable(rows(detail["members"])[0])["private_operation"] = "private-member"
            mutable(map(rows(plan["items"])[0])["display"])["private_operation"] = "private-display"
            mutable(rows(plan["processing_details"])[0])["private_operation"] = "private-processing"
        } }
        val plan = f.preview(f.create()); val detail = f.detail(plan)
        assertFalse("private_operation" in map(map(rows(plan["items"])[0])["display"]))
        assertFalse("private_operation" in detail); assertFalse("private_operation" in map(rows(detail["members"])[0]))
        assertFalse("private_operation" in map(rows(f.processing(plan)["items"])[0]))
    }

    @Test fun publicIssuesKeepScopeActionAndSafeMessage() {
        val f = Fixture("safe_issues")
        val issue = mapOf("code" to "account_missing", "message" to "尚未选择账户", "field_path" to "account_id",
            "blocking_scope" to "row", "next_action" to "edit")
        f.builder = CashImportPlanBuilder { _, _, _, _ -> built().toMutableMap().also { plan ->
            mutable(map(plan["item_details"])["item_0"])["issues"] = listOf(issue)
            plan["file_results"] = listOf(mapOf("file_ref" to "a", "issues" to listOf(issue + mapOf("blocking_scope" to "file", "next_action" to "reselect_file"))))
        } }
        val plan = f.preview(f.create())
        assertEquals(listOf(issue), f.detail(plan)["issues"])
        assertEquals("reselect_file", map(rows(map(rows(plan["file_results"])[0])["issues"])[0])["next_action"])
    }

    @Test fun issueMessagesUseSafeDescriptionsAndInvalidScopeCannotPublish() {
        val f = Fixture("invalid_scope"); val created = f.create()
        fun output(issue: Map<String, Any?>) = built().also { mutable(map(it["item_details"])["item_0"])["issues"] = listOf(issue) }
        f.builder = CashImportPlanBuilder { _, _, _, _ -> output(mapOf("code" to "row_unreadable", "message" to "SQL secret-provider")) }
        val plan = f.preview(created); val issue = map(rows(f.detail(plan)["issues"])[0])
        assertFalse("SQL" in (issue["message"] as String)); assertEquals("row", issue["blocking_scope"])
        f.builder = CashImportPlanBuilder { _, _, _, _ -> output(mapOf("code" to "row_unreadable", "blocking_scope" to "database")) }
        error("plan_build_failed") { f.preview(f.current(created.sessionId), plan) }
        assertEquals(BigInteger.TWO, f.current(created.sessionId).revision)
    }

    @Test fun expiredPlanStaysExpiredAfterBackgroundCleanup() {
        val f = Fixture("swept"); val plan = f.preview(f.create()); f.now = f.now.plusSeconds(1801)
        assertEquals(1, f.store.sweepExpired().deleted)
        error("plan_expired") { f.service.plan(f.token, f.id(plan)) }
    }

    @Test fun processingWithoutIssueCodeDropsUntrustedProblemText() {
        val f = Fixture("no_code")
        for (extra in listOf(emptyMap<String, Any?>(), mapOf("code" to null))) {
            f.builder = CashImportPlanBuilder { _, _, _, _ -> built() + ("processing_details" to listOf(mapOf(
                "row_ref" to "a:r1", "planned_action" to "duplicate", "message" to "SQL secret-provider", "blocking_scope" to "request") + extra)) }
            val plan = f.preview(f.create()); val item = map(rows(f.processing(plan)["items"])[0])
            assertFalse("message" in item); assertFalse("blocking_scope" in item)
        }
    }
}
