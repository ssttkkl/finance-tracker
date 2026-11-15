package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

class LocalCashImportRebindingTest {
    private val empty = JsonArray(emptyList())
    internal class Calculated(val plan: Map<String, Any?>, val writes: CashWriteSetDto)
    internal inner class Fixture(private val strong: Boolean = false) {
        val source = LocalCashImportRelationWritesTest().calculate(alternatives = true).first
        val files = listOf(ParsedImportFile("refund", "a".repeat(64), "parsed", ParsedCashDocument("alipay", "csv", "p",
            listOf(ParsedCashRow(1, mapOf("amount" to "2.50", "currency" to "CNY", "occurred_at" to "2026-10-03T09:00:00Z",
                "record_type" to "refund", "record_subtype" to "not_applicable", "note" to "Refund S", "source_payload" to emptyMap<String, Any?>()),
                paymentMethod = "支付宝余额", providerId = "REFUND-1")), "owner", "Account",
            CashSourceCoverage(null, null, false, "", emptyMap(), "v"), 1, 1)))
        val input = buildJsonObject {
            put("account_selections", JsonArray(scanLocalCashFunding(files).groups.map { buildJsonObject {
                put("source_account_ref", it.sourceAccountRef); put("account_id", "10"); put("expected_account_revision", 9007199254740993L)
            } })); put("row_choices", empty); put("relation_choices", empty); put("entry_choices", empty)
        }
        fun calculate(input: JsonObject = this.input, previous: Map<String, Any?>? = null,
            ids: Map<String, String> = emptyMap(), source: LedgerSnapshotDto = this.source, discover: Boolean = true): Calculated {
            val rowChoices = input.getValue("row_choices").jsonArray
            val relationChoices = input.getValue("relation_choices").jsonArray
            val operations = prepareLocalCashImportOperations(files, source, input.getValue("account_selections"), rowChoices, ids, false)
            val prepared = LocalCashImportPreviewPreparation(source) {}.prepare(operations.operations, operations.source.rows.rows,
                additionalSeedIds = source.facts.cashTransactions.map { it.id.toLong() }.toSet())
            val snapshot = buildLocalCashImportPreviewSnapshot(source, prepared.context, prepared.operations, operations.rows) { Instant.parse("2026-10-03T09:00:00Z") }
            val proposals = snapshot.parentOps.keys.map { parent ->
                val part = snapshot.components.values.single { it.transactionId == parent }.componentId as String
                RelationProposal("refund_offset", "11", part, anchorFactId = part,
                    status = if (strong) "accepted" else "pending_review", confidence = if (strong) "strong" else "weak",
                    evidence = RelationEvidence(candidateFactIds = listOf("11", "22"), candidateCount = 2, extras = mapOf("refund_amount" to "2.50")))
            }
            val plan = completeLocalCashImportPreviewPlan(source, prepared, snapshot, if (discover) proposals else emptyList(), files, decodeInput(input), previous, operations.candidateSets)
            return Calculated(plan, buildLocalCashImportWriteSet(source, files, operations.source, freezeLocalCashImportPreviewPlan(plan), rowChoices, relationChoices))
        }
        fun confirmed(): Pair<LocalCashImportReview, Calculated> {
            val first = calculate()
            val group = (first.plan.getValue("relation_groups") as List<*>).single() as Map<*, *>
            val candidate = (group["candidates"] as List<*>).lastOrNull() as? Map<*, *>
            val choices = buildJsonArray { add(buildJsonObject {
                put("suggestion_id", group["suggestion_id"] as String); put("action", "accept")
                candidate?.let { put("candidate_id", it["candidate_id"] as String) }
                put("common_fields", buildJsonObject { put("note", "Chosen B"); put("category_id", JsonNull) })
            }) }
            val chosenInput = JsonObject(input + ("relation_choices" to choices))
            val chosen = calculate(chosenInput, first.plan)
            val parent = ((chosen.plan["operations"] as List<*>).single() as Map<*, *>)["preview_transaction_id"] as String
            val target = (chosen.plan["entry_targets"] as Map<*, *>).entries.single {
                parent in ((it.value as Map<*, *>)["parent_ids"] as List<*>)
            }
            val fullInput = JsonObject(chosenInput + ("entry_choices" to buildJsonArray { add(buildJsonObject {
                put("edit_ref", target.key as String); put("patch", buildJsonObject { put("note", "Final D"); put("category_id", JsonNull) })
            }) }))
            val final = calculate(fullInput, chosen.plan)
            return LocalCashImportReview.decode(LocalCashImportReview.freeze(fullInput, final.plan, source, final.writes).encode()) to final
        }
        fun observed() = source.copy(ledgerSeq = (source.ledgerSeq.toLong() + 1).toString(), facts = source.facts.copy(
            cashTransactions = source.facts.cashTransactions.map { if (it.id == "1") it.copy(rowRevision = (it.rowRevision.toLong() + 1).toString()) else it },
            cashEntries = source.facts.cashEntries.map { it.copy(revision = (it.revision.toLong() + 1).toString()) }))
    }
    private fun decodeInput(input: JsonObject) = prepareLocalCashImportInput(input["account_selections"], input["row_choices"], input["relation_choices"], input["entry_choices"])

