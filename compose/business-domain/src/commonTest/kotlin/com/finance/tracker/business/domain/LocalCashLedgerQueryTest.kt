package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*
import kotlin.time.Instant
import kotlin.test.*

class LocalCashLedgerQueryTest {
    private fun snapshot(): LedgerSnapshotDto {
        fun fact(id: String, amount: String?, day: String?, created: String, kind: String = "consumption", account: String = "10") =
            LedgerCashTransactionFactDto(id, account, if (amount == null) "draft" else "posted", "legacy", null,
                "1", "atomic", null, "record:$id", JsonNull, JsonNull, null, JsonArray(emptyList()), day,
                amount, if (amount == null) null else "CNY", "source value", "", JsonArray(emptyList()), "source note", null,
                kind, "not_applicable", created, null, "", "")
        val august = "2026-08-12T03:00:00Z"
        val transactions = listOf(
            fact("1", "-12.340000000000000001", august, august),
            fact("2", "50", "2026-07-01T00:00:00Z", august, "income", "20"),
            fact("3", null, null, "2026-08-15T00:00:00Z"),
            fact("4", "-0.000000000000000001", august, august))
        val empty = LedgerFactsDto(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "0")
        val facts = empty.copy(
            accounts = listOf(LedgerAccountFactDto("10", "Cash", "cash", true, JsonArray(emptyList()), "1"),
                LedgerAccountFactDto("20", "Bank", "bank", true, JsonArray(emptyList()), "1")),
            cashTransactions = transactions,
            cashEntries = transactions.map { LedgerCashEntryFactDto("entry:${it.id}", it.id, it.id, "1", null,
                if (it.id == "1") "literal%_\\note" else "", if (it.id == "1") "Cafe" else "Other", "",
                JsonArray(emptyList()), JsonArray(emptyList()), null) },
            cashEntryMembers = transactions.map { LedgerCashEntryMemberFactDto(it.id, "entry:${it.id}", JsonObject(emptyMap()), JsonArray(emptyList())) })
        return LedgerSnapshotDto("workspace", "9007199254740993", CASH_RULES_VERSION, facts)
    }
    private fun input(filters: Map<String, String?> = emptyMap(), start: String? = null, end: String? = null) =
        LocalCashLedgerQueryInput(mapOf("state" to "all", "hide_zero_amount" to "True", "uncategorized" to "False", "timezone" to "UTC") + filters,
            start?.let(Instant::parse), end?.let(Instant::parse))
    private fun query(snapshot: LedgerSnapshotDto = snapshot()) = LocalCashLedgerQuery(snapshot) { instant, _ -> cashLedgerMonthAtOffset(instant, 0) }
    @Test fun callerMutationCannotSeparateCanonicalFiltersFromExecutedAmounts() {
        val input = normalizeLocalCashLedgerQueryInput(CashFiltersDto(amountMin = "0"), null) { _, _, _ ->
            CashLedgerDateBounds(null, null, "UTC", null, null)
        }
        val exposed = input.filters as MutableMap<String, String?>
        exposed["amount_min"] = "-1000"
        exposed["state"] = "draft"
        val page = query().page(input, startIndex = 0)
        assertEquals(listOf("2"), page.items.map { it.transactionId })
        assertEquals("0", page.filters["amount_min"])
        assertEquals("all", page.filters["state"])
        assertEquals(1, page.totalCount)
        assertTrue(query().page(this.input(mapOf("account_id" to "9223372036854775808"))).items.isEmpty())
    }
    @Test fun completeSelectionCountsAndMonthlyAmountsDoNotComeFromThePage() {
        val page = query().page(input(), startIndex = 0, limit = 1)
        assertEquals(9007199254740993L, page.projectionVersion)
        assertEquals(listOf("3"), page.items.map { it.transactionId })
        assertNull(page.items.single().occurredAt)
        assertEquals(4, page.totalCount); assertEquals(0, page.itemsBefore); assertEquals(3, page.itemsAfter)
        assertEquals(1L, page.pendingItemCount)
        assertEquals(listOf(CashMonthlySummaryDto("2026-08", listOf(CashMonthlyCurrencySummaryDto("CNY", "0", "-12.340000000000000002"))),
            CashMonthlySummaryDto("2026-07", listOf(CashMonthlyCurrencySummaryDto("CNY", "50", "0")))), page.monthlySummaries)
        assertEquals(listOf(CashMonthPositionDto("unknown", 0, 1), CashMonthPositionDto("2026-08", 1, 3), CashMonthPositionDto("2026-07", 3, 4)), page.monthPositions)
        assertEquals(listOf("expense", "income"), page.filterOptions.economicTypes.map { it.economicType })
    }
    @Test fun publicTextDateAndExactAmountsFilterBeforeCountsAndOptionsStayGlobal() {
        val q = query()
        assertEquals(listOf("1"), q.page(input(mapOf("counterparty" to "cafe")), startIndex = 0).items.map { it.transactionId })
        assertEquals(listOf("1"), q.page(input(mapOf("counterparty" to "%_\\")), startIndex = 0).items.map { it.transactionId })
        assertTrue(q.page(input(mapOf("counterparty" to "source")), startIndex = 0).items.isEmpty())
        val selected = q.page(input(mapOf("amount_min" to "-0.000000000000000001", "amount_max" to "0")), startIndex = 0)
        assertEquals(listOf("4"), selected.items.map { it.transactionId }); assertEquals(1, selected.totalCount)
        assertEquals(0L, selected.pendingItemCount)
        assertEquals(listOf("expense", "income"), selected.filterOptions.economicTypes.map { it.economicType })
        val day = q.page(input(start = "2026-08-12T00:00:00Z", end = "2026-08-13T00:00:00Z"), startIndex = 0)
        assertEquals(listOf("4", "1"), day.items.map { it.transactionId }); assertEquals(2, day.totalCount)
        assertEquals(listOf("2"), q.page(input(mapOf("account_id" to "20")), startIndex = 0).items.map { it.transactionId })
    }
    @Test fun keysetAndAbsolutePagesBindTheWholeScopeAndKeepFullCounts() {
        val q = query()
        val first = q.page(input(), limit = 1)
        assertNull(first.totalCount); assertTrue(first.monthPositions.isEmpty()); assertNotNull(first.nextCursor)
        val second = q.page(input(), cursor = first.nextCursor, limit = 2)
        assertEquals(listOf("4", "1"), second.items.map { it.transactionId }); assertEquals(1L, second.pendingItemCount)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> {
            q.page(input(mapOf("timezone" to "Asia/Tokyo")), cursor = first.nextCursor)
        }.code)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> {
            query(snapshot().copy(workspaceId = "other")).page(input(), cursor = first.nextCursor)
        }.code)
        val last = q.page(input(), cursor = second.nextCursor, limit = 2)
        assertEquals(listOf("2"), last.items.map { it.transactionId }); assertNull(last.nextCursor)
        val indexed = q.page(input(), startIndex = 1, expectedLedgerSeq = 9007199254740993L, limit = 2)
        assertEquals(second.items, indexed.items); assertTrue(indexed.monthlySummaries.isEmpty()); assertTrue(indexed.monthPositions.isEmpty())
        assertEquals(4, indexed.totalCount)
        val beyond = q.page(input(), startIndex = Int.MAX_VALUE, expectedLedgerSeq = 9007199254740993L)
        assertTrue(beyond.items.isEmpty()); assertEquals(4, beyond.itemsBefore); assertEquals(0, beyond.itemsAfter)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> { q.page(input(mapOf("state" to "posted")), cursor = first.nextCursor) }.code)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionDomainFailure> {
            query(snapshot().copy(ledgerSeq = "9007199254740994")).page(input(), cursor = first.nextCursor)
        }.code)
        assertEquals("read_snapshot_changed", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), startIndex = 0, expectedLedgerSeq = 9007199254740992L) }.code)
    }
    @Test fun categoriesUseAncestorsAndAccountMatchesIncludeCashComponents() {
        val base = snapshot()
        fun category(id: String, parent: String?) = LedgerCategoryFactDto(id, parent, parent ?: "", id, id, null,
            "/$id/", 1, "0", "1", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        val facts = base.facts.copy(
            cashCategories = listOf(category("parent%_", null), category("child", "parent%_"), category("parentAB", null)),
            cashEntries = base.facts.cashEntries.map { it.copy(categoryId = when (it.rootTransactionId) {
                "1" -> "child"; "2" -> "parentAB"; "4" -> "parent%_"; else -> null }) },
            cashTransactionComponents = listOf(LedgerCashComponentFactDto("11", "1", "20", "-12.340000000000000001", "CNY", "0", "", "", JsonObject(emptyMap()), "2026-08-12T03:00:00Z")))
        val q = query(base.copy(facts = facts))
        assertEquals(listOf("4", "1"), q.page(input(mapOf("category_id" to "parent%_")), startIndex = 0).items.map { it.transactionId })
        assertEquals(listOf("3"), q.page(input(mapOf("uncategorized" to "True")), startIndex = 0).items.map { it.transactionId })
        assertEquals(listOf("1", "2"), q.page(input(mapOf("account_id" to "20")), startIndex = 0).items.map { it.transactionId })
        assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { q.page(input(mapOf("category_id" to "missing"))) }.code)
    }
    @Test fun pendingStatesAndZeroRulesKeepUnknownAmountsDistinctFromZero() {
        val base = snapshot()
        val zero = base.facts.cashTransactions.first().copy(id = "5", amount = "0")
        val facts = base.facts.copy(cashTransactions = base.facts.cashTransactions + zero,
            cashEntries = base.facts.cashEntries + base.facts.cashEntries.first().copy(id = "entry:5", rootTransactionId = "5", identityTransactionId = "5"),
            cashEntryMembers = base.facts.cashEntryMembers + LedgerCashEntryMemberFactDto("5", "entry:5", JsonObject(emptyMap()), JsonArray(emptyList())),
            cashTransactionIssues = listOf(LedgerCashIssueFactDto("9", "5", "pending", "source_update_pending", null,
                JsonObject(emptyMap()), JsonObject(emptyMap()), JsonObject(emptyMap()), "1", zero.createdAt, zero.createdAt)))
        val q = query(base.copy(facts = facts))
        assertEquals(listOf("3"), q.page(input(mapOf("confirmation_status" to "pending"))).items.map { it.transactionId })
        assertEquals(listOf("3", "5"), q.page(input(mapOf("confirmation_status" to "pending", "hide_zero_amount" to "False"))).items.map { it.transactionId })
        assertEquals(listOf("3"), q.page(input(mapOf("state" to "draft"))).items.map { it.transactionId })
        assertEquals(listOf("4", "1", "2"), q.page(input(mapOf("state" to "posted"))).items.map { it.transactionId })
        assertEquals(listOf("4", "1"), q.page(input(mapOf("economic_type" to "expense"))).items.map { it.transactionId })
        assertEquals(listOf("2"), q.page(input(mapOf("economic_type" to "income", "currency" to "CNY"))).items.map { it.transactionId })
        assertTrue(q.page(input(mapOf("currency" to "USD"))).items.isEmpty())
    }
    @Test fun combinedRelationshipsAndFullRefundVisibilityUseTheRealProjectionGraph() {
        fun related(refund: String): LedgerSnapshotDto {
            val base = snapshot()
            val source = base.facts.cashTransactions.first()
            val transactions = listOf(source.copy(id = "1", amount = "-100"), source.copy(id = "2", amount = "-100", recordId = "record:2"),
                source.copy(id = "3", amount = refund, recordType = "refund", recordId = "record:3"))
            fun relation(id: String, kind: String, to: String, role: String, applied: String) = LedgerCashRelationFactDto(id, kind, "1", to,
                "1", to, "1", "1", "expense", role, "confirmed", true, "1", null, "system", JsonObject(emptyMap()), applied,
                "fixture", "user", source.createdAt, "user", source.createdAt, "")
            return base.copy(facts = base.facts.copy(cashTransactions = transactions,
                cashTransactionComponents = transactions.map { LedgerCashComponentFactDto(it.id, it.id, it.accountId, it.amount, "CNY", "0", "", "", JsonObject(emptyMap()), it.createdAt) },
                transactionRelations = listOf(relation("10", "payment_mirror", "2", "expense", "0"), relation("11", "refund_offset", "3", "refund", refund)),
                cashEntries = listOf(base.facts.cashEntries.first().copy(id = "one")),
                cashEntryMembers = transactions.map { LedgerCashEntryMemberFactDto(it.id, "one", JsonObject(emptyMap()), JsonArray(emptyList())) }))
        }
        val valid = related("25")
        val invalid = valid.copy(facts = valid.facts.copy(cashTransactionComponents = valid.facts.cashTransactionComponents.map {
            if (it.id == "2") it.copy(accountId = "20") else it }))
        assertEquals("projection.invalid_relation", assertFailsWith<CashProjectionDomainFailure> { query(invalid) }.code)
        val duplicateIdentity = valid.copy(facts = valid.facts.copy(cashTransactions = valid.facts.cashTransactions.map {
            if (it.id == "2") it.copy(recordId = "record:1") else it }))
        assertEquals("projection.invalid_relation", assertFailsWith<CashProjectionDomainFailure> { query(duplicateIdentity) }.code)
        val q = query(valid)
        for (composition in listOf("combined", "payment_mirror", "refund_offset")) {
            val page = q.page(input(mapOf("composition" to composition)), startIndex = 0)
            assertEquals("-75", page.items.single().amount); assertEquals(3, page.items.single().memberCount)
            assertEquals(listOf("payment_mirror", "refund_offset"), page.items.single().composition)
        }
        assertTrue(q.page(input(mapOf("composition" to "single"))).items.isEmpty())
        val full = query(related("100"))
        assertTrue(full.page(input()).items.isEmpty())
        val retained = full.page(input(mapOf("hide_zero_amount" to "False")), startIndex = 0)
        assertEquals("0", retained.items.single().amount); assertEquals(1, retained.totalCount)
    }
    @Test fun wholeSelectionTotalsCanExceedOnePersistedAmountAndInvalidPaginationDoesNotPublish() {
        val base = snapshot()
        val transactions = base.facts.cashTransactions.take(2).map { it.copy(amount = "99999999999999999999.999999999999999999",
            recordType = "income", occurredAt = "2026-08-12T03:00:00Z") }
        val q = query(base.copy(facts = base.facts.copy(cashTransactions = transactions)))
        assertEquals("199999999999999999999.999999999999999998", q.page(input(), startIndex = 0, limit = 1).monthlySummaries.single().currencies.single().income)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), cursor = "", startIndex = 0) }.code)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), startIndex = -1) }.code)
        assertEquals("invalid_cursor", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), expectedLedgerSeq = 0) }.code)
        assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), limit = 0) }.code)
        assertEquals("invalid_filter", assertFailsWith<CashProjectionDomainFailure> { q.page(input(), limit = 51) }.code)
    }

    @Test fun transferCashMembersMatchBothAccountsWithoutCountingAsIncomeOrExpense() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first()
        val transactions = listOf(source.copy(id = "1", amount = "-100", recordType = "transfer_out", recordSubtype = "ordinary_transfer"),
            source.copy(id = "2", amount = "100", recordId = "record:2", accountId = "20", recordType = "transfer_in", recordSubtype = "ordinary_transfer"))
        val relation = LedgerCashRelationFactDto("10", "transfer_pair", "1", "2", "1", "2", "1", "1", "outgoing", "incoming",
            "confirmed", true, "1", null, "system", JsonObject(emptyMap()), "0", "fixture", "user", source.createdAt, "user", source.createdAt, "")
        val q = query(base.copy(facts = base.facts.copy(cashTransactions = transactions,
            cashTransactionComponents = transactions.map { LedgerCashComponentFactDto(it.id, it.id, it.accountId, it.amount, "CNY", "0", "", "", JsonObject(emptyMap()), it.createdAt) },
            transactionRelations = listOf(relation), cashEntries = listOf(base.facts.cashEntries.first().copy(id = "one")),
            cashEntryMembers = transactions.map { LedgerCashEntryMemberFactDto(it.id, "one", JsonObject(emptyMap()), JsonArray(emptyList())) })))
        for (account in listOf("10", "20")) {
            val page = q.page(input(mapOf("account_id" to account, "hide_zero_amount" to "False")), startIndex = 0)
            assertEquals(1, page.totalCount); assertEquals("internal_transfer", page.items.single().economicType)
            assertEquals(10L, page.items.single().transfer!!.fromAccount.id); assertEquals(20L, page.items.single().transfer!!.toAccount.id)
            assertTrue(page.monthlySummaries.single().currencies.isEmpty())
        }
        assertTrue(q.page(input()).items.isEmpty())
        for (composition in listOf("single", "combined")) assertTrue(q.page(input(mapOf("composition" to composition, "hide_zero_amount" to "False"))).items.isEmpty())
    }
    @Test fun investmentDisplayAccountIsNotIncludedInCashAccountFiltering() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first().copy(recordType = "transfer_out", recordSubtype = "ordinary_transfer")
        val event = LedgerFundingEventFactDto("30", "USD", null, "2.340000000000000001")
        val link = LedgerInvestmentFundingFactDto("3", "11", "4", "cash_to_investment", "accepted", "fixture", JsonObject(emptyMap()),
            "active", "system", source.createdAt, "user", source.createdAt, "", event)
        val q = query(base.copy(facts = base.facts.copy(
            accounts = base.facts.accounts + LedgerAccountFactDto("30", "Investment", "security", true, JsonArray(emptyList()), "1"),
            cashTransactions = base.facts.cashTransactions.map { if (it.id == "1") source else it },
            cashTransactionComponents = listOf(LedgerCashComponentFactDto("11", "1", "10", source.amount, "CNY", "0", "", "", JsonObject(emptyMap()), source.createdAt)),
            cashInvestmentFundingRelations = listOf(link))))
        val result = q.page(input(mapOf("economic_type" to "internal_transfer", "business_label" to "bank_security_transfer", "hide_zero_amount" to "False")))
        assertEquals("1", result.items.single().transactionId); assertEquals(30L, result.items.single().transfer!!.toAccount.id)
        assertTrue(q.page(input(mapOf("account_id" to "30", "hide_zero_amount" to "False"))).items.isEmpty())
    }

    @Test fun unicodeEntryIdsUseCodePointOrderForBothSortingAndContinuation() {
        val base = snapshot()
        val identifiers = mapOf("1" to "a\uE000", "4" to "a𐀀")
        val q = query(base.copy(facts = base.facts.copy(
            cashEntries = base.facts.cashEntries.map { it.copy(id = identifiers[it.rootTransactionId] ?: it.id) },
            cashEntryMembers = base.facts.cashEntryMembers.map { it.copy(entryId = identifiers[it.transactionId] ?: it.entryId) })))
        val filters = input(mapOf("economic_type" to "expense"))
        val first = q.page(filters, limit = 1)
        assertEquals(listOf("4"), first.items.map { it.transactionId })
        assertEquals(listOf("1"), q.page(filters, cursor = first.nextCursor, limit = 1).items.map { it.transactionId })
        val empty = q.page(input(mapOf("currency" to "USD")), startIndex = 0)
        assertEquals(0, empty.totalCount); assertEquals(0L, empty.pendingItemCount)
        assertTrue(empty.monthlySummaries.isEmpty()); assertTrue(empty.monthPositions.isEmpty())
    }

}
