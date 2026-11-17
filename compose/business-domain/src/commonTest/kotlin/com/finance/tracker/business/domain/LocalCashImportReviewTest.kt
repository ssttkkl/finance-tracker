package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportReviewTest {
    private val empty = JsonArray(emptyList())
    private fun input(entries: JsonElement = empty) = buildJsonObject {
        put("account_selections", empty); put("row_choices", empty)
        put("relation_choices", empty); put("entry_choices", entries)
    }

    @Test fun fileChangesRetainUnrelatedDecisionsAndRelationHistoryButRetireTheOldConfirmation() {
        val golden = Json.parseToJsonElement(CashEntryChoiceOriginalVectors.json).jsonObject
        @Suppress("UNCHECKED_CAST") val plan = decodeLocalCashValue(golden.getValue("plan")) as Map<String, Any?>
        val raw = Json.parseToJsonElement("""{"account_selections":[{"source_account_ref":"funding","account_id":"7","expected_account_revision":9007199254740993}],"row_choices":[{"row_ref":"file:one","action":"apply","patch":{"note":""}},{"row_ref":"file2:two","action":"defer"}],"relation_choices":[{"suggestion_id":"sg","action":"defer"}],"entry_choices":[]}""").jsonObject
        val original = LocalCashImportReview.freeze(JsonObject(raw + ("entry_choices" to golden.getValue("choices"))), plan)
        val confirmed = LocalCashImportReview.decode(JsonObject(Json.parseToJsonElement(original.encode()).jsonObject +
            mapOf("version" to JsonPrimitive(2), "conclusion" to JsonPrimitive("a".repeat(64)))).toString())
        val changed = LocalCashImportReview.decode(confirmed.afterFilesChanged(setOf("file")).encode())
        assertNull(changed.conclusion()); assertEquals(JsonPrimitive(1), Json.parseToJsonElement(changed.encode()).jsonObject["version"])
        assertEquals(raw["account_selections"], changed.inputJson()["account_selections"])
        assertEquals(JsonArray(listOf(raw.getValue("row_choices").jsonArray[1])), changed.inputJson()["row_choices"])
        assertEquals(raw["relation_choices"], changed.inputJson()["relation_choices"])
        assertEquals(empty, changed.inputJson()["entry_choices"])
        assertEquals(original.previousPlan() - "entry_targets", changed.previousPlan() - "entry_targets")
        assertEquals(emptyMap<String, Any?>(), changed.previousPlan()["entry_targets"])
        assertEquals(raw["row_choices"], confirmed.afterFilesChanged(emptySet()).inputJson()["row_choices"])
        assertEquals(golden.getValue("choices"), confirmed.inputJson()["entry_choices"])
        assertNotNull(confirmed.conclusion())
    }

    @Test fun recoveredEntryChoiceStillProducesTheCompleteUnmodifiedOriginalOracleResult() {
        val golden = Json.parseToJsonElement(CashEntryChoiceOriginalVectors.json).jsonObject
        @Suppress("UNCHECKED_CAST") val plan = decodeLocalCashValue(golden.getValue("plan")) as MutableMap<String, Any?>
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(input(golden.getValue("choices")), plan).encode())
        applyLocalCashImportEntryChoices(plan, saved.input().getValue("entry_choices") as List<*>, saved.previousPlan(), setOf("cat"))
        assertEquals(golden.getValue("applied"), encodeLocalCashValue(plan))
    }

    @Test fun refundEntryTargetRetainsItsRealCalculatedRelationAmount() {
        val base = LocalCashRelationContextTest().snapshot()
        val source = base.copy(facts = base.facts.copy(transactionRelations = emptyList(),
            cashTransactions = base.facts.cashTransactions.filter { it.id != "5" }))
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<MutableMap<String, Any?>>
        operations.single()["stable_source_key"] = listOf("ccb_debit", "account", "owner", "provider", "record-new")
        @Suppress("UNCHECKED_CAST") val values = operations.single().getValue("values") as MutableMap<String, Any?>
        values["amount"] = CashAmount.parse("5.00"); values["record_type"] = "refund"
        for (item in values.getValue("components") as List<*>) {
            @Suppress("UNCHECKED_CAST") (item as MutableMap<String, Any?>)["amount"] = CashAmount.parse("5.00")
        }
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations, additionalSeedIds = setOf(1L))
        fun preview() = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, emptyList()) {
            Instant.parse("2026-10-01T09:00:00Z")
        }
        val snapshot = preview()
        val component = snapshot.components.values.single { it.transactionId == snapshot.parentOps.keys.single() }.componentId as String
        val proposal = RelationProposal("refund_offset", "11", component, evidence = RelationEvidence(extras = mapOf("refund_amount" to "5.00")))
        val plan = completeLocalCashImportPreviewPlan(source, prepared, snapshot, listOf(proposal), emptyList(), emptyMap(), null)
        val targets = plan.getValue("entry_targets") as Map<*, *>
        val target = targets.entries.first { ((it.value as Map<*, *>)["final_edges"] as List<*>).isNotEmpty() }
        val edge = ((target.value as Map<*, *>)["final_edges"] as List<*>).first() as Map<*, *>
        assertTrue(edge["applied_amount"] is CashDecimalLiteral)
        val choices = JsonArray(listOf(buildJsonObject {
            put("edit_ref", target.key as String); put("patch", buildJsonObject { put("note", "after-refund") })
        }))
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(input(choices), plan).encode())
        assertEquals(localCashImportDigest(listOf(target.value)), localCashImportDigest(listOf(
            (saved.previousPlan().getValue("entry_targets") as Map<*, *>)[target.key])))
        applyLocalCashImportEntryChoices(plan, saved.input().getValue("entry_choices") as List<*>, saved.previousPlan(), emptySet())
        assertEquals("after-refund", (((plan.getValue("entry_edits") as List<*>).single() as Map<*, *>)["patch"] as Map<*, *>)["note"])
        @Suppress("UNCHECKED_CAST") val mutableTargets = plan["entry_targets"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") val changed = (target.value as Map<String, Any?>) +
            ("final_edges" to listOf(edge + ("applied_amount" to CashDecimalLiteral("4.00"))))
        mutableTargets[target.key as String] = changed
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            applyLocalCashImportEntryChoices(plan, saved.input().getValue("entry_choices") as List<*>, saved.previousPlan(), emptySet())
        }.code)
    }

    @Test fun durablePreviousContextDoesNotResurrectAnInvalidatedRelationChoice() {
        val group = mutableMapOf<String, Any?>("suggestion_id" to "source", "origin" to "discovered", "action" to "accept",
            "allowed_actions" to listOf("accept", "defer", "ignore"), "edges" to emptyList<Any?>(),
            "candidates" to emptyList<Any?>(), "member_refs" to listOf(mapOf("row_ref" to "file:r1")))
        val choices = listOf(mapOf("suggestion_id" to "source", "action" to "accept"))
        val first = mapOf("relation_groups" to listOf(group))
        val disappeared = applyLocalCashImportRelationChoices(emptyList(), choices, first)
        val raw = JsonObject(input() + ("relation_choices" to encodeLocalCashValue(choices)))
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(raw, mapOf(
            "invalidated_relation_choices" to disappeared.sources, "relation_choice_generations" to disappeared.generations)).encode())
        @Suppress("UNCHECKED_CAST") val restoredChoices = saved.input().getValue("relation_choices") as List<Map<String, Any?>>
        applyLocalCashImportRelationChoices(listOf(group), restoredChoices, saved.previousPlan())
        assertEquals("defer", group["action"])
        assertNotEquals("source", group["suggestion_id"])
        assertEquals(JsonPrimitive(1), group["choice_generation"])
    }

    @Test fun restoredDecisionAppliesToTheRealComputedTargetAndRejectsChangedDependencies() {
        val source = LocalCashRelationContextTest().snapshot().let {
            it.copy(facts = it.facts.copy(cashTransactions = it.facts.cashTransactions.filter { tx -> tx.id != "5" }))
        }
        @Suppress("UNCHECKED_CAST") val operations = decodeLocalCashValue(LocalCashImportPreviewSnapshotTest().operation) as List<Map<String, Any?>>
        val prepared = LocalCashImportPreviewPreparation(source) {}.prepareOperations(operations)
        fun preview() = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, emptyList()) {
            Instant.parse("2026-10-01T09:00:00Z")
        }
        val first = completeLocalCashImportPreviewPlan(source, prepared, preview(), emptyList(), emptyList(), emptyMap(), null)
        val reference = (first.getValue("entry_targets") as Map<*, *>).keys.single() as String
        val entries = Json.parseToJsonElement("""[{"edit_ref":"$reference","patch":{"note":"","category_id":null}}]""")
        val frozen = LocalCashImportReview.freeze(input(entries), first)
        val restored = LocalCashImportReview.decode(frozen.encode())
        val again = completeLocalCashImportPreviewPlan(source, prepared, preview(), emptyList(), emptyList(), restored.input(), restored.previousPlan())
        val edit = (again.getValue("entry_edits") as List<*>).single() as Map<*, *>
        assertEquals(mapOf("note" to "", "category_id" to null), edit["patch"])
        assertEquals(localCashImportDigest(listOf(first["entry_targets"])), localCashImportDigest(listOf(restored.previousPlan()["entry_targets"])))
        @Suppress("UNCHECKED_CAST") val targets = again["entry_targets"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST") val target = targets.getValue(reference) as Map<String, Any?>
        targets[reference] = target + ("rows" to listOf(listOf(1L, 99L)))
        val error = assertFailsWith<CashImportPlanError> {
            applyLocalCashImportEntryChoices(again, restored.input().getValue("entry_choices") as List<*>, restored.previousPlan(), emptySet())
        }
        assertEquals("plan_changed", error.code)
    }

    @Test fun retainedTargetsPreserveComputedTypesAndIgnoreUnselectedBodies() {
        val instant = Instant.parse("2026-01-02T03:04:05.123456789Z")
        val target = linkedMapOf<String, Any?>("parent_ids" to listOf("184467440737095516170"),
            "amount" to CashAmount.parse("-0.000"), "at" to instant,
            "integer" to CashImportPreviewInteger.parse("340282366920938463463374607431768211455"),
            "float" to Json.parseToJsonElement("1.0"), "int" to Json.parseToJsonElement("9007199254740993"),
            "ordinary" to listOf("-0.000", "2026-01-02T03:04:05.123456789Z"))
        val plan = mapOf("entry_targets" to mapOf("ie_keep" to target, "ie_ignore" to mapOf("body" to Any())),
            "private_body" to Any(), "operations" to listOf(mapOf("occurrence_key" to "occ", "values" to mapOf("record_id" to "once", "body" to Any()))))
        val choices = Json.parseToJsonElement("""[{"edit_ref":"ie_keep","patch":{"note":"checked"}}]""")
        val frozen = LocalCashImportReview.freeze(input(choices), plan)
        target["amount"] = CashAmount.parse("17")
        val restored = LocalCashImportReview.decode(frozen.encode())
        val targets = restored.previousPlan().getValue("entry_targets") as Map<*, *>
        assertEquals(setOf("ie_keep"), targets.keys)
        val actual = targets["ie_keep"] as Map<*, *>
        assertEquals(CashAmount.parse("-0.000").representation, (actual["amount"] as CashAmount).representation)
        assertEquals(instant, actual["at"])
        assertEquals("340282366920938463463374607431768211455", actual["integer"].toString())
        assertEquals(listOf("-0.000", "2026-01-02T03:04:05.123456789Z"), actual["ordinary"])
        assertEquals(mapOf("occ" to "once"), restored.recordIds())
        assertFalse(frozen.encode().contains("private_body")); assertFalse(frozen.encode().contains("ie_ignore"))
        @Suppress("UNCHECKED_CAST") (actual as MutableMap<String, Any?>).clear()
        assertTrue((restored.previousPlan().getValue("entry_targets") as Map<*, *>).isNotEmpty())
        assertEquals(frozen.encode(), restored.encode())
    }

    @Test fun exactAccountRevisionAndExplicitClearsSurviveAndBadInputIsRejected() {
        val raw = Json.parseToJsonElement("""{"account_selections":[{"source_account_ref":"funding","account_id":"7","expected_account_revision":9007199254740993}],"row_choices":[{"row_ref":"file:row","action":"apply","patch":{"note":"","category_id":null}}],"relation_choices":[],"entry_choices":[]}""").jsonObject
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(raw, emptyMap()).encode())
        assertEquals(prepareLocalCashImportInput(raw["account_selections"], raw["row_choices"], empty, empty), saved.input())
        assertEquals("9007199254740993", ((saved.input()["account_selections"] as List<*>).single() as Map<*, *>)["expected_account_revision"].toString())
        assertFailsWith<CashImportPlanError> { LocalCashImportReview.freeze(JsonObject(raw + ("relation_choices" to JsonPrimitive(true))), emptyMap()) }
        assertFailsWith<CashImportPlanError> { LocalCashImportReview.freeze(input(Json.parseToJsonElement("""[{"edit_ref":"missing","patch":{}}]""")), emptyMap()) }
        assertFails { LocalCashImportReview.decode(saved.encode().replace("\"version\":1", "\"version\":2")) }
    }

    @Test fun livePreviewTargetsKeepTheirTypesAndIsolationWhileOnlyActualChoicesAreFrozen() {
        val amount = CashAmount.parse("1.2300"); val decimal = CashDecimalLiteral("0.500")
        val instant = Instant.parse("2026-10-01T09:00:00Z")
        val target = linkedMapOf<String, Any?>("parent_ids" to listOf("1"), "rows" to listOf(listOf("1", mapOf(
            "amount" to amount, "occurred_at" to instant, "ordinary" to "2026-10-01T09:00:00Z"))),
            "final_edges" to listOf(mapOf("applied_amount" to decimal)))
        val targets = linkedMapOf("ie_live" to target)
        val make = LocalCashImportReview.forPreview(mapOf("entry_targets" to targets, "unrelated_opaque_body" to Any()))
        target.clear(); targets.clear()
        val emptyReview = make(buildJsonObject { put("unused_legacy_field", true) })
        assertEquals(input(), emptyReview.inputJson()); assertNull(emptyReview.conclusion())
        assertEquals(emptyMap<String, Any?>(), emptyReview.previousPlan()["entry_targets"])
        val chosen = input(buildJsonArray { add(buildJsonObject { put("edit_ref", "ie_live"); put("patch", buildJsonObject { put("note", "") }) }) })
        val saved = make(chosen); val restored = LocalCashImportReview.decode(saved.encode())
        val previous = (restored.previousPlan().getValue("entry_targets") as Map<*, *>)["ie_live"] as Map<*, *>
        val row = ((previous["rows"] as List<*>).single() as List<*>)[1] as Map<*, *>
        assertEquals(amount, assertIs<CashAmount>(row["amount"])); assertEquals(instant, assertIs<Instant>(row["occurred_at"]))
        assertEquals("2026-10-01T09:00:00Z", assertIs<String>(row["ordinary"]))
        assertEquals(decimal, assertIs<CashDecimalLiteral>(((previous["final_edges"] as List<*>).single() as Map<*, *>)["applied_amount"]))
        assertEquals(chosen, restored.inputJson()); assertEquals(saved.encode(), make(chosen).encode())
        assertFalse("unrelated_opaque_body" in saved.encode()); assertNull(saved.conclusion())
        assertEquals(emptyMap<String, Any?>(), make(input()).previousPlan()["entry_targets"])
        val unknown = input(buildJsonArray { add(buildJsonObject { put("edit_ref", "missing"); put("patch", JsonObject(emptyMap())) }) })
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { make(unknown) }.code)
    }

    @Test fun originalPatchNumberTokensAreNeverEncodedAsBinaryFloats() {
        val row = Json.parseToJsonElement("""[{"row_ref":"file:row","action":"apply","patch":{"source_payload":{"a":1.000000000000000001,"b":1e400,"c":-0,"d":1.2300}}}]""")
        val raw = JsonObject(input() + ("row_choices" to row))
        val saved = LocalCashImportReview.decode(LocalCashImportReview.freeze(raw, emptyMap()).encode())
        val recovered = Json.parseToJsonElement(saved.encode()).jsonObject.getValue("input").jsonObject.getValue("row_choices")
        assertEquals(row.toString(), recovered.toString())
        val patch = ((saved.input()["row_choices"] as List<*>).single() as Map<*, *>)["patch"] as Map<*, *>
        val payload = patch["source_payload"] as Map<*, *>
        assertEquals("1.000000000000000001", (payload["a"] as JsonPrimitive).content)
        assertEquals("1e400", (payload["b"] as JsonPrimitive).content)
    }
}
