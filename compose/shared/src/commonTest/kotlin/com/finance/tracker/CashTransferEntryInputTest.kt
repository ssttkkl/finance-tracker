package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*
import kotlin.test.*

class CashTransferEntryInputTest {
    private val from: CashCurrentRecord = record("out", "-12.5000", "CNY", "1", "transfer_out")
    private val to: CashCurrentRecord = record("in", "12.5000", "CNY", "2", "transfer_in")
    @Test fun rolesComeFromCurrentSignedMembersRatherThanArrayOrder() {
        val pair = assertNotNull(cashTransferEntryMembers(to, listOf(to, from)))
        assertEquals("out", pair.outgoing.transactionId); assertEquals("in", pair.incoming.transactionId)
        assertEquals("12.5000", cashTransferEntryValues(from)["amount"])
    }
    @Test fun sameCurrencyAmountIsLinkedAndOnlyActualFinancialChangesAreSent() {
        val request = cashTransferEntryRequest(from, listOf(to, from), mapOf("note" to ""),
            cashTransferEntryValues(from) + ("amount" to "19.75000"), cashTransferEntryValues(to), "UTC")
        assertEquals("edit_entry", request.intent); assertEquals("entry-1", request.entryId); assertEquals(7L, request.expectedEntryRevision)
        assertEquals("out", request.transactionId); assertEquals(3L, request.expectedRevision)
        assertEquals(mapOf("note" to StructuredText("")), request.patch.fields)
        assertEquals(mapOf("out" to StructuredText("-19.75000"), "in" to StructuredText("19.75000")), request.memberPatches.associate { it.transactionId to it.patch.fields["amount"] })
        assertTrue(request.memberPatches.all { it.patch.fields.keys == setOf("amount") })
    }
    @Test fun exchangeKeepsIndependentExactAmountsAndBothMemberReferences() {
        val incoming = to.copy(fields = StructuredObject(to.fields.fields + ("currency" to StructuredText("USD")) + ("record_type" to StructuredText("fx_in"))))
        val outgoing = from.copy(fields = StructuredObject(from.fields.fields + ("record_type" to StructuredText("fx_out"))))
        val request = cashTransferEntryRequest(outgoing, listOf(incoming, outgoing), emptyMap(), cashTransferEntryValues(outgoing),
            cashTransferEntryValues(incoming) + ("amount" to "1.234500"), "UTC")
        assertEquals(setOf("out", "in"), request.memberPatches.map { it.transactionId }.toSet())
        assertTrue(request.memberPatches.single { it.transactionId == "out" }.patch.fields.isEmpty())
        assertEquals(StructuredText("1.234500"), request.memberPatches.single { it.transactionId == "in" }.patch.fields["amount"])
    }
    @Test fun publicOnlyChangesDoNotRewriteMemberFinancialValues() {
        val request = cashTransferEntryRequest(from, listOf(from, to), mapOf("note" to "changed"), cashTransferEntryValues(from), cashTransferEntryValues(to), "UTC")
        assertTrue(request.memberPatches.isEmpty()); assertEquals(mapOf("note" to StructuredText("changed")), request.patch.fields)
    }
    @Test fun untouchedSameCurrencyAmountKeepsEachOriginalRepresentation() {
        val incoming = to.copy(fields = StructuredObject(to.fields.fields + ("amount" to StructuredText("12.50"))))
        val request = cashTransferEntryRequest(from, listOf(from, incoming), mapOf("note" to "changed"),
            cashTransferEntryValues(from), cashTransferEntryValues(incoming), "UTC")
        assertTrue(request.memberPatches.isEmpty())
    }
    @Test fun incompleteForeignOrUneditableMembersNeverOpenFinancialEditing() {
        for (members in listOf(listOf(from), listOf(from, from), listOf(from, to.copy(recordOrigin = "import")),
            listOf(from, to.copy(entry = to.entry!!.copy(entryId = "other"))), listOf(from, to.copy(entry = to.entry!!.copy(revision = 8))),
            listOf(from, to.copy(entry = to.entry!!.copy(supportsTransferEdit = false))), listOf(from, to.copy(state = "draft")),
            listOf(from, to.copy(entry = to.entry!!.copy(editableFields = cashPublicEntryFields.toList()))),
            listOf(from.copy(rowRevision = 4), to),
            listOf(from, to.copy(fields = StructuredObject(to.fields.fields + ("manual_transfer_id" to StructuredText("other"))))))) {
            assertNull(cashTransferEntryMembers(from, members))
            assertFailsWith<DomainFailure> { cashTransferEntryRequest(from, members, emptyMap(), mapOf("amount" to "1"), mapOf("amount" to "1"), "UTC") }
        }
    }
    @Test fun invalidAmountsAndForeignFinancialFieldsNeverBuildPatch() {
        for (amount in listOf("0", "-1", "NaN", "1e4")) assertFailsWith<DomainFailure> {
            cashTransferEntryRequest(from, listOf(from, to), emptyMap(), cashTransferEntryValues(from) + ("amount" to amount), cashTransferEntryValues(to), "UTC")
        }
        assertFailsWith<DomainFailure> { cashTransferEntryRequest(from, listOf(from, to), emptyMap(),
            cashTransferEntryValues(from) + ("source_payload" to "forged"), cashTransferEntryValues(to), "UTC") }
    }
    private fun record(id: String, amount: String, currency: String, account: String, type: String): CashCurrentRecord = CashCurrentRecord("manual_transfer",
        CashPublicEntry("entry-1", 7, StructuredObject(mapOf("note" to StructuredText("transfer"))), emptyList(), cashPublicEntryFields.toList() + cashTransferFinancialFields, true),
        id,  "posted", 3, "confirmed", StructuredObject(mapOf("amount" to StructuredText(amount), "currency" to StructuredText(currency),
            "account_id" to StructuredText(account), "record_type" to StructuredText(type), "manual_transfer_id" to StructuredText("pair-1"),
            "occurred_at" to StructuredText("2026-10-24T04:30:00Z"))), emptyList(), emptyList(), emptyList(), null)
}
