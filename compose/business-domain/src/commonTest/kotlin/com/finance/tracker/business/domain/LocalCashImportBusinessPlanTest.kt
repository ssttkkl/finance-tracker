package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportBusinessPlanTest {
    fun source() = LedgerSnapshotDto("w", "0", CASH_RULES_VERSION,
        LedgerFactsDto(listOf(LedgerAccountFactDto("10", "现金", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0"))

    fun files(refund: Boolean = true, provider: Boolean = true): List<ParsedImportFile> {
        fun row(index: Int, amount: String, record: String, type: String) = ParsedCashRow(index, mapOf(
            "amount" to amount, "currency" to "CNY", "occurred_at" to "2026-08-${if (index == 1) "15" else "16"}T02:00:00Z",
            "record_type" to type, "record_subtype" to "not_applicable", "counterparty" to "商户", "note" to "edited-note",
            "source_payload" to mapOf("交易订单号" to record, "收/付款方式" to "支付宝余额", "商品说明" to "原始商品",
                "收/支" to if (index == 1) "支出" else "收入", "交易状态" to if (index == 1) "交易成功" else "退款成功")),
            paymentMethod = "支付宝余额", providerId = if (provider) record else null,
            sourceExplanation = if (provider) mapOf("txn_id" to record, "payment_method" to "支付宝余额",
                "platform_status" to if (index == 1) "交易成功" else "退款成功", "_alipay_direction" to if (index == 1) "支出" else "收入")
            else mapOf("source_display_name" to "建设银行"))
        val rows = listOf(row(1, "-10.00", "ORDER", "consumption")) +
            if (refund) listOf(row(2, "3.00", "ORDER_1", "refund")) else emptyList()
        return listOf(ParsedImportFile("f", "f".repeat(64), "parsed", ParsedCashDocument(if (provider) "alipay" else "ccb_debit", "csv", "p", rows,
            "owner", "支付宝", CashSourceCoverage(null, null, false, "", emptyMap(), "v"), rows.size, rows.size)))
    }

    fun input(files: List<ParsedImportFile>) = buildJsonObject {
        put("account_selections", encodeLocalCashValue(scanLocalCashFunding(files).groups.map {
            mapOf("source_account_ref" to it.sourceAccountRef, "account_id" to "10", "expected_account_revision" to SourceJsonInteger(1))
        }))
        put("row_choices", JsonArray(emptyList())); put("relation_choices", JsonArray(emptyList())); put("entry_choices", JsonArray(emptyList()))
    }

    private fun build(files: List<ParsedImportFile>, input: JsonObject = input(files), previous: Map<String, Any?>? = null,
        ids: Map<String, String> = emptyMap(), guard: () -> Unit = {}) = buildLocalCashImportBusinessPlan(
        files, source(), input, previous, ids, guard) { Instant.parse("2026-10-01T00:00:00Z") }

    @Test fun realFilePipelineDiscoversAndAppliesRefundWithoutInjectedProposals() {
        val files = files(); val input = input(files)
        val before = encodeLocalCashValue(files.single().document!!.rows.map { it.fields })
        val first = build(files, input)
        val group = (first.getValue("relation_groups") as List<*>).single() as Map<*, *>
        val edge = (group["edges"] as List<*>).single() as Map<*, *>
        assertEquals("refund_offset", edge["kind"])
        assertEquals(CashAmount.parse("3.00"), edge["applied_amount"])
        assertEquals(emptyList<Any?>(), first["relation_failures"])
        assertEquals(true, first["can_commit"])
        val frozen = freezeLocalCashImportPreviewPlan(first)
        assertEquals(JsonPrimitive(2), frozen.getValue("summary").jsonObject.getValue("create_posted"))
        val candidate = (group["candidates"] as List<*>).singleOrNull() as? Map<*, *>
        val choice = mapOf("suggestion_id" to group["suggestion_id"], "candidate_id" to candidate?.get("candidate_id"),
            "action" to "accept", "common_fields" to mapOf("note" to "checked-refund"))
        val selected = build(files, JsonObject(input + ("relation_choices" to encodeLocalCashValue(listOf(choice)))), first)
        assertEquals(emptyList<Any?>(), selected["invalidated_choices"])
        assertTrue((selected["items"] as List<*>).any { ((it as Map<*, *>)["display"] as Map<*, *>)["note"] == "checked-refund" })
        assertEquals(frozen, freezeLocalCashImportPreviewPlan(first))
        assertEquals(before, encodeLocalCashValue(files.single().document!!.rows.map { it.fields }))
        assertEquals(frozen, freezeLocalCashImportPreviewPlan(build(files)))
    }

    @Test fun completePlanRetainsTypedFieldsAndAppliesEntryChoices() {
        val files = files(refund = false); val first = build(files)
        val edit = (first["entry_targets"] as Map<*, *>).keys.single() as String
        val selected = build(files, JsonObject(input(files) + ("entry_choices" to encodeLocalCashValue(listOf(
            mapOf("edit_ref" to edit, "patch" to mapOf("note" to "checked-entry")))))), first)
        val display = ((selected["items"] as List<*>).single() as Map<*, *>)["display"] as Map<*, *>
        assertEquals("checked-entry", display["note"])
        assertTrue(display["amount"] is CashAmount)
        assertTrue(display["occurred_at"] is Instant)
        assertEquals(emptyList<Any?>(), selected["invalidated_choices"])
    }

    @Test fun draftRowsDoNotBecomeDiscoverySeeds() {
        val files = files(); val original = input(files)
        val input = JsonObject(original + ("row_choices" to encodeLocalCashValue(listOf(mapOf("row_ref" to "f:r2", "action" to "defer")))))
        val plan = build(files, input)
        assertEquals(emptyList<Any?>(), plan["relation_groups"])
        val rows = plan["row_decisions"] as List<*>
        assertEquals(listOf("create_posted", "create_draft"), rows.map { (it as Map<*, *>)["planned_action"] })
    }

    @Test fun unboundRecordIdsSurvivePreviousPlanAndExplicitRecovery() {
        val files = files(refund = false, provider = false)
        val first = build(files)
        fun record(plan: Map<String, Any?>) = ((plan["operations"] as List<*>).single() as Map<*, *>)["values"] as Map<*, *>
        val id = record(first)["record_id"] as String
        assertEquals(id, record(build(files, previous = first))["record_id"])
        val occurrence = ((first["operations"] as List<*>).single() as Map<*, *>)["occurrence_key"] as String
        assertEquals(id, record(build(files, ids = mapOf(occurrence to id)))["record_id"])
        assertEquals(freezeLocalCashImportPreviewPlan(first), freezeLocalCashImportPreviewPlan(build(files, previous = first)))
    }

    @Test fun inactiveScopeStopsBeforeReadingInvalidInput() {
        val failure = IllegalStateException("inactive")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            build(emptyList(), buildJsonObject { put("account_selections", "invalid") }, guard = { throw failure })
        })
    }

    @Test fun actualFilePlanBuildsTheCompleteWriteSetAndRecalculatesTheConfirmedReview() {
        val source = source(); val files = files(); val input = input(files)
        fun calculate(snapshot: LedgerSnapshotDto, input: JsonObject, previous: Map<String, Any?>?, ids: Map<String, String>) =
            buildLocalCashImportBusinessPlan(files, snapshot, input, previous, ids, {}) {
                Instant.parse("2026-10-01T00:00:00Z")
            }.let { plan -> plan to buildLocalCashImportWriteSet(snapshot, files, prepareLocalCashImportSources(files, snapshot),
                freezeLocalCashImportPreviewPlan(plan), input.getValue("row_choices").jsonArray, input.getValue("relation_choices").jsonArray) }
        val first = calculate(source, input, null, emptyMap())
        assertEquals(2, first.second.transactions.size)
        assertEquals(1, first.second.relations.size)
        val review = LocalCashImportReview.freeze(input, first.first, source, first.second)
        val recovered = LocalCashImportReview.decode(review.encode()).recalculate(source.copy(ledgerSeq = "1")) { fresh, choices, previous, ids ->
            calculate(fresh, choices, previous, ids)
        }
        assertTrue(recovered.conclusionUnchanged)
        assertEquals(first.second, recovered.changes)
        assertEquals(freezeLocalCashImportPreviewPlan(first.first), freezeLocalCashImportPreviewPlan(recovered.plan))
    }
}