    @Test fun realChoicesAndFinalEntryEditRebindAcrossObservationChangesWithTheSameCompleteConclusion() {
        val f = Fixture(); val (review, original) = f.confirmed(); val frozen = review.encode()
        val current = f.observed(); val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds(), source = current)
        val relations = review.rebindRelations(current, fresh.plan, fresh.writes)
        assertNotEquals(review.input()["relation_choices"], relations.input()["relation_choices"])
        assertEquals(emptyList<Any?>(), relations.input()["entry_choices"])
        val decided = f.calculate(relations.inputJson(), relations.previousPlan(), review.recordIds(), current)
        val complete = review.rebindEntries(current, decided.plan, decided.writes, relations)
        assertNotEquals(review.input()["entry_choices"], complete.input()["entry_choices"])
        val again = f.calculate(complete.inputJson(), complete.previousPlan(), review.recordIds(), current)
        assertEquals(review.conclusion(), localCashImportConclusion(current, freezeLocalCashImportPreviewPlan(again.plan), again.writes))
        assertEquals(original.writes, again.writes)
        assertEquals(buildJsonObject { put("note", "Chosen B"); put("category_id", JsonNull) }, again.writes.relationGroups.single().commonFields)
        assertTrue(again.writes.entries.any { it.note == "Final D" })
        assertEquals(frozen, review.encode())
        assertEquals(review.input()["account_selections"], complete.input()["account_selections"])
    }

    @Test fun missingOrAmbiguousGroupsCannotDiscardOrRetargetTheOriginalDecision() {
        val f = Fixture(); val (review, _) = f.confirmed(); val frozen = review.encode()
        val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds())
        val groups = fresh.plan.getValue("relation_groups") as List<*>
        @Suppress("UNCHECKED_CAST") val duplicate = (groups.single() as Map<String, Any?>) + ("suggestion_id" to "different-derived-id")
        for (changed in listOf(emptyList<Any?>(), groups + duplicate)) {
            assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
                review.rebindRelations(f.source, fresh.plan + ("relation_groups" to changed), fresh.writes)
            }.code)
        }
        assertEquals(frozen, review.encode())
    }

    @Test fun earlierEncodingsRemainReadableButCannotInventTheMissingOriginalBinding() {
        val f = Fixture(); val (review, _) = f.confirmed()
        val document = Json.parseToJsonElement(review.encode()).jsonObject
        val legacy = LocalCashImportReview.decode(JsonObject(document - "bindings").toString())
        assertEquals(review.conclusion(), legacy.conclusion())
        val fresh = f.calculate()
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { legacy.rebindRelations(f.source, fresh.plan, fresh.writes) }.code)
    }

    @Test fun anActualNewInvalidationGenerationCannotBeOverwrittenByTheOldActiveChoice() {
        val f = Fixture(); val (review, _) = f.confirmed()
        val input = JsonObject(review.inputJson() + ("entry_choices" to empty))
        val disappeared = f.calculate(input, review.previousPlan(), review.recordIds(), discover = false)
        assertEquals(1, (disappeared.plan["invalidated_choices"] as List<*>).size)
        val returned = f.calculate(previous = disappeared.plan, ids = review.recordIds())
        val group = (returned.plan["relation_groups"] as List<*>).single() as Map<*, *>
        assertEquals(JsonPrimitive(1), group["choice_generation"])
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> {
            review.rebindRelations(f.source, returned.plan, returned.writes)
        }.code)
    }

    @Test fun theBoundTypedTargetDoesNotFollowLaterMutationsOfTheCalculatedPlan() {
        val f = Fixture(); val (review, _) = f.confirmed()
        val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds())
        val relations = review.rebindRelations(f.source, fresh.plan, fresh.writes)
        val decided = f.calculate(relations.inputJson(), relations.previousPlan(), review.recordIds())
        val bound = review.rebindEntries(f.source, decided.plan, decided.writes, relations)
        val before = localCashImportDigest(listOf(bound.previousPlan()))
        val reference = ((bound.input()["entry_choices"] as List<*>).single() as Map<*, *>)["edit_ref"]
        @Suppress("UNCHECKED_CAST") val target = (decided.plan["entry_targets"] as Map<*, *>)[reference] as MutableMap<String, Any?>
        val edges = target["final_edges"] as List<*>
        val amount = (edges.single() as Map<*, *>)["applied_amount"]
        assertTrue(amount is CashAmount || amount is CashDecimalLiteral)
        target["parent_ids"] = listOf("different-parent")
        @Suppress("UNCHECKED_CAST") (edges.single() as MutableMap<String, Any?>)["applied_amount"] = CashDecimalLiteral("99")
        assertEquals(before, localCashImportDigest(listOf(bound.previousPlan())))
    }

    @Test fun invalidatedChoicesStayInvalidatedWhenTheirOldBusinessGroupReturns() {
        val f = Fixture(); val (original, _) = f.confirmed()
        val input = JsonObject(Json.parseToJsonElement(original.encode()).jsonObject.getValue("input").jsonObject + ("entry_choices" to empty))
        val disappeared = f.calculate(input, original.previousPlan(), original.recordIds(), discover = false)
        val review = LocalCashImportReview.decode(LocalCashImportReview.freeze(input, disappeared.plan, f.source, disappeared.writes).encode())
        val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds())
        val bound = review.rebindRelations(f.source, fresh.plan, fresh.writes)
        assertEquals(review.input()["relation_choices"], bound.input()["relation_choices"])
        val again = f.calculate(bound.inputJson(), bound.previousPlan(), review.recordIds())
        val group = (again.plan["relation_groups"] as List<*>).single() as Map<*, *>
        assertEquals("defer", group["action"]); assertEquals(JsonPrimitive(1), group["choice_generation"])
        assertNotEquals(true, group["explicit_decision"])
        assertEquals(1, (again.plan["invalidated_choices"] as List<*>).size)
        assertNotEquals(review.conclusion(), localCashImportConclusion(f.source, freezeLocalCashImportPreviewPlan(again.plan), again.writes))
    }

    @Test fun aNewExplicitConfirmationKeepsItsNonzeroGenerationAcrossRebinding() {
        val f = Fixture(); val (original, _) = f.confirmed()
        val relationOnly = JsonObject(original.inputJson() + ("entry_choices" to empty))
        val disappeared = f.calculate(relationOnly, original.previousPlan(), original.recordIds(), discover = false)
        val returned = f.calculate(previous = disappeared.plan, ids = original.recordIds())
        val group = (returned.plan["relation_groups"] as List<*>).single() as Map<*, *>
        val old = Json.parseToJsonElement(original.encode()).jsonObject.getValue("input").jsonObject.getValue("relation_choices").jsonArray.single().jsonObject
        val input = JsonObject(f.input + ("relation_choices" to JsonArray(listOf(JsonObject(old +
            ("suggestion_id" to JsonPrimitive(group["suggestion_id"] as String)))))))
        val chosen = f.calculate(input, returned.plan, original.recordIds())
        val review = LocalCashImportReview.freeze(input, chosen.plan, f.source, chosen.writes)
        val source = f.observed()
        val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds(), source = source)
        val bound = review.rebindRelations(source, fresh.plan, fresh.writes)
        val again = f.calculate(bound.inputJson(), bound.previousPlan(), review.recordIds(), source)
        val accepted = (again.plan["relation_groups"] as List<*>).single() as Map<*, *>
        assertEquals("accept", accepted["action"]); assertEquals(JsonPrimitive(1), accepted["choice_generation"])
        assertEquals(review.conclusion(), localCashImportConclusion(source, freezeLocalCashImportPreviewPlan(again.plan), again.writes))
    }

    @Test fun finalTargetAmbiguityAndCrossSnapshotOrOwnerUseCannotRetargetAnEdit() {
        val f = Fixture(); val (review, _) = f.confirmed()
        val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds())
        val relations = review.rebindRelations(f.source, fresh.plan, fresh.writes)
        val decided = f.calculate(relations.inputJson(), relations.previousPlan(), review.recordIds())
        val bound = review.rebindEntries(f.source, decided.plan, decided.writes, relations)
        val id = ((bound.input()["entry_choices"] as List<*>).single() as Map<*, *>)["edit_ref"] as String
        @Suppress("UNCHECKED_CAST") val targets = decided.plan["entry_targets"] as Map<String, Any?>
        for (values in listOf(targets - id, targets + ("other-derived-edit" to targets.getValue(id)))) assertEquals("plan_changed",
            assertFailsWith<CashImportPlanError> { review.rebindEntries(f.source, decided.plan + ("entry_targets" to values), decided.writes, relations) }.code)
        for (source in listOf(f.source.copy(workspaceId = "another"), f.observed(), f.source.copy(rulesVersion = "old"))) assertEquals("plan_changed",
            assertFailsWith<CashImportPlanError> { review.rebindEntries(source, decided.plan, decided.writes, relations) }.code)
        val restored = LocalCashImportReview.decode(review.encode())
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { restored.rebindEntries(f.source, decided.plan, decided.writes, relations) }.code)
        assertEquals("plan_changed", assertFailsWith<CashImportPlanError> { review.rebindRelations(f.source.copy(workspaceId = "another"), fresh.plan, fresh.writes) }.code)
    }

    @Test fun aStrongGroupAddressSurvivesTheChosenStateButCannotHideChangedBusinessFacts() {
        val f = Fixture(strong = true)
        val first = f.calculate()
        val group = (first.plan["relation_groups"] as List<*>).single() as Map<*, *>
        assertEquals(emptyList<Any?>(), group["candidates"])
        for (action in listOf("accept", "defer", "ignore")) {
            val input = JsonObject(f.input + ("relation_choices" to buildJsonArray { add(buildJsonObject {
                put("suggestion_id", group["suggestion_id"] as String); put("action", action)
            }) }))
            val chosen = f.calculate(input, first.plan)
            val review = LocalCashImportReview.freeze(input, chosen.plan, f.source, chosen.writes)
            val observed = f.observed()
            val fresh = f.calculate(previous = review.previousPlan(), ids = review.recordIds(), source = observed)
            val bound = review.rebindRelations(observed, fresh.plan, fresh.writes)
            val again = f.calculate(bound.inputJson(), bound.previousPlan(), review.recordIds(), observed)
            assertEquals(action, ((again.plan["relation_groups"] as List<*>).single() as Map<*, *>)["action"])
            assertEquals(review.conclusion(), localCashImportConclusion(observed, freezeLocalCashImportPreviewPlan(again.plan), again.writes))
            val changed = observed.copy(facts = observed.facts.copy(
                cashTransactions = observed.facts.cashTransactions.map { if (it.id == "1") it.copy(amount = "-6.00") else it },
                cashTransactionComponents = observed.facts.cashTransactionComponents.map { if (it.id == "11") it.copy(amount = "-6.00") else it }))
            val other = f.calculate(previous = review.previousPlan(), ids = review.recordIds(), source = changed)
            val located = review.rebindRelations(changed, other.plan, other.writes)
            val result = f.calculate(located.inputJson(), located.previousPlan(), review.recordIds(), changed)
            assertNotEquals(review.conclusion(), localCashImportConclusion(changed, freezeLocalCashImportPreviewPlan(result.plan), result.writes))
        }
    }

    @Test fun originalRowPatchTokensAndReturnedContainersRemainIndependent() {
        val f = Fixture()
        val rows = Json.parseToJsonElement("""[{"row_ref":"refund:r1","action":"apply","patch":{"note":"","category_id":null,"source_payload":{"id":9007199254740993,"expected_revision":1.000000000000000001,"plain":"1.2300"}}}]""")
        val input = JsonObject(f.input + ("row_choices" to rows))
        val chosen = f.calculate(input)
        val review = LocalCashImportReview.freeze(input, chosen.plan, f.source, chosen.writes)
        val fresh = f.calculate(review.inputJson(), review.previousPlan(), review.recordIds(), f.observed())
        val bound = review.rebindRelations(f.observed(), fresh.plan, fresh.writes)
        assertEquals(rows.toString(), bound.inputJson().getValue("row_choices").toString())
        val payload = (((bound.input()["row_choices"] as List<*>).single() as Map<*, *>)["patch"] as Map<*, *>)["source_payload"] as Map<*, *>
        assertEquals("1.000000000000000001", (payload["expected_revision"] as JsonPrimitive).content)
        val before = localCashImportDigest(listOf(bound.previousPlan()))
        @Suppress("UNCHECKED_CAST") val previous = bound.previousPlan() as MutableMap<String, Any?>
        previous.clear()
        @Suppress("UNCHECKED_CAST") val choice = (bound.input()["row_choices"] as List<*>).single() as MutableMap<String, Any?>
        choice.clear()
        assertEquals(rows.toString(), bound.inputJson().getValue("row_choices").toString())
        assertEquals(before, localCashImportDigest(listOf(bound.previousPlan())))
    }

    @Test fun malformedOrMismatchedBindingSummariesAreNeverRecoveredAsMissingData() {
        val f = Fixture(); val (review, _) = f.confirmed()
        val document = Json.parseToJsonElement(review.encode()).jsonObject
        val bindings = document.getValue("bindings").jsonObject
        val groups = bindings.getValue("relations").jsonObject
        val id = groups.keys.single(); val target = groups.getValue(id).jsonObject
        val variants = listOf(bindings - "workspace", bindings + ("workspace" to JsonPrimitive(1)),
            bindings + ("unknown" to JsonNull), bindings + ("entries" to JsonObject(emptyMap())),
            bindings + ("relations" to JsonObject(emptyMap())),
            bindings + ("relations" to JsonObject(mapOf(id to JsonObject(target + ("group" to JsonPrimitive("a".repeat(63))))))),
            bindings + ("relations" to JsonObject(mapOf(id to JsonObject(target + ("candidate" to JsonNull))))))
        for (value in variants) assertFails { LocalCashImportReview.decode(JsonObject(document + ("bindings" to JsonObject(value))).toString()) }
    }
}
