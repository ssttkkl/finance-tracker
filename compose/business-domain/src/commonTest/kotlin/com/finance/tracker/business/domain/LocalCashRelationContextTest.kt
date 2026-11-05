package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashRelationContextTest {
    private val time = "2026-10-02T10:00:00Z"
    private fun transaction(id: String) = LedgerCashTransactionFactDto(id, "10", "posted", "legacy", null,
        "9007199254740993", "atomic", "ccb_debit", "record-$id", JsonNull, JsonNull, null,
        JsonArray(emptyList()), time, "-5.00", "CNY", "来源对方", "", JsonArray(emptyList()), "来源备注", null,
        "consumption", "not_applicable", time, null, "", "")
    private fun part(id: String) = LedgerCashComponentFactDto(id + id, id, "10", "-5.00", "CNY", "0", "", "", JsonNull, time)
    private fun relation(id: String, first: String, second: String, state: String = "confirmed", active: Boolean = true,
        group: String? = null) = LedgerCashRelationFactDto(id, "payment_mirror", first, second, first + first, second + second,
        first, first + first, "expense", "expense", state, active, "9007199254740994", group, "", JsonNull, "0",
        "fixture", "", time, "", null, "")
    internal fun snapshot(): LedgerSnapshotDto {
        val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        val rows = listOf(transaction("1"), transaction("2"), transaction("3").copy(state = "draft", amount = null),
            transaction("4").copy(deletedAt = time), transaction("5").copy(currency = "bad unrelated fact"))
        return LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION, empty.copy(cashTransactions = rows,
            cashTransactionComponents = (1..4).map { part(it.toString()) },
            transactionRelations = listOf(relation("100", "1", "2"), relation("101", "2", "3", "draft"),
                relation("200", "3", "4", "ignored", false, "group")),
            cashTransactionIssues = listOf(LedgerCashIssueFactDto("300", "2", "issue", "attention", null,
                JsonNull, JsonNull, JsonNull, "9007199254740995", time, time)),
            cashEntries = listOf(LedgerCashEntryFactDto("entry", "1", "1", "9007199254740996", null,
                "公共备注", "公共对方", "", JsonArray(emptyList()), JsonArray(listOf(JsonPrimitive("note"))), null)),
            cashEntryMembers = listOf(LedgerCashEntryMemberFactDto("1", "entry", JsonObject(emptyMap()), JsonArray(emptyList())))))
    }

    @Test fun confirmedClosureDoesNotExpandDraftEdgesOrReadUnrelatedFacts() {
        val input = snapshot()
        val result = readLocalCashRelationContext(input, setOf(1L))
        assertEquals(setOf(1L, 2L), result.transactionIds)
        assertEquals(listOf(1L, 2L), result.facts.map { it.id })
        assertEquals(listOf(100L, 101L), result.relations.map { it.id })
        assertEquals(listOf("11", "22"), result.components.map { it.componentId })
        assertNull(result.facts.first().createdAt)
        assertEquals("来源对方", result.facts.first().counterparty)
        assertEquals("来源备注", result.facts.first().note)
        assertTrue(result.facts.last().needsAttention)
        assertEquals(9007199254740993L, result.transactionRevisions.first().second)
        assertEquals(9007199254740996L, result.entryVersions.single().revision)
        assertEquals("9007199254740993", input.ledgerSeq)
    }

    @Test fun explicitGroupsRetainInactiveMembersAndDraftDeletedMetadata() {
        val result = readLocalCashRelationContext(snapshot(), setOf(1L), listOf("group", "group"))
        assertEquals(setOf(1L, 2L, 3L, 4L), result.transactionIds)
        assertEquals(listOf("group"), result.decisionGroupIds)
        assertEquals(setOf(1L, 2L, 3L), result.activeTransactionIds)
        assertEquals(listOf(1L, 2L), result.facts.map { it.id })
        assertEquals(listOf(11L, 22L, 33L, 44L), result.componentVersions.map { it.id })
        assertEquals(listOf(200L to "group"), result.relationGroups)
        assertEquals(listOf(CashJointRelationMember("200", "group", 9007199254740994L, "ignored", false)), result.jointMembers)
    }

    @Test fun fundingUsesTheFirstAcceptedIdAcrossAllParentComponents() {
        val base = snapshot()
        fun funding(id: String, component: String) = LedgerInvestmentFundingFactDto(id, component, "9007199254740993",
            "cash_to_investment", "accepted", "fixture", JsonNull, "active", "", time, "", null, "", null)
        val data = base.facts.copy(cashTransactionComponents = base.facts.cashTransactionComponents + part("1").copy(id = "12", ordinal = "1"),
            cashInvestmentFundingRelations = listOf(funding("80", "11"), funding("70", "12")))
        val result = readLocalCashRelationContext(base.copy(facts = data), setOf(1L))
        assertEquals(70L, result.facts.single { it.id == 1L }.fundingRelationId)
        assertEquals(listOf(70L, 80L), result.fundingVersions.map { it.id })
        assertEquals(setOf("11", "12"), result.fundingComponentIds)
        assertEquals(9007199254740993L, result.fundingVersions.first().investmentEventId)
    }

    @Test fun invalidSeedsGroupsAndMissingEndpointsKeepTheOriginalFailures() {
        val input = snapshot()
        fun failure(ids: Set<Long>, groups: List<String> = emptyList()) =
            assertFailsWith<CashRelationFailure> { readLocalCashRelationContext(input, ids, groups) }.code
        assertEquals("relation_data_invalid", failure(setOf(0L)))
        assertEquals("relation_data_invalid", failure(setOf(1L), listOf("x".repeat(129))))
        assertEquals("relation_data_invalid", failure(setOf(1L), listOf(charArrayOf(0xd800.toChar()).concatToString())))
        assertEquals("relation_group_changed", failure(setOf(999L), listOf("missing")))
        assertEquals("relation_endpoint_incomplete", failure(setOf(999L)))
        assertTrue(readLocalCashRelationContext(input, emptySet()).transactionIds.isEmpty())
    }
}
