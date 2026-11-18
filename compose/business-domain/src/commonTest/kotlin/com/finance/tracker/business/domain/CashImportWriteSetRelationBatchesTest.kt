package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetRelationBatchesTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun fresh(id: String) = NewCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = (1..6).map { LedgerCashTransactionFactDto("$it", "10", "posted", "legacy", null, "1", "atomic", "bank",
            "record:$it", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "") },
        cashTransactionComponents = (1..6).map { LedgerCashComponentFactDto("$it$it", "$it", "10", "-5", "CNY", "0", "", "", empty, time) },
    )
    private fun edge(id: String, primary: String, secondary: String, state: String = "confirmed") =
        CashRelationWriteDto(CashWriteVersionedReferenceDto(fresh(id), null), "payment_mirror", target(primary), target(secondary),
            ref("$primary$primary"), ref("$secondary$secondary"), target(primary), ref("$primary$primary"), "expense", "expense", state, true,
            null, "user", empty, "5", "rule", "user", emptyList())
    private fun group(vararg ids: String) = CashRelationWriteGroupDto(ids.map(::fresh))
    private fun oldEdge(state: String = "confirmed", active: Boolean = true) = LedgerCashRelationFactDto("61", "payment_mirror", "2", "3", "22", "33", "2", "22",
        "expense", "expense", state, active, "1", null, "user", empty, "5", "rule", "user", time, "user", time, "")

    @Test fun nonacceptedBridgesPreserveTheFirstBatchAndTheOrderWithinIt() {
        for (state in listOf("draft", "ignored")) {
            val bridge = group("bridge")
            val separate = group("a-independent")
            val accepted = group("z-accepted")
            val value = CashWriteSetDto(relations = listOf(edge("a-independent", "4", "5"), edge("z-accepted", "2", "3"), edge("bridge", "1", "2", state)),
                relationGroups = listOf(bridge, separate, accepted))
            val before = value.copy()
            assertEquals(listOf(listOf(bridge, accepted), listOf(separate)), prepareCashImportWriteSetRelationBatches(value, facts()))
            assertEquals(before, value)
        }
    }

    @Test fun anOldConfirmedClosureJoinsBatchesWithoutAddingItsEdgesToTheGroups() {
        val first = group("first")
        val second = group("second")
        val value = CashWriteSetDto(relations = listOf(edge("first", "1", "2"), edge("second", "3", "4")), relationGroups = listOf(second, first))
        val source = facts().copy(transactionRelations = listOf(oldEdge()))
        val before = source.copy()
        val batches = prepareCashImportWriteSetRelationBatches(value, source)
        assertEquals(listOf(listOf(second, first)), batches)
        assertEquals(listOf(fresh("second")), batches.single().first().relations)
        assertEquals(before, source)
    }

    @Test fun oldDraftIgnoredAndInactiveEdgesDoNotConnectIndependentBatches() {
        val first = group("first")
        val second = group("second")
        val value = CashWriteSetDto(relations = listOf(edge("first", "1", "2"), edge("second", "3", "4")), relationGroups = listOf(second, first))
        for (old in listOf(oldEdge("draft"), oldEdge("ignored"), oldEdge(active = false))) {
            assertEquals(listOf(listOf(second), listOf(first)), prepareCashImportWriteSetRelationBatches(value, facts().copy(transactionRelations = listOf(old))))
        }
    }

    @Test fun oneExplicitChoiceGroupCanTieItsDisconnectedEdgesIntoTheSameBatch() {
        val joint = group("alpha", "zulu")
        val other = group("beta")
        val value = CashWriteSetDto(relations = listOf(edge("alpha", "1", "2"), edge("zulu", "4", "5"), edge("beta", "5", "6")),
            relationGroups = listOf(joint, other))
        assertEquals(listOf(listOf(joint, other)), prepareCashImportWriteSetRelationBatches(value, facts()))
    }

    @Test fun clientJointLabelsAndRelationArrayOrderCannotOverrideTheOrderedGroups() {
        val first = group("first")
        val second = group("second")
        val rows = listOf(edge("first", "1", "2"), edge("second", "4", "5")).map { it.copy(decisionGroupId = "client-label") }
        val value = CashWriteSetDto(relations = rows, relationGroups = listOf(second, first))
        val expected = listOf(listOf(second), listOf(first))
        assertEquals(expected, prepareCashImportWriteSetRelationBatches(value, facts()))
        assertEquals(expected, prepareCashImportWriteSetRelationBatches(value.copy(relations = rows.reversed()), facts()))
    }

    @Test fun aNewParentAndAStoredParentWithTheSameTextRemainDifferentVertices() {
        val parent = CashWriteVersionedReferenceDto(fresh("1"), null)
        val part = fresh("new-part")
        val row = CashTransactionWriteDto(parent, CashTransactionWriteValuesDto(null, "draft", "import", null, "atomic", "bank", "new-source",
            empty, empty, null, empty, null, null, null, "", "", array, "", null, null, null),
            listOf(CashComponentWriteDto(part, null, null, null, "0", "", "", empty)))
        val newEdge = edge("new-parent", "1", "2", "draft").copy(primaryTransaction = parent, primaryComponent = part,
            anchorTransaction = parent, anchorComponent = part)
        val oldEdge = edge("old-parent", "1", "3", "draft")
        val first = group("new-parent")
        val second = group("old-parent")
        val value = CashWriteSetDto(transactions = listOf(row), relations = listOf(newEdge, oldEdge), relationGroups = listOf(first, second))
        assertEquals(listOf(listOf(first), listOf(second)), prepareCashImportWriteSetRelationBatches(value, facts()))
    }

    @Test fun invalidPartitionsAreRejectedBeforeReturningAnyBatches() {
        assertEquals(emptyList(), prepareCashImportWriteSetRelationBatches(CashWriteSetDto(), facts()))
        val failure = assertFailsWith<CashWriteSetReferenceFailure> {
            prepareCashImportWriteSetRelationBatches(CashWriteSetDto(relations = listOf(edge("one", "1", "2"))), facts())
        }
        assertEquals("invalid_reference", failure.code)
        assertEquals("relation_groups", failure.fieldPath)
    }
}
