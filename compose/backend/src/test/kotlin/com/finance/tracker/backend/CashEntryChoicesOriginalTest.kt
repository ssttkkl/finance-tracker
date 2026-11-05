package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import io.ktor.client.statement.*
import io.ktor.server.testing.testApplication
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashEntryChoicesOriginalTest {
    private fun fixture(name: String) = CashImportOriginalApiFixture(name, "FT_KOTLIN_ENTRY_CHOICE_DIR")
    private fun plan(f: CashImportOriginalApiFixture): Map<String, Any?> {
        val files = f.files("default"); val staged = f.store.create(f.workspace, f.user, files)
        return f.service.preview(f.token, staged.sessionId, 1, null, mapOf("account_selections" to CashImportFunding.scan(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "101", "expected_account_revision" to 1)
        }))
    }
    private fun items(plan: Map<String, Any?>) = (plan["items"] as List<*>).map { it as Map<*, *> }
    private fun display(plan: Map<String, Any?>) = items(plan).first()["display"] as Map<*, *>
    private fun edit(f: CashImportOriginalApiFixture, plan: Map<String, Any?>, patch: Map<String, Any?>,
        rows: List<Map<String, Any?>>? = null): Map<String, Any?> {
        val id = plan["plan_id"] as String
        val detail = f.service.planItem(f.token, id, items(plan).first()["preview_item_id"] as String)
        val ref = (detail["entry"] as Map<*, *>)["edit_ref"]
        val current = f.store.get(CashImportPlanViews.sessionId(id), f.workspace, f.user)
        val input = current.previewInput + mapOf("row_choices" to (rows ?: current.previewInput["row_choices"]),
            "entry_choices" to listOf(mapOf("edit_ref" to ref, "patch" to patch)))
        return f.service.preview(f.token, current.sessionId, current.revision.toLong(), id, input)
    }
    private fun commit(f: CashImportOriginalApiFixture, plan: Map<String, Any?>, commands: CashImportCommandService = f.commands) =
        commands.commit(f.token, plan["plan_id"] as String, (plan["session_revision"] as Number).toLong(), "synthetic-confirm")
    private fun note(f: CashImportOriginalApiFixture, id: Long) = f.database.read {
        CashEntryRepository(it, f.workspace, f.database.sqlite).current(id)!!.values.getValue("note").jsonPrimitive.content
    }
    private fun sourceNote(f: CashImportOriginalApiFixture, id: Long) = f.database.read {
        it.jooq(f.database.sqlite).select(CASH_TRANSACTIONS.NOTE).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.ID.eq(id)).fetchSingle(CASH_TRANSACTIONS.NOTE)
    }
    private fun rebuild(f: CashImportOriginalApiFixture) = CashProjectionService(f.database, f.workspace, { f.now }).rebuild()

    @Test fun newEntryClearSurvivesRepreviewAndCommitWithoutChangingSource() {
        val f = fixture("new"); val edited = edit(f, plan(f), mapOf("note" to "", "counterparty_account_attrs" to emptyList<Any>()))
        assertEquals("", display(edited)["note"])
        val again = edit(f, edited, mapOf("note" to "", "counterparty_account_attrs" to emptyList<Any>()))
        assertEquals(JsonPrimitive(true), commit(f, again).getValue("commit_outcome").jsonObject["has_business_writes"])
        val id = f.database.read { connection ->
            val t = CASH_TRANSACTIONS; val p = CASH_PROJECTIONS; val sql = connection.jooq(f.database.sqlite)
            val row = sql.select(t.ID, t.NOTE, t.SOURCE_PAYLOAD).from(t).where(t.SOURCE_TYPE.eq("alipay")).fetchSingle()
            val fields = f.files("default").single().document!!.rows.single().fields!!
            assertEquals(fields["note"], row[t.NOTE]); assertEquals(sourceJson(fields["source_payload"]), row[t.SOURCE_PAYLOAD])
            assertEquals("", sql.select(p.NOTE).from(p).where(p.ROOT_CASH_TRANSACTION_ID.eq(row[t.ID])).fetchSingle(p.NOTE)); row[t.ID]!!
        }
        assertEquals("", note(f, id))
    }

    @Test fun mixedNewAndExistingWithoutOperationEditTheCompleteResult() {
        val f = fixture("mixed"); val plan = plan(f); assertEquals(1, items(plan).size)
        val refs = items(plan).single()["member_refs"] as List<*>; assertEquals(2, refs.size); assertTrue(refs.any { "transaction_id" in (it as Map<*, *>) })
        val edited = edit(f, plan, mapOf("note" to "public result")); assertEquals("public result", display(edited)["note"])
        commit(f, edited); assertEquals("public result", note(f, 1003)); rebuild(f); assertEquals("public result", note(f, 1003))
        assertNotEquals("public result", sourceNote(f, 1003))
    }

    @Test fun sourceDecisionAndExistingRelationDoNotSwallowPublicEdit() {
        for (action in listOf("keep_existing", "defer")) {
            val f = fixture("source-$action"); val plan = plan(f); assertTrue(items(plan).isNotEmpty())
            val edited = edit(f, plan, mapOf("note" to "independent public"), listOf(mapOf("row_ref" to "file_a:r1", "action" to action)))
            assertEquals("independent public", display(edited)["note"])
            val receipt = commit(f, edited); assertEquals(JsonPrimitive(true), receipt.getValue("commit_outcome").jsonObject["has_business_writes"])
            assertNotEquals("no_changes", receipt.apiText("status")); assertEquals(1, receipt.getValue("summary").jsonObject.apiNumber("updated_entries"))
            assertEquals("independent public", note(f, 1003)); assertNotEquals("independent public", sourceNote(f, 1003))
        }
    }

    @Test fun unknownOrChangedEntryReferenceIsRejectedWithoutPublishingPlan() {
        val f = fixture("unknown"); val plan = plan(f)
        assertFailsWith<CashImportPlanError> { f.service.preview(f.token, plan["session_id"] as String, (plan["session_revision"] as Number).toLong(),
            plan["plan_id"] as String, mapOf("entry_choices" to listOf(mapOf("edit_ref" to "invented", "patch" to mapOf("note" to "unsafe"))))) }
        assertEquals(plan["plan_id"], f.service.plan(f.token, plan["plan_id"] as String)["plan_id"])
    }

    @Test fun targetDependencyFailureRollsBackTheWholeCommand() {
        val f = fixture("dependency"); val edited = edit(f, plan(f), mapOf("note" to "must not partially apply"))
        atomic(f, edited, "row_write")
        assertEquals("retry_original", f.commands.resolve(f.token, edited["plan_id"] as String, "synthetic-confirm").apiText("status"))
        assertNoAlipay(f)
    }

    private fun assertNoAlipay(f: CashImportOriginalApiFixture) = assertEquals(0, f.database.read {
        it.jooq(f.database.sqlite).fetchCount(CASH_TRANSACTIONS, CASH_TRANSACTIONS.SOURCE_TYPE.eq("alipay"))
    })
    private fun atomic(f: CashImportOriginalApiFixture, plan: Map<String, Any?>, stage: String) {
        val before = f.snapshot(); var reached = 0
        val commands = CashImportCommandService(f.database, f.store, now = { f.now }, afterStep = {
            if (it == stage) { reached++; error("synthetic $stage failure") }
        })
        val failure = assertFailsWith<CashImportCommitFailure> { commit(f, plan, commands) }
        assertEquals("synthetic $stage failure", assertIs<IllegalStateException>(failure.failure).message)
        assertEquals(1, reached); assertEquals("not_committed", failure.commitState); assertEquals(before, f.snapshot())
    }

    @Test fun allDuplicatesWithoutPreviewItemDoNotInventEditTargets() {
        val f = fixture("duplicate"); val plan = plan(f); assertTrue(items(plan).isEmpty())
        val frozen = f.store.getPlanSnapshot(CashImportPlanViews.sessionId(plan["plan_id"] as String), f.workspace, f.user).plan!!
        assertEquals(emptyMap<String, Any?>(), frozen["entry_targets"]); assertEquals("no_changes", commit(f, plan).apiText("status"))
    }

    @Test fun changedExistingEntryCannotReuseOldEditTarget() {
        val f = fixture("changed"); val plan = plan(f)
        f.database.write { connection ->
            val context = CashSqlContext(connection, f.workspace, f.database.sqlite)
            val entry = context.entries.current(1003)!!
            context.entries.patch(1003, mapOf("note" to "another saved value"), entry.revision)
            CashProjectionService.maintainInSession(context, setOf(1003L))
        }
        assertFailsWith<CashImportPlanError> { edit(f, plan, mapOf("note" to "stale form")) }
        assertEquals("another saved value", note(f, 1003))
    }

    @Test fun fileChangeClearsEntryChoicesWithoutRecoveringOldTarget() {
        val f = fixture("remove"); val edited = edit(f, plan(f), mapOf("note" to "temporary public"))
        f.service.removeFile(f.token, edited["session_id"] as String, "file_a", (edited["session_revision"] as Number).toLong())
        val current = f.store.get(edited["session_id"] as String, f.workspace, f.user)
        assertNull(current.plan); assertTrue((current.previewInput["entry_choices"] as? List<*>).isNullOrEmpty())
    }

    @Test fun invalidPublicCategoryFailsInPreview() {
        for (category in listOf("missing-category", "other-category")) {
            val f = fixture(if (category == "missing-category") "category-missing" else "category-other"); val plan = plan(f)
            assertEquals("category_unavailable", assertFailsWith<CashImportPlanError> { edit(f, plan, mapOf("category_id" to category)) }.code)
        }
    }

    @Test fun pendingRelationSaveFailureRejectsEditedSingleEntry() {
        val f = fixture("relation"); val plan = plan(f)
        val frozen = f.store.getPlanSnapshot(CashImportPlanViews.sessionId(plan["plan_id"] as String), f.workspace, f.user).plan!!
        assertTrue((frozen["relation_groups"] as List<*>).any { (it as Map<*, *>)["action"] == "defer" })
        val edited = edit(f, plan, mapOf("note" to "must not partially apply")); atomic(f, edited, "relation_save"); assertNoAlipay(f)
    }

    @Test fun httpEditTargetIsPublicAndEntryPatchRoundtrips() = testApplication {
        val f = fixture("http-edit"); application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val plan = f.plan(client, view)
        val path = "/api/v1/cash-import/plans/${plan.apiText("plan_id")}/items/${plan.apiArray("items").first().jsonObject.apiText("preview_item_id")}"
        val detail = f.get(client, path).apiBody(); val ref = detail.getValue("entry").jsonObject.apiText("edit_ref"); assertTrue(ref.startsWith("ie_"))
        fun input(patch: JsonObject) = buildJsonObject { put("previous_plan_id", plan.getValue("plan_id")); put("entry_choices", JsonArray(listOf(buildJsonObject { put("edit_ref", ref); put("patch", patch) }))) }
        val revised = JsonObject(view + ("session_revision" to plan.getValue("session_revision")))
        val invalid = f.preview(client, revised, input(buildJsonObject { put("category_id", "missing-category") }))
        assertEquals(422, invalid.status.value); assertEquals("category_unavailable", invalid.apiBody().apiError())
        val patch = buildJsonObject { put("note", ""); put("category_id", JsonNull) }
        val updated = f.preview(client, revised, input(patch)); assertEquals(200, updated.status.value, updated.bodyAsText())
        val edited = updated.apiBody(); assertEquals("", edited.apiArray("items").first().jsonObject.getValue("display").jsonObject.apiText("note"))
        val restored = f.get(client, "/api/v1/cash-import/sessions/${plan.apiText("session_id")}").apiBody()
        assertEquals(patch, restored.getValue("last_preview_input").jsonObject.apiArray("entry_choices").first().jsonObject["patch"])
        val receipt = f.commit(client, edited); assertEquals(200, receipt.status.value); assertEquals(1, receipt.apiBody().getValue("summary").jsonObject.apiNumber("updated_entries"))
    }

    @Test fun httpCandidateMembersIncludeOtherPreviewItemsWithoutEditPermissions() = testApplication {
        val f = fixture("http-candidates"); application { financeModule(f.database, cashImports = f.service) }
        val view = f.upload(client); val response = f.preview(client, view); assertEquals(200, response.status.value, response.bodyAsText()); val plan = response.apiBody()
        val item = plan.apiArray("items").first { it.jsonObject.apiText("confirmation_status") == "pending" }.jsonObject
        val path = "/api/v1/cash-import/plans/${plan.apiText("plan_id")}/items/${item.apiText("preview_item_id")}"
        val details = f.get(client, path); assertEquals(200, details.status.value); val detail = details.apiBody(); val members = detail.apiArray("relation_members").map { it.jsonObject }
        assertEquals(3, members.size); assertTrue(members.all { it.apiArray("editable_fields").isEmpty() && it.apiArray("allowed_actions").isEmpty() })
        assertEquals(3, members.map { it.apiText("row_ref") }.toSet().size)
        assertTrue(members.all { it.getValue("fields").jsonObject.apiText("amount") == "-12.5" }); assertFalse("fields_ref" in details.bodyAsText())
        assertEquals(detail, f.get(client, path).apiBody())
    }
}
