package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*

@org.junit.experimental.categories.Category(HistoricalMigrationEvidence::class)
class CashImportLocalPlanOriginalTest {
    private fun fixture(name: String) = CashImportOriginalDataFixture(name, "FT_KOTLIN_LOCAL_PLAN_ORIGINAL_DIR")
    private data class Prepared(val source: LedgerSnapshotDto, val files: List<ParsedImportFile>, val input: JsonObject,
        val plan: JsonObject, val request: CashWriteSetRequestDto)
    private fun prepare(f: CashImportOriginalDataFixture, variant: String = "default", accounts: Boolean = true,
        input: JsonObject? = null): Prepared {
        val before = f.snapshot()
        val source = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val files = f.files(variant).map { file -> file.copy(document = file.document?.let { document ->
            localNormalizedCashDocument(normalizedCashDocumentWire(document) { sourceJson(it).jsonObject })
        }) }
        val choices = input ?: buildJsonObject {
            put("account_selections", buildJsonArray {
                if (accounts) for (group in scanLocalCashFunding(files).groups) add(buildJsonObject {
                    put("source_account_ref", group.sourceAccountRef); put("account_id", "101"); put("expected_account_revision", 1)
                })
            })
            for (key in listOf("row_choices", "relation_choices", "entry_choices")) put(key, JsonArray(emptyList()))
        }
        val (plan, changes) = calculateLocalCashImportBusinessPlan(files, source, choices,
            requireActive = {}, now = { kotlin.time.Instant.parse(f.now.toString()) })
        assertEquals(before, f.snapshot())
        val frozen = freezeLocalCashImportPreviewPlan(plan)
        assertEquals(JsonPrimitive(true), frozen["can_commit"])
        return Prepared(source, files, choices, frozen, CashWriteSetRequestDto(source.ledgerSeq, CASH_RULES_VERSION, changes))
    }
    private fun commit(f: CashImportOriginalDataFixture, prepared: Prepared, key: String): JsonObject =
        CashImportWriteSetSubmissionService(f.database, now = { f.now }).submit(f.token, f.workspace,
            Json.encodeToJsonElement(prepared.request).jsonObject, key)
    private fun summary(value: JsonObject, key: String) = value.getValue("summary").jsonObject.getValue(key).jsonPrimitive.int
    private fun rows(f: CashImportOriginalDataFixture, receipt: JsonObject, kind: String): JsonArray {
        val page = ReceiptReadService(f.database, { f.now }).results(f.token, receipt.apiText("receipt_id"), kind, ReceiptPage(0, 200), f.workspace)
        assertEquals(JsonNull, page["next_after"])
        return page.getValue("items").jsonArray
    }
    private fun capture(f: CashImportOriginalDataFixture, name: String, prepared: Prepared, receipt: JsonObject) {
        val directory = System.getenv("FT_LOCAL_PLAN_CAPTURE_DIR")?.let(Path::of) ?: return
        require(directory.isAbsolute); Files.createDirectories(directory)
        Files.writeString(directory.resolve("$name.json"), buildJsonObject {
            put("plan", prepared.plan); put("request", Json.encodeToJsonElement(prepared.request)); put("receipt", receipt)
            put("server_items", JsonArray(rows(f, receipt, "row") + rows(f, receipt, "relation")))
        }.toString())
    }
    @Test fun fullRefundZeroRemainsReviewableAndHasOneCurrentResult() {
        val f = fixture("refund"); val prepared = prepare(f)
        val item = prepared.plan.getValue("items").jsonArray.single().jsonObject
        assertEquals(0, item.getValue("display").jsonObject.apiText("amount").toBigDecimal().compareTo(java.math.BigDecimal.ZERO))
        assertEquals(JsonPrimitive(true), item["detail_available"])
        val reader = LocalCashImportPlanReader(prepared.plan) { _, _ -> error("No monthly query in the detail check") }
        assertEquals(4, reader.detail(item.apiText("preview_item_id")).getValue("members").jsonArray.size)
        val receipt = commit(f, prepared, "original-refund")
        assertEquals(4, summary(receipt, "created_posted")); assertEquals(1, summary(receipt, "relations_confirmed"))
        val actual = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val created = actual.facts.cashTransactions.map { it.id }.toSet() - prepared.source.facts.cashTransactions.map { it.id }.toSet()
        val savedRows = rows(f, receipt, "row")
        assertEquals(4, created.size); assertEquals(4, savedRows.size)
        assertEquals(created, savedRows.map { it.jsonObject.apiText("transaction_id") }.toSet())
        assertEquals(created, Json.decodeFromJsonElement<CashWriteSetResultDto>(receipt.getValue("write_set_result")).transactions.map { it.id }.toSet())
        val query = LocalCashLedgerQuery(actual) { _, _ -> error("No monthly query in the location check") }
        val refs = query.projectionRefs(created.toList())
        assertEquals(created, refs.map { it.transactionId }.toSet()); assertEquals(1, refs.map { it.projectionId }.toSet().size)
        val entryIds = actual.facts.cashEntryMembers.filter { it.transactionId in created }.map { it.entryId }.toSet()
        assertEquals(1, entryIds.size); assertEquals("cash-entry:${entryIds.single()}", refs.first().projectionId)
        assertTrue(actual.facts.cashEntries.any { it.id == entryIds.single() })
        assertEquals(created, query.currentGroup(refs.first().projectionId).members.map { it.transactionId }.toSet())
        capture(f, "refund", prepared, receipt)
    }
    @Test fun unselectedAccountsSaveDraftsAndInvalidRowPatchDoesNotPublish() {
        val f = fixture("draft"); val first = prepare(f, accounts = false)
        assertEquals(2, first.plan.getValue("items").jsonArray.size)
        assertTrue(first.plan.getValue("items").jsonArray.all { it.jsonObject["confirmation_status"] == JsonPrimitive("pending") })
        assertTrue(first.plan.getValue("items").jsonArray.all { it.jsonObject["statistics_eligible"] == JsonPrimitive(false) })
        val row = first.plan.getValue("items").jsonArray.first().jsonObject.getValue("member_refs").jsonArray.first().jsonObject.apiText("row_ref")
        val invalid = JsonObject(first.input + ("row_choices" to buildJsonArray { add(buildJsonObject {
            put("row_ref", row); put("action", "apply"); put("patch", buildJsonObject { put("manual_overrides", buildJsonObject { put("note", true) }) })
        }) }))
        val before = f.snapshot()
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { prepare(f, accounts = false, input = invalid) }.code)
        assertEquals(before, f.snapshot())
        val valid = prepare(f, accounts = false, input = first.input)
        assertEquals(first.plan, valid.plan); assertEquals(2, summary(valid.plan, "create_draft"))
        val receipt = commit(f, valid, "original-draft")
        assertEquals(2, summary(receipt, "saved_draft")); assertFalse("skipped" in receipt.getValue("summary").jsonObject)
        val actual = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val created = actual.facts.cashTransactions.filter { row -> first.source.facts.cashTransactions.none { it.id == row.id } }
        assertEquals(2, created.size); assertTrue(created.all { it.state == "draft" && it.accountId == null })
        capture(f, "draft", valid, receipt)
    }
    @Test fun duplicateOnlyCanConfirmAndFixedAmountConflictIsFailedWithoutBusinessWrites() {
        val f = fixture("duplicate"); val firstPlan = prepare(f); val first = commit(f, firstPlan, "original-first")
        assertEquals(1, summary(first, "created_posted")); capture(f, "duplicate-first", firstPlan, first)
        val before = LedgerReadService(f.database, now = { f.now }).snapshot(f.token)
        val duplicate = prepare(f)
        assertEquals(JsonArray(emptyList()), duplicate.plan["items"])
        assertEquals(listOf("duplicate"), duplicate.plan.getValue("row_decisions").jsonArray.map { it.jsonObject.apiText("planned_action") })
        val receipt = commit(f, duplicate, "original-duplicate")
        assertEquals("no_changes", receipt.apiText("status")); assertEquals(1, summary(receipt, "duplicate"))
        assertEquals(before, LedgerReadService(f.database, now = { f.now }).snapshot(f.token))
        capture(f, "duplicate-repeat", duplicate, receipt)
        val conflict = prepare(f, "conflict")
        assertEquals(JsonArray(emptyList()), conflict.plan["items"]); assertEquals(1, summary(conflict.plan, "skip"))
        val decision = conflict.plan.getValue("row_decisions").jsonArray.single().jsonObject
        assertEquals("skip", decision.apiText("planned_action")); assertEquals("fixed_field_conflict", decision.apiText("code"))
        val raw = commit(f, conflict, "original-conflict")
        assertEquals("no_changes", raw.apiText("status")); assertFalse("skipped" in raw.getValue("summary").jsonObject)
        val outcome = buildJsonObject { put("has_new_failures", false); put("has_business_writes", false); put("reason_codes", JsonArray(emptyList())) }
        assertEquals(outcome, receipt["commit_outcome"]); assertEquals(outcome, raw["commit_outcome"])
        assertEquals(before, LedgerReadService(f.database, now = { f.now }).snapshot(f.token))
        assertEquals(JsonArray(emptyList()), rows(f, raw, "row"))
        capture(f, "fixed-conflict", conflict, raw)
    }
    @Test fun fileStatusAndUnknownCountMatchTheActualPreparation() {
        val f = fixture("file_status"); val prepared = prepare(f)
        val shown = prepared.plan.getValue("file_results").jsonArray
        assertEquals(2, shown.size)
        val expected = prepared.files.map { sourceJson(cashImportFileView(it) { value -> value }).jsonObject }
        for (index in expected.indices) for (key in listOf("file_ref", "status", "recognized_row_count"))
            assertEquals(expected[index].getValue(key), shown[index].jsonObject.getValue(key), "$index $key")
        assertEquals(listOf("file_a", "file_b"), shown.map { it.jsonObject.apiText("file_ref") })
        assertEquals(listOf("ready", "failed"), shown.map { it.jsonObject.apiText("status") })
        assertEquals(JsonPrimitive(1), shown.first().jsonObject["recognized_row_count"])
        assertEquals(JsonNull, shown[1].jsonObject["recognized_row_count"])
    }
}
