package com.finance.tracker.business.domain

import kotlin.test.*

class CashLedgerKeywordTest {
    @Test fun ordinaryTextMatchesEitherFieldWithoutDependingOnDatabaseCaseRules() {
        assertTrue(cashLedgerKeywordMatches("Cafe", "", "cafe"))
        assertTrue(cashLedgerKeywordMatches("", "Paid at Cafe", "CAFE"))
        assertTrue(cashLedgerKeywordMatches("STRASSE", "", "Straße"))
        assertTrue(cashLedgerKeywordMatches("", "ÉCOLE", "école"))
        assertTrue(cashLedgerKeywordMatches("ΟΣ", "", "ος"))
        assertTrue(cashLedgerKeywordMatches("𐐀", "", "𐐨"))
        assertFalse(cashLedgerKeywordMatches("Cafe", "", "Café"))
        assertFalse(cashLedgerKeywordMatches("ab", "cd", "bc"))
    }
    @Test fun sqlWildcardsAndBackslashRemainLiteralText() {
        for (query in listOf("%", "_", "\\")) {
            assertFalse(cashLedgerKeywordMatches("ordinary", "plain text", query))
            assertTrue(cashLedgerKeywordMatches("before${query}after", "", query))
            assertTrue(cashLedgerKeywordMatches("", "before${query}after", query))
        }
        assertFalse(cashLedgerKeywordMatches("ab", "", "a\\b"))
        assertTrue(cashLedgerKeywordMatches("a\\b", "", "A\\B"))
        assertFalse(cashLedgerKeywordMatches("anything", "", "%thing%"))
    }
    @Test fun blankKeywordDoesNotRestrictButOnlyItsOuterWhitespaceIsTrimmed() {
        for (keyword in listOf(null, "", " \t\n", "\u0085\u3000")) assertTrue(cashLedgerKeywordMatches("", "", keyword))
        assertTrue(cashLedgerKeywordMatches("Cafe", "", "\u0085 cAfE\u3000"))
        assertTrue(cashLedgerKeywordMatches("a b", "", "a b"))
        assertFalse(cashLedgerKeywordMatches("ab", "", "a b"))
        assertFalse(cashLedgerKeywordMatches("a  b", "", "a b"))
    }
}
