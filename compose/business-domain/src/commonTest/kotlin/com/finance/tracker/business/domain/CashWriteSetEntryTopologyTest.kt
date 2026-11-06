package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashWriteSetEntryTopologyTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun fresh(id: String) = CashWriteVersionedReferenceDto(NewCashWriteReferenceDto(id), null)
    private fun transaction(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "legacy", null, "1", "atomic", "bank",
        "record:$id", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "")
    private fun component(id: String) = LedgerCashComponentFactDto("$id$id", id, "10", "-5", "CNY", "0", "", "", empty, time)
    private fun relation(id: String = "31", first: String = "1", second: String = "2") = LedgerCashRelationFactDto(id, "payment_mirror",
        first, second, "$first$first", "$second$second", first, "$first$first", "expense", "expense", "confirmed", true, "1", null,
        "user", empty, "5", "rule", "user", time, "user", time, "")
    private fun facts() = LedgerFactsDto(listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        emptyList(), emptyList(), emptyList(), listOf(transaction("1"), transaction("2")), listOf(component("1"), component("2")),
        emptyList(), emptyList(), listOf(relation()), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
    private fun entry(root: String, members: List<String>) = CashEntryWriteDto(target(root), null,
        "", "", "", array, emptyList(), members.map { CashEntryMemberWriteDto(target(it), empty, emptyList()) })
    private data class OldEntry(val id: String, val root: String, val members: List<String>)
    private fun stored(source: LedgerFactsDto, vararg entries: OldEntry): LedgerFactsDto = source.copy(
        cashEntries = entries.map { LedgerCashEntryFactDto(it.id, it.root, it.root, "1", null, "", "", "", array, array, null) },
        cashEntryMembers = entries.flatMap { e -> e.members.map { LedgerCashEntryMemberFactDto(it, e.id, empty, array) } })
    private fun edge(active: Boolean = true, created: Boolean = false) = CashRelationWriteDto(if (created) fresh("edge") else target("31"),
        "payment_mirror", target("1"), target("2"), ref("11"), ref("22"), target("1"), ref("11"), "expense", "expense", "confirmed",
        active, null, "user", empty, "5", "rule", "user", emptyList())
    private fun binding(id: String, owner: String, key: String) = LedgerSourceBindingFactDto(id, owner, "bank", "account", "scope",
        "provider_id", key, "strong", null, "1", empty, "1", time, time)
    private fun write(binding: LedgerSourceBindingFactDto) = CashSourceBindingWriteDto(target(binding.id), ref(binding.transactionId),
        binding.sourceType, binding.sourceScopeKind, binding.sourceScopeKey, binding.identityKind, binding.identityKey,
        binding.identityStrength, binding.stableEvidenceKey, binding.strategyVersion, binding.coverage)
    private fun invalid(changes: CashWriteSetDto, source: LedgerFactsDto) {
        val failure = assertFailsWith<CashPolicyError> { validateCashWriteSetEntryTopology(changes, source) }
        assertEquals("entry_result_invalid", failure.code)
        assertTrue(failure.fields.isNotEmpty())
    }

    @Test fun explicitEntriesRequireCompleteConfirmedMembersAndTheActualRoot() {
        val source = facts()
        val group = entry("1", listOf("1", "2"))
        validateCashWriteSetEntryTopology(CashWriteSetDto(entries = listOf(group)), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(rootTransaction = target("2")))), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(members = group.members.take(1)))), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(members = emptyList()))), source)
        assertEquals(emptyList(), source.cashEntries)
        assertEquals(emptyList(), source.cashEntryMembers)
    }

    @Test fun addingARelationRequiresTheCompleteGroupAndBothOldEntryDependencies() {
        val source = stored(facts().copy(transactionRelations = emptyList()), OldEntry("first", "1", listOf("1")), OldEntry("second", "2", listOf("2")))
        invalid(CashWriteSetDto(relations = listOf(edge(created = true))), source)
        val changes = CashWriteSetDto(relations = listOf(edge(created = true)), entries = listOf(entry("1", listOf("1", "2"))),
            entryDependencies = listOf(target("first"), target("second")))
        validateCashWriteSetEntryTopology(changes, source)
        val omitted = assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashWriteSetEntryTopology(changes.copy(entryDependencies = listOf(target("first"))), source)
        }
        assertEquals("invalid_reference", omitted.code)
        assertEquals("entry_dependencies", omitted.fieldPath)
        assertEquals(listOf("first", "second"), source.cashEntries.map { it.id })
    }

    @Test fun genericSplitTopologyStillRequiresBothFinalBusinessGroups() {
        val source = stored(facts(), OldEntry("old", "1", listOf("1", "2")))
        invalid(CashWriteSetDto(relations = listOf(edge(active = false)), entryDependencies = listOf(target("old"))), source)
        val first = entry("1", listOf("1"))
        invalid(CashWriteSetDto(relations = listOf(edge(active = false)), entries = listOf(first), entryDependencies = listOf(target("old"))), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(relations = listOf(edge(active = false)), entries = listOf(entry("2", listOf("2")), first),
            entryDependencies = listOf(target("old"))), source)
    }

    @Test fun bindingOnlyChangesStillRequireTheNewRepresentativeRoot() {
        val source = stored(facts().copy(cashSourceBindings = listOf(binding("41", "1", "z"), binding("42", "2", "a"))), OldEntry("old", "2", listOf("1", "2")))
        val changed = write(binding("41", "1", "0"))
        invalid(CashWriteSetDto(sourceBindings = listOf(changed)), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(sourceBindings = listOf(changed), entries = listOf(entry("1", listOf("1", "2"))),
            entryDependencies = listOf(target("old"))), source)
    }

    @Test fun movingABindingChecksItsPreviousOwnerEvenWhenTheNewGroupIsComplete() {
        val source = stored(facts().copy(cashTransactions = (1..4).map { transaction("$it") }.map { if (it.id == "2") it.copy(recordId = "0") else it },
            cashTransactionComponents = (1..4).map { component("$it") }, transactionRelations = listOf(relation(), relation("32", "3", "4")),
            cashSourceBindings = listOf(binding("41", "1", "provider"))), OldEntry("old", "1", listOf("1", "2")))
        val changed = write(binding("41", "3", "provider"))
        val newGroup = entry("3", listOf("3", "4"))
        invalid(CashWriteSetDto(sourceBindings = listOf(changed), entries = listOf(newGroup)), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(sourceBindings = listOf(changed), entries = listOf(newGroup, entry("2", listOf("1", "2"))),
            entryDependencies = listOf(target("old"))), source)
    }

    @Test fun retiredGroupsNeedTheOldRevisionWithoutAClientDeletionInstruction() {
        val source = stored(facts().copy(transactionRelations = emptyList()), OldEntry("old", "1", listOf("1")))
        val changes = CashWriteSetDto(deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "delete")), entryDependencies = listOf(target("old")))
        validateCashWriteSetEntryTopology(changes, source)
        invalid(changes.copy(entries = listOf(entry("1", listOf("1")))), source)
        val omitted = assertFailsWith<CashWriteSetReferenceFailure> { validateCashWriteSetEntryTopology(changes.copy(entryDependencies = emptyList()), source) }
        assertEquals("invalid_reference", omitted.code)
        assertEquals("entry_dependencies", omitted.fieldPath)
    }

    @Test fun anUntimedNewDraftIsASingletonAndCannotAbsorbAnExistingPostedMember() {
        val row = CashTransactionWriteDto(fresh("1"), CashTransactionWriteValuesDto(null, "draft", "import", null, "atomic", "bank", "new",
            empty, empty, null, empty, null, null, null, "", "", array, "", null, null, null),
            listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part"), null, null, null, "0", "", "", empty)))
        val singleton = entry("1", listOf("1")).copy(rootTransaction = fresh("1"), members = listOf(CashEntryMemberWriteDto(fresh("1"), empty, emptyList())))
        val changes = CashWriteSetDto(transactions = listOf(row), entries = listOf(singleton))
        validateCashWriteSetEntryTopology(changes, facts())
        invalid(changes.copy(entries = listOf(singleton.copy(members = singleton.members + CashEntryMemberWriteDto(target("1"), empty, emptyList())))), facts())
        invalid(changes.copy(entries = listOf(singleton.copy(rootTransaction = target("1")))), facts())
        invalid(changes.copy(entries = emptyList()), facts())
    }

    @Test fun unrelatedUnmaterializedFactsAreNotRepairedButAnExplicitEmptyEntryIsRejected() {
        val source = facts().copy(transactionRelations = emptyList())
        validateCashWriteSetEntryTopology(CashWriteSetDto(), source)
        invalid(CashWriteSetDto(entries = listOf(entry("1", emptyList()))), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(entries = listOf(entry("1", listOf("1")))), source)
    }

    @Test fun originalReferenceAndConfirmedGraphFailuresStillPrecedeEntryTopology() {
        val source = stored(facts(), OldEntry("old", "1", listOf("1", "2")))
        val bad = entry("1", emptyList())
        assertFailsWith<CashWriteSetReferenceFailure> {
            validateCashWriteSetEntryTopology(CashWriteSetDto(entries = listOf(bad), entryDependencies = listOf(target("old").copy(expectedRevision = "2"))), source)
        }
        assertFailsWith<CashRelationFailure> {
            validateCashWriteSetEntryTopology(CashWriteSetDto(relations = listOf(edge().copy(appliedAmount = "6")), entries = listOf(bad),
                entryDependencies = listOf(target("old"))), source)
        }
    }
}
