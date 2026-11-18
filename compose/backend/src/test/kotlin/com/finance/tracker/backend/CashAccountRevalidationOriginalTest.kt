package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.localNormalizedCashDocument
import com.finance.tracker.business.domain.normalizedCashDocumentWire
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashAccountRevalidationOriginalTest {
    private fun fixture(name: String) = CashImportOriginalDataFixture(name, "FT_KOTLIN_ACCOUNT_REVALIDATION_DIR")
    private fun input(name: String) = Json.parseToJsonElement(Path.of(requireNotNull(System.getenv("FT_KOTLIN_ACCOUNT_REVALIDATION_DIR")), name, "api-input.json").readText()).jsonObject
    private data class Prepared(val plan: Map<String, Any?>, val request: JsonObject)
    private fun preview(f: CashImportOriginalDataFixture, files: List<ParsedImportFile>, input: JsonObject): Prepared {
        val before = f.snapshot()
        val source = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val normalized = files.map { file -> file.copy(document = file.document?.let { document ->
            localNormalizedCashDocument(normalizedCashDocumentWire(document) { sourceJson(it).jsonObject })
        }) }
        val (plan, changes) = calculateLocalCashImportBusinessPlan(normalized, source,
            JsonObject(input.filterKeys { it in setOf("account_selections", "row_choices") }),
            requireActive = {}, now = { kotlin.time.Instant.parse(f.now.toString()) })
        assertEquals(before, f.snapshot(), "Local calculation must not publish facts or defaults")
        assertEquals(true, plan["can_commit"])
        return Prepared(plan, Json.encodeToJsonElement(CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, changes)).jsonObject)
    }
    private fun commit(f: CashImportOriginalDataFixture, plan: Prepared, key: String = "synthetic-confirm") =
        CashImportWriteSetSubmissionService(f.database, now = { f.now }).submit(f.token, f.workspace, plan.request, key)
    private fun results(f: CashImportOriginalDataFixture, receipt: JsonObject) =
        ReceiptReadService(f.database, { f.now }).results(f.token, receipt.apiText("receipt_id"), "row", ReceiptPage(0, 200)).apiArray("items")
    private fun change(f: CashImportOriginalDataFixture, kind: String) = f.database.write { connection ->
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
        connection.markLedgerChanged(f.workspace)
    }

    @Test fun harmlessAccountChangesPreserveCurrentValidImport() {
        repeat(24) { index ->
            val name = "harmless-$index"; val f = fixture(name); val input = input(name)
            val flow = input.apiText("flow"); val phase = input.apiText("phase"); var draftId: String? = null
            if (flow == "finalize") {
                val plan = preview(f, f.files("initial"), buildJsonObject {
                    put("account_selections", JsonArray(emptyList())); put("row_choices", JsonArray(emptyList()))
                })
                draftId = results(f, commit(f, plan, "synthetic-incomplete")).first().jsonObject.apiText("transaction_id")
            }
            if (phase == "before_preview") change(f, input.apiText("change"))
            var plan = preview(f, f.files("default"), input)
            assertEquals(1, (plan.plan["summary"] as Map<*, *>)[if (flow == "finalize") "finalize_draft" else "create_posted"])
            if (phase == "after_preview") {
                change(f, input.apiText("change")); val changed = f.snapshot()
                assertFailsWith<LedgerBaseChanged> { commit(f, plan) }
                assertEquals(changed, f.snapshot())
                plan = preview(f, f.files("default"), input)
                assertEquals(1, (plan.plan["summary"] as Map<*, *>)[if (flow == "finalize") "finalize_draft" else "create_posted"])
            }
            val receipt = commit(f, plan); val summary = receipt.getValue("summary").jsonObject
            assertEquals(1, summary.apiNumber(if (flow == "finalize") "finalized_draft" else "created_posted"))
            val outcome = receipt.getValue("commit_outcome").jsonObject
            assertEquals(JsonPrimitive(false), outcome.getValue("has_new_failures"))
            assertEquals(JsonPrimitive(true), outcome.getValue("has_business_writes"))
            assertEquals(JsonArray(emptyList()), outcome.getValue("reason_codes"))
            val result = results(f, receipt).single().jsonObject
            val id = result.apiText("transaction_id"); if (draftId != null) assertEquals(draftId, id)
            f.database.read { connection ->
                val t = CASH_TRANSACTIONS; val row = connection.jooq(f.database.sqlite).select(t.STATE, t.ACCOUNT_ID).from(t).where(t.ID.eq(id.toLong())).fetchSingle()
                assertEquals("posted", row[t.STATE]); assertEquals(5001L, row[t.ACCOUNT_ID])
            }
        }
    }

    private fun reject(name: String) {
        val f = fixture(name); val input = input(name)
        val plan = preview(f, f.files("default"), input); assertEquals(2, (plan.plan["summary"] as Map<*, *>)["create_posted"])
        change(f, input.apiText("change")); val before = f.snapshot(); val count = f.count()
        val rejected = assertFailsWith<LedgerBaseChanged> { commit(f, plan) }
        assertEquals(rejected.expected + 1L, rejected.current)
        assertEquals(count, f.count()); assertEquals(before, f.snapshot())
        val current = LedgerReadService(f.database, now = { f.now }).snapshot(f.token).ledgerSeq
        val boundaryRequest = JsonObject(plan.request + ("base_ledger_seq" to JsonPrimitive(current)))
        assertEquals(plan.request.getValue("changes"), boundaryRequest.getValue("changes"))
        val componentIndex = if (name == "manual") 1 else 0
        val transaction = boundaryRequest.getValue("changes").jsonObject.getValue("transactions").jsonArray[0].jsonObject
        assertEquals("5001", transaction.getValue("components").jsonArray[componentIndex].jsonObject.apiText("account_id"))
        if (name == "manual") assertEquals(JsonNull, transaction.getValue("values").jsonObject.getValue("account_id"))
        val ineligible = assertFailsWith<CashWriteSetSubmissionFailure> {
            CashImportWriteSetSubmissionService(f.database, now = { f.now }).submit(f.token, f.workspace, boundaryRequest, "synthetic-confirm")
        }
        assertEquals(when (input.apiText("change")) {
            "delete" -> "reference_changed"
            "remove_currency" -> "account_currency_missing"
            else -> "account_unavailable"
        }, ineligible.code)
        assertEquals(listOf(if (name == "lost-delete") "transactions[0].values.account_id"
            else "transactions[0].components[$componentIndex].account_id"), ineligible.fields)
        assertEquals("not_committed", ineligible.commitState)
        assertEquals(count, f.count()); assertEquals(before, f.snapshot())
        assertEquals("receipt_not_found", assertFailsWith<ReceiptFailure> { ReceiptReadService(f.database, { f.now }).receiptByKey(f.token, "synthetic-confirm") }.code)
        assertEquals(before, f.snapshot())
    }

    @Test fun lostAccountEligibilityRejectsEntireCommit() {
        for (change in listOf("disable", "remove_currency", "delete")) reject("lost-$change")
    }

    @Test fun changedAccountInFrozenManualPatchRefusesCommitBeforeWrites() = reject("manual")
}
