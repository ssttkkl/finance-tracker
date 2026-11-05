package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashEntryEditRulesTest {
    private val rules = CashEntryEditRules(LocalCashRecordValues)
    private fun values(note: String) = buildJsonObject {
        put("category_id", JsonNull); put("note", note); put("counterparty", "merchant")
        put("counterparty_account", ""); put("counterparty_account_attrs", JsonArray(emptyList()))
    }
    private fun entry() = CashEntry("entry", 1L, 1L, 7L, values("old"), emptyList(), null)

    @Test fun clearAndSameValueProtectionAreAppliedOnceAndRepeatedPatchIsANoop() {
        for (note in listOf("", "old", "new")) {
            val entry = entry(); val member = CashEntryMember(1L, entry.id, values("independent"), emptyList())
            var queries = 0
            assertTrue(rules.patch(entry, mapOf("note" to note), 7) {
                queries++; assertEquals(8L, entry.revision); listOf(member)
            })
            assertEquals(values(note), entry.values); assertEquals(listOf("note"), entry.manualFields)
            assertEquals(values(note), member.standaloneValues); assertEquals(listOf("note"), member.standaloneManualFields)
            assertFalse(rules.patch(entry, mapOf("note" to note), 8) { error("unchanged edit must not read members") })
            assertEquals(1, queries); assertEquals(8L, entry.revision)
        }
    }

    @Test fun groupEditKeepsIndependentMemberValuesAndProtection() {
        val entry = entry()
        val members = (1L..2L).map { CashEntryMember(it, entry.id, values("independent-$it"), listOf("counterparty")) }
        val before = members.map { it.copy() }
        assertTrue(rules.patch(entry, mapOf("note" to "public"), 7) { members })
        assertEquals(values("public"), entry.values); assertEquals(listOf("note"), entry.manualFields)
        assertEquals(before, members)
    }

    @Test fun staleRevisionWinsOverInvalidFieldsAndNeitherFailureChangesTheEntry() {
        val entry = entry(); val before = entry.copy()
        val stale = assertFailsWith<CashPolicyError> {
            rules.patch(entry, mapOf("amount" to "10"), null) { error("unexpected member query") }
        }
        assertEquals("entry_changed", stale.code)
        for (patch in listOf(mapOf("amount" to "10"), listOf("note"), mapOf(1 to "bad"))) {
            val failure = assertFailsWith<CashPolicyError> { rules.patch(entry, patch, 7) { error("unexpected query") } }
            assertEquals("field_not_editable", failure.code)
            assertEquals(before, entry)
        }
        assertFailsWith<InvalidCashValue> { rules.patch(entry, mapOf("note" to null), 7) { error("unexpected query") } }
        assertEquals(before, entry)
    }

    @Test fun allFieldsNormalizeBeforeMutationAndEmptyPatchDoesNotQueryMembers() {
        val entry = entry(); val before = entry.copy()
        assertFailsWith<InvalidCashValue> {
            rules.patch(entry, linkedMapOf("note" to "valid", "counterparty_account_attrs" to listOf(123)), 7) { error("unexpected query") }
        }
        assertEquals(before, entry)
        assertFalse(rules.patch(entry, emptyMap<String, Any?>(), 7) { error("unexpected query") })
        assertEquals(before, entry)
    }

    @Test fun overflowKeepsTheOriginalMutationOrderAndDoesNotQueryMembers() {
        val entry = entry().apply { revision = Long.MAX_VALUE }
        assertFailsWith<ArithmeticException> {
            rules.patch(entry, mapOf("note" to "new"), Long.MAX_VALUE) { error("unexpected query") }
        }
        assertEquals(Long.MAX_VALUE, entry.revision)
        assertEquals(values("new"), entry.values); assertEquals(listOf("note"), entry.manualFields)
    }

    @Test fun memberReadProgrammingFailurePropagatesWithoutPretendingTheEditWasRolledBack() {
        val entry = entry(); val fault = IllegalStateException("member query failed")
        val actual = assertFailsWith<IllegalStateException> { rules.patch(entry, mapOf("note" to "new"), 7) { throw fault } }
        assertSame(fault, actual)
        assertEquals(8L, entry.revision); assertEquals(values("new"), entry.values)
    }
}
