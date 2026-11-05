package com.finance.tracker.backend

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

class CashRelationReadContextParityTest {
    private fun empty() = CashRelationSqlContext(seedIds = emptySet(), decisionGroupIds = emptyList(), transactionIds = emptySet(),
        facts = emptyList(), components = emptyList(), relations = emptyList(), jointMembers = emptyList(),
        transactionRevisions = emptyList(), activeTransactionIds = emptySet(), componentVersions = emptyList(),
        relationRevisions = emptyList(), issueVersions = emptyList(), entryVersions = emptyList(), relationGroups = emptyList(),
        fundingVersions = emptyList(), fundingComponentIds = emptySet())

    @Test fun contextTokenKeepsTheOriginalAsciiEncoderForEveryUtf16CodeUnit() {
        val base = empty()
        fun check(text: String) {
            val original = tokenDigest("[[],[],[],[],[],[],[],[[1,[" + receiptAsciiJson(JsonPrimitive(text)) + "]]],[]]")
            assertEquals(original, base.copy(sourceOrderKeys = listOf(1L to listOf(text))).token(),
                text.map { it.code.toString(16) }.joinToString())
        }
        for (code in 0..0xffff) check(code.toChar().toString())
        check("𝒩𐀀\"\\\n\t")
    }
}
