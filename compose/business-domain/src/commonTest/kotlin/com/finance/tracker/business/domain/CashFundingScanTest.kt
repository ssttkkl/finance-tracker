package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashFundingScanTest {
    private fun file(ref: String, nickname: String?, method: String): ParsedImportFile {
        val row = ParsedCashRow(1, mapOf("amount" to "-1", "currency" to "CNY"), method)
        return ParsedImportFile(ref, ref.repeat(64), "parsed", ParsedCashDocument("wechat", "csv:0", "test-v1",
            listOf(row), null, null, CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"),
            1, 1, nickname), filename = "原文$ref.csv")
    }
    @Test fun normalizedNicknameGroupsAndOriginalFieldsAreKept() {
        val files = listOf(file("a", "e\u0301", "零钱"), file("b", "é", "零钱"))
        val scan = scanLocalCashFunding(files)
        assertEquals(1, scan.groups.size)
        assertEquals("é", scan.groups.single().sessionLabel)
        assertEquals(listOf("a:r1", "b:r1"), scan.groups.single().rowRefs)
        assertEquals(scan.rows[0].groupRefs, scan.rows[1].groupRefs)
        assertEquals("-1", scan.rows[0].fields["amount"])
        assertEquals("e\u0301", files[0].document?.sourceNickname)
    }
    @Test fun fullAccountDigitsAreMaskedAndUnicodeSpaceMatchesNativeFundingRules() {
        val scan = scanLocalCashFunding(listOf(file("a", null, "银行卡(6222\u00a01234\t5678 9012)")))
        val label = scan.groups.single().displayName
        assertTrue("9012" in label)
        assertFalse("6222123456789012" in label.filter { it in '0'..'9' })
        val unicode = fundingSourcePattern("(?U)[0-9](?:[\\s\\-－—·]*[0-9]){7,}")
        assertTrue(unicode.matches("1234\u00a05678"))
        assertFalse(unicode.matches("1234\u001c5678"))
    }
    @Test fun productionScanCopiesNestedFieldsBeforeLaterEdits() {
        val original = file("a", "家庭号", "零钱")
        val nested = linkedMapOf<String, Any?>("tokens" to mutableListOf("原始"))
        val row = original.document!!.rows.single().copy(fields = original.document.rows.single().fields!! + ("source_payload" to nested))
        val input = original.copy(document = original.document.copy(rows = listOf(row)))
        val scan = scanLocalCashFunding(listOf(input))
        val payload = scan.rows.single().fields["source_payload"] as Map<*, *>
        @Suppress("UNCHECKED_CAST") val copied = payload["tokens"] as MutableList<String>
        copied[0] = "已修改"
        assertEquals(listOf("原始"), nested["tokens"])
        assertEquals("零钱", input.document!!.rows.single().paymentMethod)
    }

    @Test fun productionIdentityDigestRejectsInvalidUtf16() {
        for (code in listOf(0xd800, 0xdc00)) {
            val invalid = buildString { append(code.toChar()) }
            assertFailsWith<CharacterCodingException> { localSourceStringDigest(listOf("prefix", invalid)) }
            assertFailsWith<CharacterCodingException> { scanLocalCashFunding(listOf(file("a", invalid, "零钱"))) }
        }
        assertEquals("05a3bcdc3fd5392315693327d64ef4616a409a110d34c49803bc68f35a5fe692",
            localSourceStringDigest(listOf("家庭号", "😀")))
    }

}
