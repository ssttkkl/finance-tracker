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

    @Test fun choosingTheFinalAccountDirectlyOrAfterAnotherAccountHasTheSameBusinessResult() {
        val initial = source()
        val source = initial.copy(facts = initial.facts.copy(accounts = initial.facts.accounts +
            initial.facts.accounts.single().copy(id = "20", name = "Second cash account")))
        val original = files().single()
        val document = checkNotNull(original.document)
        val files = listOf(original, original.copy(fileRef = "repeat", digest = "a".repeat(64),
            document = document.copy(rows = document.rows.take(1), observedCount = 1, declaredCount = 1)))
        fun account(id: String): JsonObject {
            val choices = input(files)
            val mappings = choices.getValue("account_selections").jsonArray.map { choice ->
                JsonObject(choice.jsonObject + ("account_id" to JsonPrimitive(id)))
            }
            return JsonObject(choices + ("account_selections" to JsonArray(mappings)))
        }
        val clock = Instant.parse("2026-10-01T00:00:00Z")
        val final = account("20")
        fun prepare(choices: JsonObject, previous: LocalCashImportReview? = null) = prepareLocalCashImportReview(
            files, source, choices, previous?.previousPlan(), previous?.recordIds().orEmpty(),
            includePreviewContext = false, requireActive = {}, progress = {}, now = { clock })
        val firstA = prepare(account("10"))
        val throughA = firstA.previousReviewFor(final)
        val previousCli = LocalCashImportReview.decode(firstA.confirmedReview().encode())
        val direct = prepare(final)
        val changed = prepare(final, throughA)
        val changedFromStorage = prepare(final, previousCli)

        for (review in listOf(direct, changed, changedFromStorage)) {
            assertTrue(review.canCommit)
            assertEquals(JsonPrimitive(2), review.plan.getValue("summary").jsonObject.getValue("create_posted"))
            assertEquals(JsonPrimitive(1), review.plan.getValue("summary").jsonObject.getValue("duplicate"))
            assertEquals(setOf(CashAmount.parse("-10.00"), CashAmount.parse("3.00")),
                review.changes.transactions.map { CashAmount.parse(checkNotNull(it.values.amount)) }.toSet())
            assertTrue(review.changes.transactions.all { it.values.accountId == "20" && it.values.currency == "CNY" &&
                it.components.all { component -> component.accountId == "20" && component.currency == "CNY" } })
            val relation = review.changes.relations.single()
            val amounts = review.changes.transactions.associate { it.target to CashAmount.parse(checkNotNull(it.values.amount)) }
            assertEquals("refund_offset", relation.kind)
            assertEquals(CashAmount.parse("3.00"), CashAmount.parse(relation.appliedAmount))
            assertEquals(CashAmount.parse("-10.00"), amounts.getValue(relation.primaryTransaction))
            assertEquals(CashAmount.parse("3.00"), amounts.getValue(checkNotNull(relation.secondaryTransaction)))
        }
        fun rowDecisions(review: PreparedCashImportReview) = review.plan.getValue("row_decisions").jsonArray
            .associate { item -> item.jsonObject.let { it.getValue("row_ref") to it.getValue("planned_action") } }
        assertEquals(rowDecisions(direct), rowDecisions(changed))
        assertEquals(rowDecisions(direct), rowDecisions(changedFromStorage))
        assertEquals(direct.plan.getValue("summary"), changed.plan.getValue("summary"))
        val freshReferences = direct.confirmedReview().recordIds()
        val retainedReferences = changed.confirmedReview().recordIds()
        assertEquals(throughA.recordIds(), retainedReferences)
        println("account-only: referencesEqual=${freshReferences == retainedReferences}, " +
            "writeSetEqual=${direct.changes == changed.changes}, previewEqual=${direct.plan == changed.plan}, " +
            "referencedRows=${retainedReferences.size}")
    }

    @Test fun completedPlanOwnsSourceMetadataAfterItsInputIsChanged() {
        val file = files(refund = false).single()
        val document = checkNotNull(file.document)
        val row = document.rows.single()
        val fields = checkNotNull(row.fields)
        val payload = (fields.getValue("source_payload") as Map<*, *>).entries
            .associate { (key, value) -> checkNotNull(key as? String) to value }.toMutableMap()
        val explanation = row.sourceExplanation.toMutableMap()
        val input = row.copy(fields = fields + ("source_payload" to payload), sourceExplanation = explanation)
        val plan = build(listOf(file.copy(document = document.copy(rows = listOf(input)))))
        val original = freezeLocalCashImportPreviewPlan(plan)
        payload["商品说明"] = "new source description"
        explanation["payment_method"] = "new payment method"
        assertEquals(original, freezeLocalCashImportPreviewPlan(plan))
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

    @Test fun fileCollectionChangesRestoreUnaffectedChoicesAndUnboundIdsButInvalidateRemovedMembers() {
        val document = files().single().document!!
        val related = document.rows.mapIndexed { index, row -> ParsedImportFile("file_${if (index == 0) "a" else "b"}",
            (if (index == 0) "a" else "b").repeat(64), "parsed", document.copy(rows = listOf(row), declaredCount = 1, observedCount = 1)) }
        val unbound = files(refund = false, provider = false).single().let { file ->
            val bank = checkNotNull(file.document)
            file.copy(fileRef = "unbound", digest = "d".repeat(64), document = bank.copy(rows = bank.rows.map { row ->
                row.copy(fields = row.fields!! + mapOf("amount" to "-99.00", "counterparty" to "Unrelated bank merchant"))
            }))
        }
        fun extra(ref: String, provider: String) = files(refund = false).single().let { file -> file.copy(fileRef = ref,
            digest = (if (provider == "EXTRA") "c" else "e").repeat(64), document = file.document!!.copy(
                sourceAccountIdentifier = "unrelated-owner", rows = file.document.rows.map { row -> row.copy(providerId = provider,
                    fields = row.fields!! + mapOf("counterparty" to "Other merchant", "source_payload" to mapOf("交易订单号" to provider)),
                    sourceExplanation = row.sourceExplanation + ("txn_id" to provider)) })) }
        val original = related + unbound + extra("file_c", "EXTRA")
        fun ids(plan: Map<String, Any?>): Map<String, String> = LocalCashImportReview.freeze(input(original), plan).recordIds()
        for (action in listOf("add", "replace", "remove", "continuous")) for (decision in listOf("defer", "ignore")) {
            val first = build(original)
            val group = (first.getValue("relation_groups") as List<*>).single() as Map<*, *>
            val choices = encodeLocalCashValue(listOf(mapOf("suggestion_id" to group["suggestion_id"], "action" to decision)))
            val selectedInput = JsonObject(input(original) + ("relation_choices" to choices))
            val selected = build(original, selectedInput, first)
            val originalIds = ids(selected).filterKeys { key ->
                (selected.getValue("operations") as List<*>).any { raw -> val operation = raw as Map<*, *>
                    operation["occurrence_key"] == key && (operation["row_refs"] as List<*>).any { (it as String).startsWith("unbound:") } }
            }
            assertEquals(1, originalIds.size)
            var review = LocalCashImportReview.decode(LocalCashImportReview.freeze(selectedInput, selected).encode())
            var currentFiles = original
            for (step in if (action == "continuous") listOf("add", "remove") else listOf(action)) {
                currentFiles = when (step) {
                    "add" -> currentFiles + extra("added", "ADDED")
                    "replace" -> currentFiles.map { if (it.fileRef == "file_c") extra("file_c", "REPLACEMENT") else it }
                    else -> currentFiles.filter { it.fileRef != "file_c" }
                }
                review = LocalCashImportReview.decode(review.afterFilesChanged(if (step == "add") emptySet() else setOf("file_c")).encode())
                val nextInput = JsonObject(input(currentFiles) + ("relation_choices" to review.inputJson().getValue("relation_choices")))
                val next = build(currentFiles, nextInput, review.previousPlan(), review.recordIds())
                val retained = (next.getValue("relation_groups") as List<*>).single() as Map<*, *>
                assertEquals(decision, retained["action"], "$action/$decision/$step")
                assertEquals(group["suggestion_id"], retained["suggestion_id"])
                assertEquals(emptyList<Any?>(), next["invalidated_choices"])
                assertEquals(originalIds, ids(next).filterKeys { it in originalIds })
                review = LocalCashImportReview.decode(LocalCashImportReview.freeze(nextInput, next).encode())
            }
        }
        val first = build(original)
        val group = (first.getValue("relation_groups") as List<*>).single() as Map<*, *>
        val choice = mapOf("suggestion_id" to group["suggestion_id"], "action" to "accept")
        val chosenInput = JsonObject(input(original) + ("relation_choices" to encodeLocalCashValue(listOf(choice))))
        val selected = build(original, chosenInput, first)
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(chosenInput, selected).afterFilesChanged(setOf("file_a")).encode())
        val remaining = original.filter { it.fileRef != "file_a" }
        val nextInput = JsonObject(input(remaining) + ("relation_choices" to saved.inputJson().getValue("relation_choices")))
        val next = build(remaining, nextInput, saved.previousPlan(), saved.recordIds())
        assertEquals(true, next["can_commit"])
        assertEquals(listOf(mapOf("kind" to "relation", "reference" to group["suggestion_id"], "code" to "relation_context_changed")), next["invalidated_choices"])
        assertTrue((next.getValue("relation_groups") as List<*>).all { (it as Map<*, *>)["action"] != "accept" })
        assertTrue((next.getValue("operations") as List<*>).isNotEmpty())
        val calculated = calculateLocalCashImportBusinessPlan(remaining, source(), nextInput, saved.previousPlan(), saved.recordIds(), {}) {
            Instant.parse("2026-10-01T00:00:00Z")
        }
        assertEquals(freezeLocalCashImportPreviewPlan(next), freezeLocalCashImportPreviewPlan(calculated.first))
        assertTrue(calculated.second.transactions.isNotEmpty())
        assertEquals((next.getValue("operations") as List<*>).size, calculated.second.transactions.size)
        val operations = (next.getValue("operations") as List<*>).map { it as Map<*, *> }
        assertTrue(operations.all { operation -> (operation["row_refs"] as List<*>).none { (it as String).startsWith("file_a:") } })
        assertEquals(operations.map { (it["values"] as Map<*, *>)["record_id"] }.toSet(),
            calculated.second.transactions.map { it.values.recordId }.toSet())
        assertTrue(calculated.second.relations.all { it.state != "confirmed" },
            calculated.second.relations.map { it.state to it.active }.toString())
        assertTrue(calculated.second.relationGroups.all { it.commonFields.isEmpty() })
        assertEquals(calculated.second.relations.map { it.target.reference }.toSet(),
            calculated.second.relationGroups.flatMap { it.relations }.toSet())
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

    @Test fun oneProductionCalculationBuildsPlanAndWritesAndIgnoresOnlyTheNewCreationClockOnRebase() {
        val source = source(); val files = files(); val input = input(files)
        var seconds = Instant.parse("2026-10-01T00:00:00Z").epochSeconds
        val times = mutableListOf<Instant>()
        fun calculate(snapshot: LedgerSnapshotDto, input: JsonObject, previous: Map<String, Any?>?, ids: Map<String, String>) =
            calculateLocalCashImportBusinessPlan(files, snapshot, input, previous, ids, {}) {
                Instant.fromEpochSeconds(seconds.also { seconds += 60 }).also { times += it }
            }
        val first = calculate(source, input, null, emptyMap())
        assertEquals(2, first.second.transactions.size); assertEquals(1, first.second.relations.size)
        val review = LocalCashImportReview.freeze(input, first.first, source, first.second)
        val result = review.recalculate(source.copy(ledgerSeq = "1")) { snapshot, input, previous, ids -> calculate(snapshot, input, previous, ids) }
        assertTrue(result.conclusionUnchanged)
        assertEquals(first.second, result.changes)
        assertTrue(times.distinct().size > 1)
    }
}
