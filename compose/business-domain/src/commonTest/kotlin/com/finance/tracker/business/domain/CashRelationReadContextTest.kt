package com.finance.tracker.business.domain

import kotlin.test.*
import kotlin.time.Instant

class CashRelationReadContextTest {
    private fun context() = CashRelationReadContext<Long, Instant>(
        seedIds = setOf(10L), decisionGroupIds = listOf("组𝒩"), transactionIds = setOf(10L),
        facts = emptyList(), components = emptyList(), relations = emptyList(),
        jointMembers = listOf(CashJointRelationMember("30", "组𝒩", 9007199254740995L, "draft", false)),
        transactionRevisions = listOf(10L to 9007199254740993L), activeTransactionIds = setOf(10L),
        componentVersions = listOf(CashRelationComponentVersion(20L, 10L, 9007199254740993L, "0.000000000000000001", "CNY", 0L)),
        relationRevisions = listOf(30L to 9007199254740994L),
        issueVersions = listOf(CashRelationIssueVersion(40L, 10L, 7L)),
        entryVersions = listOf(CashRelationEntrySnapshot(10L, "entry\"\\\n𝒩", 8L, "digest")),
        relationGroups = listOf(30L to "组𝒩"),
        fundingVersions = listOf(CashRelationFundingVersion(50L, 20L, 60L, "cash_to_investment")),
        fundingComponentIds = setOf("20"),
        sourceOrderKeys = listOf(10L to listOf("alipay", "account", "scope\u007f", "provider_id", "键")),
    )

    @Test fun dependencyTokenRetainsAsciiEscapingAndEveryIntegerBit() {
        assertEquals("db08c1c524d440d628b29e28f5c81ca6fb725c2b3c55d498d958b97a5bb1f05c", cashRelationReadContextToken(context()))
    }

    @Test fun dependencyChangesInvalidateTheToken() {
        val original = context()
        val changed = listOf(
            original.copy(transactionRevisions = listOf(10L to 9007199254740994L)),
            original.copy(componentVersions = original.componentVersions.map { it.copy(amount = "0.000000000000000002") }),
            original.copy(relationRevisions = listOf(30L to 9007199254740995L)),
            original.copy(relationGroups = listOf(30L to "other")),
            original.copy(fundingVersions = original.fundingVersions.map { it.copy(direction = "investment_to_cash") }),
            original.copy(issueVersions = original.issueVersions.map { it.copy(revision = 8L) }),
            original.copy(entryVersions = original.entryVersions.map { it.copy(digest = "other") }),
            original.copy(sourceOrderKeys = emptyList()),
            original.copy(jointMembers = listOf(CashJointRelationMember("30", "组𝒩", 9007199254740996L, "draft", false))),
        )
        for (value in changed) assertNotEquals(cashRelationReadContextToken(original), cashRelationReadContextToken(value))
    }

    @Test fun selectionAndDerivedRuntimeStateDoNotChangeTheDependencyToken() {
        val original = context()
        val other = original.copy(seedIds = setOf(11L), decisionGroupIds = emptyList(), transactionIds = setOf(11L),
            activeTransactionIds = emptySet(), fundingComponentIds = emptySet())
        assertEquals(cashRelationReadContextToken(original), cashRelationReadContextToken(other))
    }
}
