package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashEntryInputTest {
    @Test fun importedEntryUsesPublicValuesAndClearsWithBothRealRevisions() {
        val current = record()
        assertEquals("公共备注", cashEntryInitialValues(current)["note"])
        val request = cashEntryRequest(current, mapOf("note" to "", "category_id" to ""), "UTC")
        assertEquals("edit_entry", request.intent)
        assertEquals("11", request.transactionId); assertEquals(3L, request.expectedRevision)
        assertEquals("entry-1", request.entryId); assertEquals(9L, request.expectedEntryRevision)
        assertEquals(mapOf("note" to StructuredText(""), "category_id" to StructuredNull), request.patch.fields)
        assertTrue(request.memberPatches.isEmpty())
    }
    @Test fun importedEntryNeverExposesOrWritesSourceFinancialFields() {
        val current = record().let { it.copy(entry = it.entry!!.copy(editableFields = it.entry!!.editableFields + listOf("amount", "source_payload"))) }
        assertEquals(setOf("note", "category_id", "counterparty", "counterparty_account", "counterparty_account_attrs"), cashEntryEditableFields(current).toSet())
        for (field in listOf("amount", "source_payload", "record_origin", "transaction_id")) {
            assertFailsWith<DomainFailure> { cashEntryRequest(current, mapOf(field to "forged"), "UTC") }
        }
    }
    @Test fun ordinaryManualEntryKeepsExactZeroAndChangedLocalTimeOnly() {
        val current = record("manual").let { it.copy(entry = it.entry!!.copy(editableFields = it.entry!!.editableFields + listOf("amount", "occurred_at", "currency", "account_id", "record_type", "record_subtype"))) }
        val request = cashEntryRequest(current, mapOf("amount" to "0.0000", "occurred_at" to "2026-10-24T12:30"), "Asia/Shanghai")
        assertEquals(setOf("amount", "occurred_at"), request.patch.fields.keys)
        assertEquals(StructuredText("0.0000"), request.patch.fields["amount"])
        assertEquals(StructuredText("2026-10-24T04:30:00Z"), request.patch.fields["occurred_at"])
        assertFalse("components" in request.patch.fields)
    }
    @Test fun transferFinancialFieldsRequireTheSeparateDoubleMemberEditor() {
        for (supports in listOf(false, true)) {
            val current = record("manual_transfer").let { it.copy(entry = it.entry!!.copy(supportsTransferEdit = supports, editableFields = listOf("note", "amount", "account_id", "occurred_at"))) }
            assertEquals(listOf("note"), cashEntryEditableFields(current))
            assertFailsWith<DomainFailure> { cashEntryRequest(current, mapOf("amount" to "12.50"), "UTC") }
            assertEquals(StructuredText("公共修改"), cashEntryRequest(current, mapOf("note" to "公共修改"), "UTC").patch.fields["note"])
        }
    }
    @Test fun missingObjectDraftAndReadonlyEntriesDoNotCreateMaintenanceRequests() {
        val current = record()
        for (value in listOf(current.copy(entry = null), current.copy(state = "draft"), current.copy(entry = current.entry!!.copy(editableFields = emptyList())))) {
            assertTrue(cashEntryEditableFields(value).isEmpty())
            assertFailsWith<DomainFailure> { cashEntryRequest(value, mapOf("note" to "修改"), "UTC") }
        }
    }
    private fun record(origin: String = "import") = CashCurrentRecord(origin,
        CashPublicEntry("entry-1", 9, StructuredObject(mapOf("note" to StructuredText("公共备注"), "category_id" to StructuredNull)), emptyList(),
            listOf("note", "category_id", "counterparty", "counterparty_account", "counterparty_account_attrs"), false),
        "11", "posted", 3, "confirmed", StructuredObject(mapOf("amount" to StructuredText("-12.500"), "currency" to StructuredText("CNY"),
            "occurred_at" to StructuredText("2026-10-01T04:00:00Z"), "note" to StructuredText("来源备注"))), emptyList(), emptyList(), emptyList(), null)
}
