package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CounterpartyAccountRulesTest {
    private fun rules() = CounterpartyAccountRules(
        textOrEmpty = { it as? String ?: "" },
        textValue = { it?.toString() ?: "None" },
        sourcePayloadDigest = { value -> if (value is Map<*, *> && value.isNotEmpty())
            sha256Hex(sourceJson(value, { null }, ::pythonFloatText).toString()) else "" },
    )
    private val rules = rules()

    @Test
    fun accountFormsKeepWhitespaceMasksAndTailBoundaries() {
        val cases = listOf(
            "（空）" to CounterpartyAccount("", emptyList()),
            " １２-３４ " to CounterpartyAccount("１２３４", listOf("tail")),
            "123" to CounterpartyAccount("", emptyList()),
            "12345" to CounterpartyAccount("12345", listOf("full")),
            "尾号 1234 银行" to CounterpartyAccount("1234", listOf("tail")),
            "12345 6789" to CounterpartyAccount("123456789", listOf("full")),
            "a12345x6789" to CounterpartyAccount("6789", listOf("tail")),
            "1234@example.test" to CounterpartyAccount("1234@example.test", listOf("full")),
            "(6222) ＊＊＊＊ 1234" to CounterpartyAccount("6222****1234", listOf("masked")),
            "abc*1234" to CounterpartyAccount("abc*1234", listOf("masked")),
            "𝟙𝟚𝟛𝟜" to CounterpartyAccount("𝟙𝟚𝟛𝟜", listOf("full")),
            "𝟙𝟙𝟙𝟙𝟙x1234" to CounterpartyAccount("𝟙𝟙𝟙𝟙", listOf("tail")),
            "尾号𝟙𝟚𝟛𝟜" to CounterpartyAccount("𝟙𝟚𝟛𝟜", listOf("tail")),
            "¹²³⁴" to CounterpartyAccount("¹²³⁴", listOf("tail")),
        )
        for ((input, expected) in cases) assertEquals(expected, rules.normalizeAccount(input), input)
        rules.validateAccount("𝟙𝟚𝟛𝟜", listOf("tail"))
        assertEquals("counterparty_account_attrs tail requires exactly four digits", assertFailsWith<CashRecordRuleFailure> {
            rules.validateAccount("¹²³⁴", listOf("tail"))
        }.message)
    }

    @Test
    fun reconstructionProofBindsTheEngineSourceAccountAndPayload() {
        val payload = mapOf("source row" to "original")
        val account = rules.normalizeAccount("6222********1234", "icbc_asia", "6222000011111234", payload)
        assertEquals(CounterpartyAccount("6222000011111234", listOf("masked", "reconstructed")), account)
        rules.validateAccountForWrite(account.value, account.attrs, account, "icbc_asia", payload)
        val attempts = listOf<() -> Unit>(
            { rules.validateAccountForWrite(account.value, account.attrs, null, "icbc_asia", payload) },
            { rules.validateAccountForWrite(account.value, account.attrs, account, "icbc_asia", payload + ("source row" to "changed")) },
            { rules.validateAccountForWrite(account.value, account.attrs, account, "another", payload) },
            { rules.validateAccountForWrite("6222000011119999", account.attrs, account, "icbc_asia", payload) },
            { rules().validateAccountForWrite(account.value, account.attrs, account, "icbc_asia", payload) },
        )
        for (attempt in attempts) assertEquals(
            "counterparty_account_attrs reconstructed requires verified source reconstruction from the same source row",
            assertFailsWith<CashRecordRuleFailure>(block = attempt).message)
    }

    @Test
    fun reconstructionNeedsCompatibleLengthsAndASourcePayloadProof() {
        val plain = rules.normalizeAccount("6222********1234", "icbc_asia", "9999000011111234", mapOf("row" to "value"))
        assertEquals(CounterpartyAccount("6222********1234", listOf("masked")), plain)
        val unsupported = rules.normalizeAccount("6222********1234", "icbc_asia", "6222000011111234")
        assertEquals(listOf("masked", "reconstructed"), unsupported.attrs)
        assertFailsWith<CashRecordRuleFailure> {
            rules.validateAccountForWrite(unsupported.value, unsupported.attrs, unsupported, "icbc_asia", null)
        }
    }

    @Test
    fun malformedAttributeSetsRetainTheirSpecificRejections() {
        for (attrs in listOf(listOf("reconstructed"), listOf("tail", "full"), listOf("masked", "masked")))
            assertEquals("counterparty_account_attrs has an invalid combination", assertFailsWith<CashRecordRuleFailure> {
                rules.validateAccount("12345", attrs)
            }.message)
        assertEquals("counterparty_account_attrs must be empty when account is empty", assertFailsWith<CashRecordRuleFailure> {
            rules.validateAccount("", listOf("full"))
        }.message)
        assertEquals("counterparty_account_attrs full conflicts with account value", assertFailsWith<CashRecordRuleFailure> {
            rules.validateAccount("1234", listOf("full"))
        }.message)
    }

    @Test
    fun exportedCharacterTablesCannotChangeParsingOrClassification() {
        val integerDigits = pythonIntegerDigitStarts
        integerDigits[0] = 65
        assertEquals(null, LedgerDecimal.parseRepresentationOrNull("A"))
        assertEquals("1", LedgerDecimal.parseRepresentationOrNull("1")?.coefficient)
        @Suppress("UNCHECKED_CAST")
        val nondecimal = pythonNondecimalDigits as MutableSet<Int>
        nondecimal.clear()
        nondecimal.add(65)
        assertEquals(CounterpartyAccount("AAAA", listOf("full")), rules.normalizeAccount("AAAA"))
        assertEquals(CounterpartyAccount("¹²³⁴", listOf("tail")), rules.normalizeAccount("¹²³⁴"))
    }

}
