package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetEntryTopologyTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun transaction(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "legacy", null, "1", "atomic", "bank",
        "record:$id", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "")
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = listOf("1", "2", "3").map(::transaction),
        cashTransactionComponents = listOf("1", "2", "3").map { LedgerCashComponentFactDto("$it$it", it, "10", "-5", "CNY", "0", "", "", empty, time) },
        transactionRelations = listOf(LedgerCashRelationFactDto("31", "payment_mirror", "1", "2", "11", "22", "1", "11", "expense", "expense",
            "confirmed", true, "1", null, "user", empty, "5", "rule", "user", time, "user", time, "")),
        cashEntries = listOf(LedgerCashEntryFactDto("old", "1", "2", "1", null, "", "", "", array, array, null)),
        cashEntryMembers = listOf("1", "2").map { LedgerCashEntryMemberFactDto(it, "old", empty, array) },
    )
    private fun group(root: String, vararg members: String) = CashEntryWriteDto(target(root), null, "", "", "", array,
        emptyList(), members.map { CashEntryMemberWriteDto(target(it), empty, emptyList()) })
    private fun changes(vararg groups: CashEntryWriteDto) = CashWriteSetDto(entries = groups.toList(), entryDependencies = listOf(target("old")))
    private fun rejected(value: CashWriteSetDto, source: LedgerFactsDto) {
        val failure = assertFailsWith<CashPolicyError> { validateCashImportWriteSetEntryTopology(value, source) }
        assertEquals("entry_result_invalid", failure.code)
        assertEquals(listOf("entry_dependencies"), failure.fields)
    }

    @Test fun theWholeOldGroupCanSurviveWithAnAnchorDifferentFromItsRoot() {
        val source = facts()
        val before = source.copy()
        validateCashImportWriteSetEntryTopology(changes(group("1", "1", "2")), source)
        assertEquals(before, source)
        assertEquals("2", source.cashEntries.single().identityTransactionId)
    }

    @Test fun aNewConfirmedRelationCanMergeBothCompleteOldGroups() {
        val source = facts().copy(transactionRelations = emptyList(), cashEntries = listOf(
            facts().cashEntries.single().copy(id = "first", identityTransactionId = "1"),
            facts().cashEntries.single().copy(id = "second", rootTransactionId = "2")),
            cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("1", "first", empty, array), LedgerCashEntryMemberFactDto("2", "second", empty, array)))
        val edge = CashRelationWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("edge"), null), "payment_mirror",
            target("1"), target("2"), ref("11"), ref("22"), target("1"), ref("11"), "expense", "expense", "confirmed", true,
            null, "user", empty, "5", "rule", "user", emptyList())
        val value = changes(group("1", "1", "2")).copy(relations = listOf(edge), entryDependencies = listOf(target("first"), target("second")))
        validateCashImportWriteSetEntryTopology(value, source)
        assertEquals(listOf("first", "second"), source.cashEntryMembers.map { it.entryId })
    }

    @Test fun aCompleteGenericSplitCannotEnterTheFileImportCommand() {
        val source = facts().copy(transactionRelations = emptyList())
        val value = changes(group("1", "1"), group("2", "2"))
        validateCashWriteSetEntryTopology(value, source)
        rejected(value, source)
        assertEquals(listOf("1", "2"), source.cashEntryMembers.map { it.transactionId })
    }

    @Test fun anOmittedDeletedOldMemberCannotBeHiddenByACompleteLiveFinalGroup() {
        val source = facts().copy(transactionRelations = emptyList(),
            cashTransactions = facts().cashTransactions.map { if (it.id == "2") it.copy(deletedAt = time) else it })
        val value = changes(group("1", "1"))
        validateCashWriteSetEntryTopology(value, source)
        rejected(value, source)
    }

    @Test fun anOldIdentityAnchorMustAlsoSurviveInsideTheSameGroup() {
        val source = facts().copy(cashEntries = facts().cashEntries.map { it.copy(identityTransactionId = "3") })
        val value = changes(group("1", "1", "2"))
        validateCashWriteSetEntryTopology(value, source)
        rejected(value, source)
    }

    @Test fun aReadDependencyAloneDoesNotRepairOrWriteAnUnrelatedOldGroup() {
        val source = facts().copy(cashEntries = listOf(facts().cashEntries.single().copy(rootTransactionId = "3", identityTransactionId = "3")),
            cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("3", "old", empty, array)),
            cashTransactions = facts().cashTransactions.map { if (it.id == "3") it.copy(deletedAt = time) else it })
        validateCashImportWriteSetEntryTopology(changes(), source)
        validateCashImportWriteSetEntryTopology(changes(group("1", "1", "2")), source)
    }

    @Test fun revisionsScopeAndTheActualFinalGraphStillFailBeforeOldGroupChecks() {
        val source = facts()
        val stale = assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashImportWriteSetEntryTopology(changes(group("1", "1", "2")).copy(
                entryDependencies = listOf(target("old").copy(expectedRevision = "2"))), source)
        }
        assertEquals("reference_changed", stale.code)
        val maintenance = assertFailsWith<CashPolicyError> {
            validateCashImportWriteSetEntryTopology(changes(group("1", "1", "2")).copy(
                deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "delete"))), source)
        }
        assertEquals("field_not_editable", maintenance.code)
        assertEquals(listOf("deleted_transactions"), maintenance.fields)
        val wrongRoot = assertFailsWith<CashPolicyError> {
            validateCashImportWriteSetEntryTopology(changes(group("2", "1", "2")), source)
        }
        assertEquals("entry_result_invalid", wrongRoot.code)
        assertEquals(listOf("entries[0]"), wrongRoot.fields)
    }
}
