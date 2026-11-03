package com.finance.tracker

import com.finance.tracker.domain.ImportAccountListItem
import com.finance.tracker.presentation.importAccountSuggestions
import com.finance.tracker.presentation.jaroWinkler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportAccountSuggestionsTest {
    private fun account(id: String, name: String) = ImportAccountListItem(id, name, "cash", true, listOf("CNY"))

    @Test fun bankVariantsSortExactThenSameTailThenBankLevel() {
        val accounts = listOf(
            account("1", "建设银行储蓄卡(1234)"),
            account("2", "建设银行(1234)"),
            account("3", "建设银行"),
            account("4", "建设银行(5678)"),
            account("5", "招商银行"),
        )
        assertEquals(listOf("1", "2", "3"), importAccountSuggestions("建设银行储蓄卡(1234)", accounts))
    }

    @Test fun differentCardTailIsHardExcludedEvenForSimilarNames() {
        val accounts = listOf(account("1", "建设银行(5678)"), account("2", "建设很行(5678)"))
        assertEquals(emptyList<String>(), importAccountSuggestions("建设银行(1234)", accounts))
    }

    @Test fun fuzzyTypoIsSuggestedAfterRuleMatches() {
        val accounts = listOf(account("1", "建设银行储蓄卡(1234)"), account("2", "建设很行(1234)"))
        assertEquals(listOf("1", "2"), importAccountSuggestions("建设银行(1234)", accounts))
        assertTrue(jaroWinkler("建设银行", "建设很行") >= 0.75)
        assertTrue(jaroWinkler("建设银行", "招商银行") < 0.75)
    }

    @Test fun containmentMatchesBankLevelNames() {
        val accounts = listOf(account("1", "中国建设银行"), account("2", "建设银行龙卡"))
        assertEquals(setOf("1", "2"), importAccountSuggestions("建设银行", accounts).toSet())
    }

    @Test fun sourceWithoutTailStillSuggestsBankLevelAndCards() {
        val accounts = listOf(account("1", "建设银行(1234)"), account("2", "建设银行"))
        assertEquals(listOf("2", "1"), importAccountSuggestions("建设银行", accounts))
    }

    @Test fun digitsInTheMiddleAreNotTreatedAsCardTails() {
        val accounts = listOf(account("1", "建设银行2024储蓄卡"), account("2", "建设银行(1234)"))
        assertEquals(listOf("1", "2"), importAccountSuggestions("建设银行2024储蓄卡", accounts))
    }

    @Test fun genericBankWordsDoNotMakeDifferentBanksSimilar() {
        val accounts = listOf(account("1", "中国建设银行"), account("2", "中国工商银行"), account("3", "中国农业银行"))
        assertEquals(emptyList<String>(), importAccountSuggestions("中国银行", accounts))
    }

    @Test fun maskedCardTailUsesTheLastFourDigits() {
        val accounts = listOf(account("1", "建设银行(6225****1234)"), account("2", "建设银行(5678)"))
        assertEquals(listOf("1"), importAccountSuggestions("建设银行(1234)", accounts))
    }

    @Test fun spacedFullCardUsesTheLastFourDigits() {
        val accounts = listOf(account("1", "建设银行6225 1234 5678"))
        assertEquals(listOf("1"), importAccountSuggestions("建设银行(5678)", accounts))
    }

    @Test fun emptySourceNameSuggestsNothing() {
        assertEquals(emptyList<String>(), importAccountSuggestions("", listOf(account("1", ""), account("2", "日常账户"))))
    }

    @Test fun pureNumericSourceMatchesAnAccountWithTheSameTail() {
        assertEquals(listOf("1"), importAccountSuggestions("8888", listOf(account("1", "建设银行(8888)"))))
    }

    @Test fun confirmedSuggestionIsPinnedEvenWhenNameDiffers() {
        val accounts = listOf(account("1", "日常账户"), account("2", "建设银行(1234)"))
        assertEquals(listOf("1", "2"), importAccountSuggestions("建设银行(1234)", accounts, confirmedAccountId = "1"))
        assertEquals(emptyList<String>(), importAccountSuggestions("零钱", listOf(account("1", "日常账户"))))
    }
}
