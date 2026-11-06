package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetEntryEditsTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String, revision: String = "1") = CashWriteVersionedReferenceDto(ref(id), revision)
    private fun fresh(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun patch(vararg values: Pair<String, String>) = JsonObject(values.associate { it.first to JsonPrimitive(it.second) })
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = (1..3).map { LedgerCashTransactionFactDto("$it", "10", "posted", "legacy", null, "1", "atomic", "bank",
            "record:$it", empty, empty, null, empty, time, "-5", "CNY", "source-party", "", array, "S$it", null,
            "consumption", "not_applicable", time, null, "", "") },
        cashTransactionComponents = (1..3).map { LedgerCashComponentFactDto("$it$it", "$it", "10", "-5", "CNY", "0", "", "", empty, time) },
        cashEntries = listOf("owner", "other").mapIndexed { index, id -> LedgerCashEntryFactDto(id, if (index == 0) "1" else "3",
            if (index == 0) "1" else "3", "1", null, "old", "old-party", "", array, array, null) },
        cashEntryMembers = (1..3).map { LedgerCashEntryMemberFactDto("$it", if (it < 3) "owner" else "other", empty, array) },
    )
    private fun row(id: String, patch: JsonObject?, owner: String? = if (id == "3") "other" else "owner") =
        CashTransactionWriteDto(target(id), CashTransactionWriteValuesDto("10", "posted", "legacy", null, "atomic", "bank",
            "record:$id", empty, empty, null, empty, time, "-5", "CNY", "source-party", "", array, "S$id", null, "consumption", "not_applicable"),
            listOf(CashComponentWriteDto(ref("$id$id"), "10", "-5", "CNY", "0", "", "", empty)),
            entryEdit = patch?.let { CashEntryBeforeRelationEditDto(owner?.let(::target), it) })
    private fun changes(vararg rows: CashTransactionWriteDto) = CashWriteSetDto(transactions = rows.toList(),
        entryDependencies = listOf(target("owner"), target("other")))

    @Test fun sameOwnerPatchesAreAppliedOnceAtTheFirstEditedRowWithoutChangingSources() {
        val value = changes(row("1", patch("note" to "C")), row("2", patch("counterparty" to "C-party")))
        val source = facts()
        val before = source.copy()
        val prepared = prepareCashImportWriteSetEntryEdits(value, source)
        assertEquals(patch("note" to "C", "counterparty" to "C-party"), prepared.transactions.first().entryEdit!!.patch)
        assertEquals(target("owner"), prepared.transactions.first().entryEdit!!.entry)
        assertNull(prepared.transactions.last().entryEdit)
        assertEquals(value.entryDependencies, prepared.entryDependencies)
        assertEquals(value.transactions.map { it.values }, prepared.transactions.map { it.values })
        assertEquals(listOf("S1", "S2"), prepared.transactions.map { it.values.note })
        assertEquals(before, source)
        assertNotNull(value.transactions.last().entryEdit)
    }

    @Test fun conflictingValuesForTheSameOldOwnerRejectTheWholePreparation() {
        val value = changes(row("1", patch("note" to "first")), row("2", patch("note" to "second")))
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { prepareCashImportWriteSetEntryEdits(value, facts()) }.code)
        assertEquals("second", value.transactions.last().entryEdit!!.patch.getValue("note").jsonPrimitive.content)
    }

    @Test fun equalValuesAndExplicitNullClearsRetainTheirPresence() {
        val clear = buildJsonObject { put("category_id", JsonNull) }
        val value = changes(row("1", clear), row("2", clear))
        val prepared = prepareCashImportWriteSetEntryEdits(value, facts())
        assertEquals(clear, prepared.transactions.first().entryEdit!!.patch)
        assertNull(prepared.transactions.last().entryEdit)
        val source = facts().copy(cashCategories = listOf(LedgerCategoryFactDto("category", null, "root", "cat", "cat", null, "cat", 0,
            "0", "1", time, time)))
        val different = value.copy(transactions = listOf(value.transactions.first(), row("2", patch("category_id" to "category"))))
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { prepareCashImportWriteSetEntryEdits(different, source) }.code)
    }

    @Test fun theFirstPatchFollowsTheOriginalRowOrderAndSkipsRowsWithNoEdit() {
        val value = changes(row("1", null), row("2", patch("note" to "C")), row("3", patch("note" to "other-C")))
        val prepared = prepareCashImportWriteSetEntryEdits(value, facts())
        assertNull(prepared.transactions.first().entryEdit)
        assertEquals(value, prepared)
        val reversed = changes(row("2", patch("note" to "C")), row("1", patch("counterparty" to "C-party")))
        val result = prepareCashImportWriteSetEntryEdits(reversed, facts())
        assertEquals(listOf("2", "1"), result.transactions.map { (it.target.reference as ExistingCashWriteReferenceDto).id })
        assertEquals(patch("note" to "C", "counterparty" to "C-party"), result.transactions.first().entryEdit!!.patch)
        assertNull(result.transactions.last().entryEdit)
    }

    @Test fun laterInvalidOwnerOrRevisionObservationsCannotBeDiscardedByCoalescing() {
        val first = row("1", patch("note" to "C"))
        val later = row("2", patch("note" to "C"))
        for (observation in listOf(target("owner", "2"), target("other"), null)) {
            val value = changes(first, later.copy(entryEdit = later.entryEdit!!.copy(entry = observation)))
            val failure = assertFailsWith<CashWriteSetReferenceFailure> { prepareCashImportWriteSetEntryEdits(value, facts()) }
            assertEquals("reference_changed", failure.code)
            assertEquals("transactions[1].entry_edit.entry", failure.fieldPath)
        }
    }

    @Test fun newAndPreviouslyUnownedParentsAreNeverGroupedByNullOrMatchingText() {
        val value = changes(row("1", patch("note" to "old")))
        val new = row("2", patch("note" to "new"), null).copy(target = fresh("1"),
            values = row("2", null).values.copy(recordOrigin = "import", recordId = "new-record"),
            components = listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part"), "10", "-5", "CNY", "0", "", "", empty)))
        val both = value.copy(transactions = value.transactions + new)
        assertEquals(both, prepareCashImportWriteSetEntryEdits(both, facts()))
        val unowned = facts().copy(cashEntries = emptyList(), cashEntryMembers = emptyList())
        val separate = CashWriteSetDto(transactions = listOf(row("1", patch("note" to "one"), null), row("2", patch("note" to "two"), null)))
        assertEquals(separate, prepareCashImportWriteSetEntryEdits(separate, unowned))
    }

    @Test fun aBadLaterPatchCannotDisappearBehindTheFirstValidPatch() {
        val value = changes(row("1", patch("note" to "C")), row("2", patch("amount" to "-5")))
        val failure = assertFailsWith<CashPolicyError> { prepareCashImportWriteSetEntryEdits(value, facts()) }
        assertEquals("field_not_editable", failure.code)
        assertEquals(listOf("transactions[1].entry_edit.patch"), failure.fields)
    }

    @Test fun noEditAndEmptyCommandsDoNotCreateAnEntryPatch() {
        val value = changes(row("1", null), row("2", null))
        assertEquals(value, prepareCashImportWriteSetEntryEdits(value, facts()))
        assertEquals(CashWriteSetDto(), prepareCashImportWriteSetEntryEdits(CashWriteSetDto(), facts()))
    }

    @Test fun mergingTheOriginalValueTypeRetainsPartialMutationAndNullConflictTiming() {
        val target = linkedMapOf<String, Any?>("category_id" to null)
        val patch = linkedMapOf<String, Any?>("note" to "prefix", "category_id" to "different")
        assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { mergeCashImportEntryPatch(target, patch) }.code)
        assertEquals(linkedMapOf<String, Any?>("category_id" to null, "note" to "prefix"), target)
        mergeCashImportEntryPatch(target, mapOf("category_id" to null))
        assertTrue("category_id" in target)
    }
}
