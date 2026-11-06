package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportWriteSetRelationGroupsTest {
    private val empty = JsonObject(emptyMap())
    private val array = JsonArray(emptyList())
    private val time = "2026-01-01T00:00:00Z"
    private fun ref(id: String) = ExistingCashWriteReferenceDto(id)
    private fun fresh(id: String) = NewCashWriteReferenceDto(id)
    private fun target(id: String) = CashWriteVersionedReferenceDto(ref(id), "1")
    private fun facts() = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0").copy(
        accounts = listOf(LedgerAccountFactDto("10", "cash", "cash", true, JsonArray(listOf(JsonPrimitive("CNY"))), "1")),
        cashTransactions = listOf("1", "2", "3").map { LedgerCashTransactionFactDto(it, "10", "posted", "legacy", null, "1", "atomic", "bank",
            "record:$it", empty, empty, null, empty, time, "-5", "CNY", "", "", array, "", null, "consumption", "not_applicable", time, null, "", "") },
        cashTransactionComponents = listOf("1", "2", "3").map { LedgerCashComponentFactDto("$it$it", it, "10", "-5", "CNY", "0", "", "", empty, time) },
    )
    private fun edge(id: String = "first", primary: String = "1", secondary: String = "2", state: String = "confirmed") =
        CashRelationWriteDto(CashWriteVersionedReferenceDto(fresh(id), null), "payment_mirror", target(primary), target(secondary),
            ref("$primary$primary"), ref("$secondary$secondary"), target(primary), ref("$primary$primary"), "expense", "expense", state, true,
            null, "user", empty, "5", "rule", "user", emptyList())
    private fun group(vararg ids: String, patch: JsonObject = empty) = CashRelationWriteGroupDto(ids.map(::fresh), patch)
    private fun changes() = CashWriteSetDto(relations = listOf(edge(), edge("second", "2", "3")),
        relationGroups = listOf(group("second"), group("first")))
    private fun referenceFailure(value: CashWriteSetDto, code: String = "invalid_reference", path: String? = null) {
        val failure = assertFailsWith<CashWriteSetReferenceFailure> { validateCashImportWriteSetScope(value, facts()) }
        assertEquals(code, failure.code)
        if (path != null) assertEquals(path, failure.fieldPath)
    }

    @Test fun everyNewRelationStateRequiresACompleteNonemptyGroupPartition() {
        for (state in listOf("confirmed", "draft", "ignored")) {
            val value = CashWriteSetDto(relations = listOf(edge(state = state)), relationGroups = listOf(group("first")))
            validateCashImportWriteSetScope(value, facts())
            referenceFailure(value.copy(relationGroups = emptyList()), path = "relation_groups")
            referenceFailure(value.copy(relationGroups = listOf(group())), path = "relation_groups[0].relations")
        }
        validateCashImportWriteSetScope(CashWriteSetDto(), facts())
    }

    @Test fun groupReferencesCannotBeUnknownExistingRepeatedOrMissing() {
        val value = changes()
        validateCashImportWriteSetScope(value, facts())
        referenceFailure(value.copy(relationGroups = listOf(group("unknown"))), path = "relation_groups[0].relations[0]")
        referenceFailure(value.copy(relationGroups = listOf(CashRelationWriteGroupDto(listOf(ref("first"))))), path = "relation_groups[0].relations[0]")
        referenceFailure(value.copy(relationGroups = listOf(group("first", "first", "second"))), "duplicate_reference", "relation_groups[0].relations[1]")
        referenceFailure(value.copy(relationGroups = listOf(group("first"), group("second", "first"))), "duplicate_reference", "relation_groups[1].relations[1]")
        referenceFailure(value.copy(relationGroups = listOf(group("second"))), path = "relation_groups")
        referenceFailure(value.copy(relations = emptyList()), path = "relation_groups[0].relations[0]")
    }

    @Test fun onlyConsistentAcceptedGroupsMayCarryAPublicChoice() {
        val patch = buildJsonObject { put("note", "B") }
        for (state in listOf("draft", "ignored")) {
            val failure = assertFailsWith<CashPolicyError> {
                validateCashImportWriteSetScope(CashWriteSetDto(relations = listOf(edge(state = state)), relationGroups = listOf(group("first", patch = patch))), facts())
            }
            assertEquals("field_not_editable", failure.code)
            assertEquals(listOf("relation_groups[0].common_fields"), failure.fields)
        }
        for (second in listOf(edge("second", "2", "3", "draft"), edge("second", "2", "3").copy(active = false))) {
            val failure = assertFailsWith<CashPolicyError> {
                validateCashImportWriteSetScope(changes().copy(relations = listOf(edge(), second), relationGroups = listOf(group("first", "second"))), facts())
            }
            assertEquals("invalid_field", failure.code)
            assertEquals(listOf("relation_groups[0]"), failure.fields)
        }
        val inactive = assertFailsWith<CashPolicyError> {
            validateCashImportWriteSetScope(CashWriteSetDto(relations = listOf(edge().copy(active = false)), relationGroups = listOf(group("first", patch = patch))), facts())
        }
        assertEquals("invalid_field", inactive.code)
    }

    @Test fun fileDecisionGroupsCannotCreateInactiveHistoryEvenWithoutAPublicPatch() {
        for (state in listOf("confirmed", "draft", "ignored")) {
            val failure = assertFailsWith<CashPolicyError> {
                validateCashImportWriteSetScope(CashWriteSetDto(relations = listOf(edge(state = state).copy(active = false)),
                    relationGroups = listOf(group("first"))), facts())
            }
            assertEquals("invalid_field", failure.code)
            assertEquals(listOf("relation_groups[0]"), failure.fields)
        }
    }

    @Test fun explicitCommonCategoryMustExistEvenWithoutAFinalEntryCategory() {
        val patch = buildJsonObject { put("category_id", "missing") }
        referenceFailure(CashWriteSetDto(relations = listOf(edge()), relationGroups = listOf(group("first", patch = patch))),
            "reference_changed", "relation_groups[0].common_fields.category_id")
        validateCashImportWriteSetScope(CashWriteSetDto(relations = listOf(edge()),
            relationGroups = listOf(group("first", patch = buildJsonObject { put("category_id", JsonNull) }))), facts())
        val directory = facts().copy(cashCategories = listOf(LedgerCategoryFactDto("20", null, "", "category", "category", null, "category", 0, "0", "1", time, time)))
        validateCashImportWriteSetScope(CashWriteSetDto(relations = listOf(edge()),
            relationGroups = listOf(group("first", patch = buildJsonObject { put("category_id", "20") }))), directory)
    }

    @Test fun normalizationPreservesGroupOrderFieldPresenceAndExplicitClears() {
        val patch = buildJsonObject { put("note", "B"); put("category_id", JsonNull) }
        val value = changes().copy(relationGroups = listOf(group("second", patch = patch), group("first")))
        val normalized = normalizeCashWriteSetEntryValues(value)
        assertEquals(value.relationGroups, normalized.relationGroups)
        assertEquals(listOf(fresh("second")), normalized.relationGroups.first().relations)
        assertFalse("counterparty" in normalized.relationGroups.first().commonFields)
        assertEquals(empty, normalized.relationGroups.last().commonFields)
        val clear = buildJsonObject { put("note", ""); put("counterparty_account_attrs", array) }
        assertEquals(clear, normalizeCashWriteSetEntryValues(value.copy(relationGroups = listOf(group("first", patch = clear)))).relationGroups.single().commonFields)
    }

    @Test fun publicChoicesRejectFinancialFieldsBadLeafTypesAndNestedAttrs() {
        var nested: JsonElement = JsonPrimitive("tail:1234")
        repeat(4096) { nested = JsonArray(listOf(nested)) }
        for ((patch, code) in listOf(
            buildJsonObject { put("amount", "5") } to "field_not_editable",
            buildJsonObject { put("note", JsonNull) } to "invalid_field",
            buildJsonObject { put("category_id", true) } to "invalid_field",
            buildJsonObject { put("counterparty_account_attrs", JsonArray(listOf(empty))) } to "invalid_field",
            buildJsonObject { put("counterparty_account_attrs", nested) } to "invalid_field",
        )) {
            val failure = assertFailsWith<CashPolicyError> {
                normalizeCashWriteSetEntryValues(changes().copy(relationGroups = listOf(group("first", patch = patch))))
            }
            assertEquals(code, failure.code)
            assertTrue(failure.fields.single().startsWith("relation_groups[0].common_fields"))
        }
    }
}
