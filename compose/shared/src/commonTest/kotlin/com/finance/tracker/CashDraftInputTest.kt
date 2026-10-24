package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashDraftPatch
import com.finance.tracker.presentation.CashDraftAllocation
import com.finance.tracker.presentation.CashDraftAllocationInput
import com.finance.tracker.presentation.localDateTimeWithDate
import com.finance.tracker.presentation.localDateTimeWithTime
import kotlin.test.*

class CashDraftInputTest {
    @Test fun switchingBackToSingleNeverSubmitsHiddenSplitEdits() {
        val first = CashDraftAllocation("31", 4, "101", "-10.00", "CNY")
        val added = CashDraftAllocation(null, 0, "102", "-2", "CNY")
        val initial = listOf(first)
        val split = CashDraftAllocationInput(initial).switchMode().changeParts(initial + added)
        assertEquals(initial + added, split.patchParts(true, emptyMap(), initial))
        assertNull(split.switchMode().patchParts(false, emptyMap(), initial))
        assertNull(split.changeParts(emptyList()).switchMode().patchParts(false, emptyMap(), initial))
    }
    @Test fun usingSingleFieldsDoesNotCarryConsentAcrossModeSwitches() {
        val initial = emptyList<CashDraftAllocation>()
        val fields = mapOf("account_id" to "101", "amount" to "-12.500", "currency" to "CNY")
        val chosen = CashDraftAllocationInput(initial).chooseSingle()
        assertEquals("-12.500", chosen.patchParts(false, fields, initial)!!.single().amount)
        val switched = chosen.switchMode().changeParts(listOf(CashDraftAllocation(null, 2, "102", "-1", "CNY"))).switchMode()
        assertNull(switched.patchParts(false, fields, initial))
        assertEquals("101", switched.chooseSingle().patchParts(false, fields, initial)!!.single().accountId)
    }
    @Test fun explicitAllocationsPreserveComponentIdentityOrderAndExactAmounts() {
        val current = record().copy(editableFields = record().editableFields + "components")
        val patch = cashDraftPatch(current, emptyMap(), "UTC", listOf(
            CashDraftAllocation("9007199254740993", 4, "101", "12345678901234567890.0100", "CNY"),
            CashDraftAllocation(null, 9, "", "", "")))
        val parts = (patch.fields["components"] as? StructuredArray)?.values.orEmpty()
        assertEquals(2, parts.size)
        val existing = (parts.first() as StructuredObject).fields
        assertEquals(StructuredText("9007199254740993"), existing["component_id"])
        assertEquals(StructuredNumber("4"), existing["ordinal"])
        assertEquals(StructuredText("12345678901234567890.0100"), existing["amount"])
        val added = (parts.last() as StructuredObject).fields
        assertFalse("component_id" in added)
        for (field in listOf("account_id", "amount", "currency")) assertEquals(StructuredNull, added[field])
        assertEquals(setOf("components"), patch.fields.keys)
    }
    @Test fun explicitEmptyAllocationsStayEmptyAndReadonlyOrMalformedPartsAreRejected() {
        val current = record().copy(editableFields = record().editableFields + "components")
        assertEquals(StructuredArray(emptyList()), cashDraftPatch(current, emptyMap(), "UTC", emptyList()).fields["components"])
        assertFailsWith<DomainFailure> { cashDraftPatch(record(), emptyMap(), "UTC", emptyList()) }
        assertFailsWith<DomainFailure> { cashDraftPatch(current, emptyMap(), "UTC", listOf(CashDraftAllocation(null, 0, "101", "1e3", "CNY"))) }
        assertFailsWith<DomainFailure> { cashDraftPatch(current, emptyMap(), "UTC", listOf(CashDraftAllocation(null, 0, "101", "2", "CN"))) }
    }
    @Test fun accountOnlyChangeDoesNotPretendOldFinancialValuesWereChosen() {
        val current = record().copy(editableFields = record().editableFields + listOf("account_id", "components"),
            fields = StructuredObject(record().fields.fields + ("components" to StructuredArray(listOf(StructuredObject(mapOf(
                "component_id" to StructuredText("31"), "ordinal" to StructuredNumber("0"), "amount" to StructuredText("-10"), "currency" to StructuredText("CNY"))))))))
        assertEquals(setOf("account_id"), cashDraftPatch(current, mapOf("account_id" to "101"), "Asia/Shanghai").fields.keys)
    }
    @Test fun pickerClearProducesExplicitUnknownRatherThanPartialTimestamp() {
        val dateCleared = localDateTimeWithDate("2026-10-24T12:30", "", allowEmpty = true)
        val timeCleared = localDateTimeWithTime("2026-10-24T12:30", "", allowEmpty = true)
        assertEquals("", dateCleared); assertEquals("", timeCleared)
        assertEquals(StructuredNull, cashDraftPatch(record(), mapOf("occurred_at" to dateCleared), "Asia/Shanghai").fields["occurred_at"])
        assertEquals("2026-10-25T12:30", localDateTimeWithDate("2026-10-24T12:30", "2026-10-25", true))
    }
    @Test fun noChangeKeepsOriginalTimestampAndPreciseAmount() {
        assertTrue(cashDraftPatch(record(), emptyMap(), "Asia/Shanghai").fields.isEmpty())
    }
    @Test fun zeroAndExplicitClearsRemainExact() {
        val patch = cashDraftPatch(record(), mapOf("amount" to "0.00", "note" to "", "category_id" to "", "currency" to ""), "Asia/Shanghai")
        assertEquals(StructuredText("0.00"), patch.fields["amount"])
        assertEquals(StructuredText(""), patch.fields["note"])
        assertEquals(StructuredNull, patch.fields["category_id"])
        assertEquals(StructuredNull, patch.fields["currency"])
    }
    @Test fun localTimeIsConvertedAndInvalidOrReadonlyInputRejected() {
        assertEquals(StructuredText("2026-10-24T04:30:00Z"), cashDraftPatch(record(), mapOf("occurred_at" to "2026-10-24T12:30"), "Asia/Shanghai").fields["occurred_at"])
        assertFailsWith<DomainFailure> { cashDraftPatch(record(), mapOf("occurred_at" to "2026-03-08T02:30"), "America/New_York") }
        assertFailsWith<DomainFailure> { cashDraftPatch(record(), mapOf("record_origin" to "manual"), "UTC") }
        assertFailsWith<DomainFailure> { cashDraftPatch(record(), mapOf("amount" to "1e3"), "UTC") }
    }
    private fun record() = CashCurrentRecord("import", null, "11", "draft", 3, "pending",
        StructuredObject(mapOf("occurred_at" to StructuredText("2026-10-01T01:02:03.123Z"), "amount" to StructuredText("12345678901234567890.0100"), "note" to StructuredText("来源备注"), "category_id" to StructuredText("31"))),
        listOf("occurred_at", "amount", "note", "currency", "category_id"), emptyList(), emptyList(), null)
}
