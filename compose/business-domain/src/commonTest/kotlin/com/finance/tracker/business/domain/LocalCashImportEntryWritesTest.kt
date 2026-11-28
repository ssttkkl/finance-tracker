package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportEntryWritesTest {
    private fun calculated(action: String? = null, alternatives: Boolean = false, multipleRows: Boolean = false) =
        LocalCashImportRelationWritesTest().calculate(action = action, alternatives = alternatives, multipleRows = multipleRows)

    @Test fun deferredCandidateContextDoesNotWriteUnrelatedOldEntries() {
        val (source, plan, changes) = calculated(alternatives = true)
        val result = buildLocalCashImportEntryWrites(source, plan, changes)
        val entry = result.entries.single()
        assertEquals(changes.transactions.single().target, entry.rootTransaction)
        assertEquals("Refund S", entry.note); assertTrue(entry.manualFields.isEmpty())
        assertEquals(listOf(entry.rootTransaction), entry.members.map { it.transaction })
        assertEquals("Refund S", entry.members.single().standaloneValues.jsonObject.getValue("note").jsonPrimitive.content)
        assertTrue(result.entryDependencies.isEmpty())
        validateCashImportWriteSetEntryTopology(result, source.facts)
        assertTrue(changes.entries.isEmpty()); assertTrue(changes.entryDependencies.isEmpty())
    }

    @Test fun generatedGroupsDoNotExpandTheAlreadySelectedTopology() {
        for (action in listOf(null, "accept")) {
            val (source, plan, input) = calculated(action, multipleRows = true)
            val aliases = CashWriteSetSourceAliases()
            val observed = input.copy(entryDependencies = source.facts.cashEntries.map {
                CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), it.revision)
            })
            val prepared = prepareCashImportWriteSetEntryEdits(observed, source.facts)
            val topology = prepareCashWriteSetEntryTopology(prepared, source.facts)
            val result = buildLocalCashImportEntryWrites(source, plan, input)
            assertEquals(topology, prepareCashWriteSetEntryTopology(result, source.facts))
            val missing = result.copy(entries = emptyList())
            val rebuilt = assertFailsWith<CashPolicyError> { validateCashImportWriteSetEntryTopology(missing, source.facts, aliases) }
            val reused = assertFailsWith<CashPolicyError> { validateCashImportWriteSetEntryTopology(missing, source.facts, aliases, topology) }
            assertEquals(rebuilt.code, reused.code)
            assertEquals(rebuilt.fields, reused.fields)
        }
    }

    @Test fun sourcePreEditCommonChoiceAndFinalEditKeepSeparateBusinessResults() {
        val (source, original, input) = calculated("accept")
        val sourceBefore = source.copy(); val inputBefore = input.copy()
        val changes = input.copy(transactions = input.transactions.map { it.copy(entryEdit = CashEntryBeforeRelationEditDto(null,
            buildJsonObject { put("note", "C") })) })
        val editRef = original.getValue("entry_targets").jsonObject.keys.single()
        val plan = applyLocalCashImportEntryChoices(original, buildJsonArray { add(buildJsonObject {
            put("edit_ref", editRef); put("patch", buildJsonObject { put("note", "D") })
        }) }, original, emptySet())
        val result = buildLocalCashImportEntryWrites(source, plan, changes)
        val entry = result.entries.single()
        assertEquals("Refund S", result.transactions.single().values.note)
        assertEquals("Chosen B", result.relationGroups.single().commonFields.getValue("note").jsonPrimitive.content)
        assertEquals("D", entry.note); assertEquals(listOf("note"), entry.editedFields); assertEquals(listOf("note"), entry.manualFields)
        val member = entry.members.single { it.transaction == input.transactions.single().target }
        assertEquals("C", member.standaloneValues.jsonObject.getValue("note").jsonPrimitive.content)
        assertEquals(listOf("note"), member.standaloneManualFields)
        assertEquals(listOf(CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto("entry"), "9007199254740996")), result.entryDependencies)
        validateCashImportWriteSetEntryTopology(result, source.facts)
        assertEquals(sourceBefore, source); assertEquals(inputBefore, input)
    }

    @Test fun jointRefundsDeclareAllMembersWithOneResultAndExactOldObservations() {
        val (source, plan, input) = calculated("accept", multipleRows = true)
        val result = buildLocalCashImportEntryWrites(source, plan, input)
        assertEquals(3, result.entries.single().members.size)
        assertEquals("Chosen B", result.entries.single().note)
        assertEquals(1, result.entryDependencies.size)
        assertTrue(result.entries.single().members.all { it.standaloneValues.jsonObject.keys == commonCashFields })
        validateCashImportWriteSetEntryTopology(result, source.facts)
    }

    @Test fun explicitFinalEditCanSelectAnOldDisplayedGroupWithoutAChangedSourceRow() {
        val (source, original, input) = calculated()
        val target = original.getValue("entry_targets").jsonObject.entries.single {
            it.value.jsonObject.getValue("parent_ids").jsonArray == JsonArray(listOf(JsonPrimitive("1")))
        }.key
        val plan = applyLocalCashImportEntryChoices(original, buildJsonArray { add(buildJsonObject {
            put("edit_ref", target); put("patch", buildJsonObject { put("note", "Context D") })
        }) }, original, emptySet())
        val result = buildLocalCashImportEntryWrites(source, plan, input)
        assertEquals(2, result.entries.size)
        val existing = result.entries.single { it.rootTransaction.reference == ExistingCashWriteReferenceDto("1") }
        assertEquals("Context D", existing.note); assertEquals(listOf("note"), existing.editedFields)
        assertEquals(1, result.entryDependencies.size)
        assertEquals(input.transactions, result.transactions)
        validateCashImportWriteSetEntryTopology(result, source.facts)
    }

    @Test fun untimedDraftNeedsNeitherSyntheticTimeNorAFormalProjection() {
        val (source, plan, input) = calculated()
        val transaction = input.transactions.single().let { it.copy(values = it.values.copy(state = "draft", occurredAt = null)) }
        val changes = input.copy(transactions = listOf(transaction), relations = emptyList(), relationGroups = emptyList())
        val result = buildLocalCashImportEntryWrites(source, plan, changes)
        assertNull(result.transactions.single().values.occurredAt)
        assertEquals(listOf(transaction.target), result.entries.single().members.map { it.transaction })
        validateCashImportWriteSetEntryTopology(result, source.facts)
    }

    @Test fun allFinalPublicFieldsKeepExplicitClearingAndTheirManualFlags() {
        val (source, original, input) = calculated("accept")
        val patch = buildJsonObject {
            put("category_id", JsonNull); put("note", ""); put("counterparty", "")
            put("counterparty_account", "0001"); put("counterparty_account_attrs", JsonArray(emptyList()))
        }
        val plan = applyLocalCashImportEntryChoices(original, buildJsonArray { add(buildJsonObject {
            put("edit_ref", original.getValue("entry_targets").jsonObject.keys.single()); put("patch", patch)
        }) }, original, emptySet())
        val entry = buildLocalCashImportEntryWrites(source, plan, input).entries.single()
        assertNull(entry.categoryId); assertEquals("", entry.note); assertEquals("", entry.counterparty)
        assertEquals("0001", entry.counterpartyAccount); assertEquals(JsonArray(emptyList()), entry.counterpartyAccountAttrs)
        assertEquals(commonCashFields, entry.manualFields.toSet()); assertEquals(commonCashFields, entry.editedFields.toSet())
        assertEquals("Refund S", input.transactions.single().values.note)
        assertTrue(entry.members.none { it.standaloneManualFields.toSet() == commonCashFields })
    }
}
