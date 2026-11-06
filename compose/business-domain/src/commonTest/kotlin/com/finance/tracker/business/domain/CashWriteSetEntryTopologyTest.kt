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
    private fun entry(id: String, root: String, members: List<String>) = CashEntryWriteDto(target(id), target(root), target(root), null,
        "", "", "", array, emptyList(), null, members.map { CashEntryMemberWriteDto(target(it), empty, emptyList()) })
    private fun stored(source: LedgerFactsDto, vararg entries: CashEntryWriteDto): LedgerFactsDto = source.copy(
        cashEntries = entries.map { LedgerCashEntryFactDto((it.target.reference as ExistingCashWriteReferenceDto).id,
            (it.rootTransaction.reference as ExistingCashWriteReferenceDto).id, (it.identityTransaction.reference as ExistingCashWriteReferenceDto).id,
            "1", null, "", "", "", array, array, null) },
        cashEntryMembers = entries.flatMap { e -> e.members.map {
            LedgerCashEntryMemberFactDto((it.transaction.reference as ExistingCashWriteReferenceDto).id,
                (e.target.reference as ExistingCashWriteReferenceDto).id, empty, array)
        } })
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
        val group = entry("entry", "1", listOf("1", "2")).copy(target = fresh("entry"))
        validateCashWriteSetEntryTopology(CashWriteSetDto(entries = listOf(group)), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(rootTransaction = target("2")))), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(members = group.members.take(1)))), source)
        invalid(CashWriteSetDto(entries = listOf(group.copy(members = emptyList()))), source)
        assertEquals(emptyList(), source.cashEntries)
        assertEquals(emptyList(), source.cashEntryMembers)
    }

    @Test fun addingARelationCannotLeaveTheOldEntriesUnmerged() {
        val source = stored(facts().copy(transactionRelations = emptyList()), entry("first", "1", listOf("1")), entry("second", "2", listOf("2")))
        invalid(CashWriteSetDto(relations = listOf(edge(created = true))), source)
        val changes = CashWriteSetDto(relations = listOf(edge(created = true)), entries = listOf(entry("first", "1", listOf("1", "2"))),
            deletedEntries = listOf(target("second")))
        validateCashWriteSetEntryTopology(changes, source)
        assertEquals(listOf("first", "second"), source.cashEntries.map { it.id })
    }

    @Test fun removingARelationRequiresBothFinalGroupsIncludingTheNewEntry() {
        val source = stored(facts(), entry("old", "1", listOf("1", "2")))
        invalid(CashWriteSetDto(relations = listOf(edge(active = false))), source)
        val first = entry("old", "1", listOf("1"))
        invalid(CashWriteSetDto(relations = listOf(edge(active = false)), entries = listOf(first)), source)
        val second = entry("new", "2", listOf("2")).copy(target = fresh("new"))
        validateCashWriteSetEntryTopology(CashWriteSetDto(relations = listOf(edge(active = false)), entries = listOf(second, first)), source)
    }

    @Test fun bindingOnlyChangesStillRequireTheNewRepresentativeRoot() {
        val source = stored(facts().copy(cashSourceBindings = listOf(binding("41", "1", "z"), binding("42", "2", "a"))),
            entry("old", "2", listOf("1", "2")))
        val changed = write(binding("41", "1", "0"))
        invalid(CashWriteSetDto(sourceBindings = listOf(changed)), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(sourceBindings = listOf(changed),
            entries = listOf(entry("old", "1", listOf("1", "2")).copy(identityTransaction = target("2")))), source)
    }

    @Test fun movingABindingChecksItsPreviousOwnerEvenWhenTheNewGroupIsComplete() {
        val source = stored(facts().copy(cashTransactions = (1..4).map { transaction("$it") }.map { if (it.id == "2") it.copy(recordId = "0") else it },
            cashTransactionComponents = (1..4).map { component("$it") }, transactionRelations = listOf(relation(), relation("32", "3", "4")),
            cashSourceBindings = listOf(binding("41", "1", "provider"))), entry("old", "1", listOf("1", "2")))
        val changed = write(binding("41", "3", "provider"))
        val newGroup = entry("new", "3", listOf("3", "4")).copy(target = fresh("new"))
        invalid(CashWriteSetDto(sourceBindings = listOf(changed), entries = listOf(newGroup)), source)
        validateCashWriteSetEntryTopology(CashWriteSetDto(sourceBindings = listOf(changed),
            entries = listOf(newGroup, entry("old", "2", listOf("1", "2")).copy(identityTransaction = target("1")))), source)
    }

    @Test fun deletedTransactionsCannotRemainInAnEntry() {
        val source = stored(facts().copy(transactionRelations = emptyList()), entry("old", "1", listOf("1")))
        val changes = CashWriteSetDto(deletedTransactions = listOf(CashTransactionDeleteDto(target("1"), "delete")))
        invalid(changes, source)
        validateCashWriteSetEntryTopology(changes.copy(deletedEntries = listOf(target("old"))), source)
    }

    @Test fun anUntimedNewDraftIsASingletonAndCannotAbsorbAnExistingPostedMember() {
        val row = CashTransactionWriteDto(fresh("1"), CashTransactionWriteValuesDto(null, "draft", "import", null, "atomic", "bank", "new",
            empty, empty, null, empty, null, null, null, "", "", array, "", null, null, null),
            listOf(CashComponentWriteDto(NewCashWriteReferenceDto("part"), null, null, null, "0", "", "", empty)))
        val singleton = entry("new", "1", listOf("1")).copy(target = fresh("new"), rootTransaction = fresh("1"), identityTransaction = fresh("1"),
            members = listOf(CashEntryMemberWriteDto(fresh("1"), empty, emptyList())))
        val changes = CashWriteSetDto(transactions = listOf(row), entries = listOf(singleton))
        validateCashWriteSetEntryTopology(changes, facts())
        invalid(changes.copy(entries = listOf(singleton.copy(members = singleton.members + CashEntryMemberWriteDto(target("1"), empty, emptyList())))), facts())
        invalid(changes.copy(entries = listOf(singleton.copy(identityTransaction = target("1")))), facts())
        invalid(changes.copy(entries = emptyList()), facts())
    }

    @Test fun unrelatedUnmaterializedFactsAreNotRepairedButAnExplicitEmptyEntryIsRejected() {
        val source = facts().copy(transactionRelations = emptyList())
        validateCashWriteSetEntryTopology(CashWriteSetDto(), source)
        val emptyEntry = entry("empty", "1", emptyList()).copy(target = fresh("empty"))
        invalid(CashWriteSetDto(entries = listOf(emptyEntry)), source)
        val changes = CashWriteSetDto(entries = listOf(entry("single", "1", listOf("1")).copy(target = fresh("single"))))
        validateCashWriteSetEntryTopology(changes, source)
    }

    @Test fun originalReferenceAndConfirmedGraphFailuresStillPrecedeEntryTopology() {
        val source = stored(facts(), entry("old", "1", listOf("1", "2")))
        val bad = entry("old", "1", emptyList()).copy(target = CashWriteVersionedReferenceDto(ref("old"), "2"))
        assertFailsWith<CashWriteSetReferenceFailure> { validateCashWriteSetEntryTopology(CashWriteSetDto(entries = listOf(bad)), source) }
        val wrongAmount = edge().copy(appliedAmount = "6")
        assertFailsWith<CashRelationFailure> { validateCashWriteSetEntryTopology(CashWriteSetDto(relations = listOf(wrongAmount),
            entries = listOf(bad.copy(target = target("old")))), source) }
    }
}
