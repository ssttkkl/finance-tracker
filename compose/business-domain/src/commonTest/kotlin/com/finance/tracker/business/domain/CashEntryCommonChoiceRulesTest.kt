package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashEntryCommonChoiceRulesTest {
    private val rules = CashEntryEditRules(LocalCashRecordValues)
    private fun values(note: String) = buildJsonObject {
        put("category_id", JsonNull); put("note", note); put("counterparty", "merchant")
        put("counterparty_account", ""); put("counterparty_account_attrs", JsonArray(emptyList()))
    }
    private fun entry() = CashEntry("entry", 1L, 1L, 7L, values("old"), emptyList(), null)

    @Test fun ignoredFieldsAndEmptyPatchesDoNotReadEntriesOrRequestFlush() {
        for (patch in listOf(emptyMap(), mapOf("amount" to listOf("not a public value")))) {
            assertFalse(rules.chooseCommon(listOf(1L), patch,
                { error("unexpected category query") }, { error("unexpected entry read") }))
        }
        // The original native wrapper still flushes a nonempty patch even without selected parents.
        assertTrue(rules.chooseCommon(emptyList<Long>(), mapOf("note" to ""),
            { error("unexpected category query") }, { error("unexpected entry read") }))
    }

    @Test fun categoryChecksPrecedeParentReadsEvenForEmptyParentSets() {
        val fault = CashPolicyError("category_unavailable", listOf("category_id"))
        for (ids in listOf(emptyList(), listOf(1L))) {
            val failure = assertFailsWith<CashPolicyError> {
                rules.chooseCommon(ids, mapOf("category_id" to "private"),
                    { assertEquals("private", it); throw fault }, { error("unexpected entry read") })
            }
            assertSame(fault, failure)
        }
        var checked: String? = null
        assertTrue(rules.chooseCommon(emptyList<Long>(), mapOf("category_id" to ""), { checked = it }, { error("unexpected read") }))
        assertEquals("", checked)
        assertTrue(rules.chooseCommon(emptyList<Long>(), mapOf("category_id" to null),
            { error("explicit clearing must not query a category") }, { error("unexpected read") }))
    }

    @Test fun completeNormalizationPrecedesCategoryChecksAndAnyEntryMutation() {
        val entry = entry(); val before = entry.copy()
        assertFailsWith<InvalidCashValue> {
            rules.chooseCommon(listOf(1L), linkedMapOf("category_id" to "private", "note" to "new", "counterparty_account_attrs" to listOf(123)),
                { error("normalization must fail first") }, { error("unexpected entry read") })
        }
        assertEquals(before, entry)
    }

    @Test fun distinctParentsKeepFirstOccurrenceOrderAndMayAdvanceTheSameEntryTwice() {
        val entry = entry(); val order = mutableListOf<Long>()
        assertTrue(rules.chooseCommon(listOf(2L, 1L, 2L), mapOf("note" to "old", "amount" to "ignored"),
            { error("unexpected category query") }, { order += it; entry }))
        assertEquals(listOf(2L, 1L), order)
        assertEquals(9L, entry.revision)
        assertEquals(values("old"), entry.values)
        assertEquals(listOf("note"), entry.manualFields)
        assertTrue(rules.chooseCommon(listOf(1L), mapOf("note" to "old"), {}, { entry }))
        assertEquals(10L, entry.revision)
        assertTrue(rules.chooseCommon(listOf(1L), mapOf("note" to ""), {}, { entry }))
        assertEquals(11L, entry.revision)
        assertEquals(values(""), entry.values)
    }

    @Test fun laterEntryReadFailureKeepsEarlierMutationAndOriginalException() {
        val entry = entry(); val fault = IllegalStateException("entry read failed")
        val order = mutableListOf<Long>()
        val failure = assertFailsWith<IllegalStateException> {
            rules.chooseCommon(listOf(1L, 2L, 3L), mapOf("note" to "new"), {}, {
                order += it
                if (it == 1L) entry else throw fault
            })
        }
        assertSame(fault, failure)
        assertEquals(listOf(1L, 2L), order)
        assertEquals(values("new"), entry.values)
        assertEquals(listOf("note"), entry.manualFields)
        assertEquals(8L, entry.revision)
    }

    @Test fun overflowKeepsChangedValuesAndManualFlagsBeforeStoppingLaterReads() {
        val entry = entry().apply { revision = Long.MAX_VALUE }
        val order = mutableListOf<Long>()
        val failure = assertFailsWith<ArithmeticException> {
            rules.chooseCommon(listOf(1L, 2L), mapOf("note" to "new"), {}, { order += it; entry })
        }
        assertEquals("long overflow", failure.message)
        assertEquals(listOf(1L), order)
        assertEquals(values("new"), entry.values)
        assertEquals(listOf("note"), entry.manualFields)
        assertEquals(Long.MAX_VALUE, entry.revision)
    }
}
