package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

class CashAccountRevalidationOriginalTest {
    private fun fixture(name: String) = CashImportOriginalApiFixture(name, "FT_KOTLIN_ACCOUNT_REVALIDATION_DIR")
    private fun input(name: String) = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_ACCOUNT_REVALIDATION_DIR")), name, "api-input.json").readText()).jsonObject
    private fun preview(f: CashImportOriginalApiFixture, sessionId: String, input: JsonObject): Map<String, Any?> =
        f.service.preview(f.token, sessionId, 1, null, mapOf("account_selections" to cashSourceValue(input.getValue("account_selections")),
            "row_choices" to cashSourceValue(input.getValue("row_choices"))))
    private fun commit(f: CashImportOriginalApiFixture, plan: Map<String, Any?>, key: String = "synthetic-confirm") =
        f.commands.commit(f.token, plan["plan_id"] as String, (plan["session_revision"] as Number).toLong(), key)
    private fun results(f: CashImportOriginalApiFixture, receipt: JsonObject) =
        ReceiptReadService(f.database, { f.now }).results(f.token, receipt.apiText("receipt_id"), "row", ReceiptPage(0, 200)).apiArray("items")
    private fun change(f: CashImportOriginalApiFixture, kind: String) = f.database.write { connection ->
        val a = ACCOUNTS; val sql = connection.jooq(f.database.sqlite)
        val condition = a.ID.eq(5001L).and(a.WORKSPACE_ID.eq(f.workspace))
        if (kind == "delete") assertEquals(1, sql.deleteFrom(a).where(condition).execute())
        else {
            val update = sql.update(a).set(a.REVISION, a.REVISION.plus(1L))
            when (kind) {
                "rename" -> update.set(a.NAME, "合成账户新名称")
                "revision" -> Unit
                "other_currency" -> update.set(a.CURRENCIES, JsonArray(listOf("CNY", "EUR", "USD").map(::JsonPrimitive)))
                "remove_other_currency" -> update.set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("CNY"))))
                "disable" -> update.set(a.ACTIVE, false)
                "remove_currency" -> update.set(a.CURRENCIES, JsonArray(listOf(JsonPrimitive("USD"))))
                else -> error(kind)
            }
            assertEquals(1, update.where(condition).execute())
            assertEquals(2L, sql.select(a.REVISION).from(a).where(condition).fetchSingle(a.REVISION))
        }
    }

    @Test fun harmlessAccountChangesPreserveCurrentValidImport() {
        repeat(24) { index ->
            val name = "harmless-$index"; val f = fixture(name); val input = input(name)
            val flow = input.apiText("flow"); val phase = input.apiText("phase"); var draftId: String? = null
            if (flow == "finalize") {
                val initial = f.store.create(f.workspace, f.user, f.files("initial"))
                val plan = f.service.preview(f.token, initial.sessionId, 1, null, mapOf("account_selections" to emptyList<Any>(), "row_choices" to emptyList<Any>()))
                draftId = results(f, commit(f, plan, "synthetic-incomplete")).first().jsonObject.apiText("transaction_id")
            }
            val staged = f.store.create(f.workspace, f.user, f.files("default"))
            if (phase == "before_preview") change(f, input.apiText("change"))
            val plan = preview(f, staged.sessionId, input)
            assertEquals(1, (plan["summary"] as Map<*, *>)[if (flow == "finalize") "finalize_draft" else "create_posted"])
            if (phase == "after_preview") change(f, input.apiText("change"))
            val receipt = commit(f, plan); val summary = receipt.getValue("summary").jsonObject
            assertEquals(1, summary.apiNumber(if (flow == "finalize") "finalized_draft" else "created_posted")); assertEquals(0, summary.apiNumber("skipped"))
            val result = results(f, receipt).single().jsonObject
            val id = result.apiText("transaction_id"); if (draftId != null) assertEquals(draftId, id)
            f.database.read { connection ->
                val t = CASH_TRANSACTIONS; val row = connection.jooq(f.database.sqlite).select(t.STATE, t.ACCOUNT_ID).from(t).where(t.ID.eq(id.toLong())).fetchSingle()
                assertEquals("posted", row[t.STATE]); assertEquals(5001L, row[t.ACCOUNT_ID])
            }
        }
    }

    private fun reject(name: String) {
        val f = fixture(name); val input = input(name); val staged = f.store.create(f.workspace, f.user, f.files("default"))
        val plan = preview(f, staged.sessionId, input); assertEquals(2, (plan["summary"] as Map<*, *>)["create_posted"])
        change(f, input.apiText("change")); val before = f.snapshot(); val count = f.count()
        val rejected = assertFailsWith<CashImportPlanError> { commit(f, plan) }
        assertEquals("plan_changed", rejected.code); assertEquals("not_committed", rejected.commitState)
        assertEquals(count, f.count()); assertEquals(before, f.snapshot())
        assertEquals("receipt_not_found", assertFailsWith<ReceiptFailure> { ReceiptReadService(f.database, { f.now }).receiptByKey(f.token, "synthetic-confirm") }.code)
        assertEquals(before, f.snapshot())
    }

    @Test fun lostAccountEligibilityRejectsEntireCommit() {
        for (change in listOf("disable", "remove_currency", "delete")) reject("lost-$change")
    }

    @Test fun changedAccountInFrozenManualPatchRefusesCommitBeforeWrites() = reject("manual")
}
