package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashFundingScanTest {
    private val rules = CashFundingScanRules(
        { LedgerArithmeticValue.fromCashAmount(exactCashAmount(it)).toPlainString() },
        { it.replace("e\u0301", "é") },
        { error("these source-native cases do not use Alipay components") }, { it }, { it as String },
        { values -> sha256Hex(sourceJson(values, { null }, ::pythonFloatText).toString()) },
        { it.toMutableMap() },
    )
    private fun file(ref: String, nickname: String?, method: String): ParsedImportFile {
        val row = ParsedCashRow(1, mapOf("amount" to "-1", "currency" to "CNY"), method)
        return ParsedImportFile(ref, ref.repeat(64), "parsed", ParsedCashDocument("wechat", "csv:0", "test-v1",
            listOf(row), null, null, CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"),
            1, 1, nickname), filename = "原文$ref.csv")
    }
    @Test fun normalizedNicknameGroupsAndOriginalFieldsAreKept() {
        val files = listOf(file("a", "e\u0301", "零钱"), file("b", "é", "零钱"))
        val scan = rules.scan(files)
        assertEquals(1, scan.groups.size)
        assertEquals("é", scan.groups.single().sessionLabel)
        assertEquals(listOf("a:r1", "b:r1"), scan.groups.single().rowRefs)
        assertEquals(scan.rows[0].groupRefs, scan.rows[1].groupRefs)
        assertEquals("-1", scan.rows[0].fields["amount"])
        assertEquals("e\u0301", files[0].document?.sourceNickname)
    }
    @Test fun fullAccountDigitsAreMaskedAndUnicodeSpaceMatchesNativeFundingRules() {
        val scan = rules.scan(listOf(file("a", null, "银行卡(6222\u00a01234\t5678 9012)")))
        val label = scan.groups.single().displayName
        assertTrue("9012" in label)
        assertFalse("6222123456789012" in label.filter { it in '0'..'9' })
        val unicode = fundingSourcePattern("(?U)[0-9](?:[\\s\\-－—·]*[0-9]){7,}")
        assertTrue(unicode.matches("1234\u00a05678"))
        assertFalse(unicode.matches("1234\u001c5678"))
    }
}
