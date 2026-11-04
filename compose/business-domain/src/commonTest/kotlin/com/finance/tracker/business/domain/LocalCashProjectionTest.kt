package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashProjectionTest {
    private fun fact(id: String, amount: String?) = LedgerCashTransactionFactDto(
        id = id, accountId = "10", state = "posted", recordOrigin = "legacy", manualTransferId = null,
        rowRevision = "1", cashGranularity = "atomic", sourceType = null, recordId = "record:$id",
        sourcePayload = JsonNull, relationMetadata = JsonNull, sourceFingerprint = null,
        manualOverrides = JsonArray(emptyList()), occurredAt = "2026-08-12T03:04:05.123456Z", amount = amount,
        currency = "CNY", counterparty = "商户", counterpartyAccount = "", counterpartyAccountAttrs = JsonArray(emptyList()),
        note = "", categoryId = null, recordType = "consumption", recordSubtype = "not_applicable",
        createdAt = "2026-08-12T03:04:05.123456Z", deletedAt = null, deletedBy = "", deleteReason = "")
    @Test fun completeSnapshotPreservesLargeIdsDraftsAndHiddenAdjustmentsWithoutGraphComponents() {
        val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        val transactions = listOf(fact("9007199254740993", "-12.340000000000000001"), fact("2", "0"),
            fact("3", null).copy(state = "draft", occurredAt = null, currency = null),
            fact("4", "25").copy(deletedAt = "2026-08-13T00:00:00Z"))
        val snapshot = LedgerSnapshotDto("w", "9007199254740993", CASH_RULES_VERSION, empty.copy(cashTransactions = transactions))
        val result = buildLocalCashProjections(snapshot)
        assertEquals(setOf(9007199254740993L, 2L, 3L), result.memberIds)
        val expense = result.projections.single { it.primaryRecord.id == 9007199254740993L }
        assertEquals(CashAmount.parse("-12.340000000000000001").representation, expense.netAmount!!.representation)
        assertEquals(CashEconomicType.EXPENSE, expense.economicType)
        assertEquals("2026-08-12T03:04:05.123456Z", expense.sortAt.toString())
        val zero = result.projections.single { it.primaryRecord.id == 2L }
        assertFalse(zero.visible)
        assertEquals("balance_adjustment", zero.hiddenReason)
        val draft = result.projections.single { it.primaryRecord.id == 3L }
        assertEquals("draft", draft.state)
        assertFalse(draft.statisticsEligible)
        assertEquals("pending", draft.confirmationStatus)
        assertEquals(transactions, snapshot.facts.cashTransactions)
        fun binding(id: String, kind: String, scope: String) = LedgerSourceBindingFactDto(
            id, "9007199254740993", "alipay", scope, "owner", kind, "identity:$id", "strong", null,
            "v1", JsonNull, "1", "2026-08-12T00:00:00Z", "2026-08-12T00:00:00Z")
        val withBindings = snapshot.copy(facts = snapshot.facts.copy(cashSourceBindings = listOf(
            binding("1", "document_fingerprint", "account"), binding("2", "provider_id", "document"),
            binding("3", "provider_id", "account"))))
        assertEquals(listOf("alipay", "account", "owner", "provider_id", "identity:3"),
            buildLocalCashProjections(withBindings).projections.single { it.primaryRecord.id == 9007199254740993L }.primaryRecord.stableSourceKey)
    }
}
