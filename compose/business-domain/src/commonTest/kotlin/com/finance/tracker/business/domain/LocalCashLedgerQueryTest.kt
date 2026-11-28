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
    @Test fun diagnosticColumnsKeepTheLiveCursorFiltersTotalsAndAmount() {
        val live = query()
        val saved = mutableMapOf<Pair<Int, Boolean>, String>()
        val manifest = live.writeDiagnosticCashList { index, details, text -> saved[index to details] = text }
        val configurations = listOf(input(), input(mapOf("amount_max" to "-0.000000000000000001")),
            input(mapOf("counterparty" to "literal%_\\note")), input(mapOf("confirmation_status" to "pending")),
            input(mapOf("account_id" to "20")), input(start = "2026-08-01T00:00:00Z"))
        for (filter in configurations) {
            val first = live.page(filter, limit = 1)
            fun read(cursor: String? = null, index: Int? = null) = readDiagnosticCashList(manifest,
                { block, detail -> saved.getValue(block to detail) },
                { time, _ -> cashLedgerMonthAtOffset(time, 0) }, filter, cursor, index, null, 1)
            assertEquals(first, read())
            first.nextCursor?.let { cursor -> assertEquals(live.page(filter, cursor = cursor, limit = 1), read(cursor = cursor)) }
            assertEquals(live.page(filter, startIndex = 1, limit = 1), read(index = 1))
        }
    }
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
    @Test fun pageRowsMatchFullRenderingWithGlobalOptionsAndPrecision() {
        val facts = snapshot()
        val q = query(facts)
        val full = buildLocalCashProjectionViews(facts).associateBy { it.transactionId }
        val expected = listOf("3", "4", "1", "2").map(full::getValue)
        val page = q.page(input(), startIndex = 0, limit = 2)
        val later = q.page(input(), startIndex = 2, expectedLedgerSeq = page.projectionVersion, limit = 2)
        assertEquals(expected, page.items + later.items)
        assertEquals(buildLocalCashFilterOptions(facts), page.filterOptions)
        assertEquals(page.filterOptions, later.filterOptions)
        assertEquals(page.totalCount, later.totalCount)
        assertEquals(page.pendingItemCount, later.pendingItemCount)
        assertEquals("-12.340000000000000002", page.monthlySummaries.first().currencies.single().expense)
    }

    @Test fun badFundingAmountOutsidePageStillRejectsWholeSnapshot() {
        val base = snapshot()
        val row = base.facts.cashTransactions.first().copy(recordType = "transfer_out", recordSubtype = "ordinary_transfer")
        val component = LedgerCashComponentFactDto("91", row.id, "10", row.amount, "CNY", "0", "", "",
            JsonObject(emptyMap()), row.createdAt)
        val event = LedgerFundingEventFactDto("20", "USD", null, "invalid")
        val funding = LedgerInvestmentFundingFactDto("3", component.id, "4", "cash_to_investment",
            "accepted", "fixture", JsonObject(emptyMap()), "active", "system", row.createdAt, "user", row.createdAt, "", event)
        val facts = base.facts.copy(
            cashTransactions = base.facts.cashTransactions.map { if (it.id == row.id) row else it },
            cashTransactionComponents = listOf(component),
            cashInvestmentFundingRelations = listOf(funding),
        )
        val broken = base.copy(facts = facts)
        val error = assertFailsWith<IllegalArgumentException> { buildLocalCashProjectionViews(broken) }
        assertEquals(error.message, assertFailsWith<IllegalArgumentException> {
            query(broken).page(input(), limit = 1)
        }.message)
        val absent = funding.copy(investmentEvent = event.copy(toAmount = null))
        val compatible = query(base.copy(facts = facts.copy(cashInvestmentFundingRelations = listOf(absent))))
        val first = compatible.page(input(), limit = 1)
        assertEquals("3", first.items.single().transactionId)
        assertEquals(listOf("bank_security_transfer"),
            first.filterOptions.economicTypes.single { it.economicType == "internal_transfer" }.businessLabels)
        val fundingPage = compatible.page(input(mapOf("business_label" to "bank_security_transfer",
            "hide_zero_amount" to "False")), limit = 1)
        assertEquals("1", fundingPage.items.single().transactionId)
        assertNull(fundingPage.items.single().transfer)
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
        assertEquals(listOf("1", "2", "3"), q.currentGroup("cash-entry:one").members.map { it.transactionId })
        val evidence = q.evidence("cash-entry:one")
        assertEquals(listOf("10", "11"), evidence.getValue("accepted_relations").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(listOf("record:3"), evidence.getValue("refund_timeline").jsonArray.map { it.jsonObject.getValue("record_id").jsonPrimitive.content })
        assertEquals(JsonPrimitive("3"), evidence.getValue("accepted_relations").jsonArray[1].jsonObject.getValue("secondary_record").jsonObject["component_id"])
        assertEquals(listOf("2", "1", "3").map { CashMutationProjectionRefDto(it, "cash-entry:one") },
            q.projectionRefs(listOf("2", "1", "2", "3", "missing")))
        for (composition in listOf("combined", "payment_mirror", "refund_offset")) {
            val page = q.page(input(mapOf("composition" to composition)), startIndex = 0)
            assertEquals("-75", page.items.single().amount); assertEquals(3, page.items.single().memberCount)
            assertEquals(listOf("payment_mirror", "refund_offset"), page.items.single().composition)
        }
        assertTrue(q.page(input(mapOf("composition" to "single"))).items.isEmpty())
        val full = query(related("100"))
        assertTrue(full.page(input()).items.isEmpty())
        assertEquals(listOf(CashMutationProjectionRefDto("3", "cash-entry:one")), full.projectionRefs(listOf("3")))
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

    @Test fun currentMembersUseEachOriginalRevisionAndConfirmationState() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first()
        val relation = LedgerCashRelationFactDto("10", "refund_offset", "1", "2", null, null, "1", null,
            null, null, "draft", true, "1", null, "system", JsonObject(emptyMap()), "0", "fixture", "user", source.createdAt, "", null, "")
        val facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map { if (it.id == "1") it.copy(rowRevision = Long.MAX_VALUE.toString()) else it },
            transactionRelations = listOf(relation, relation.copy(id = "11", primaryTransactionId = "4", anchorTransactionId = "4", state = "ignored")))
        val q = query(base.copy(facts = facts))
        assertEquals(CashCurrentMembersDto(9007199254740993L, listOf(CashCurrentMemberDto("1", Long.MAX_VALUE, "pending"))), q.currentGroup("cash-entry:entry:1"))
        assertEquals("pending", q.currentGroup("cash-entry:entry:2").members.single().confirmationStatus)
        assertEquals("pending", q.currentGroup("cash-entry:entry:3").members.single().confirmationStatus)
        assertEquals("confirmed", q.currentGroup("cash-entry:entry:4").members.single().confirmationStatus)
        assertEquals("projection_not_found", assertFailsWith<CashProjectionDomainFailure> { q.currentGroup("missing") }.code)
        assertEquals("not_found", assertFailsWith<CashProjectionDomainFailure> { q.evidence("missing") }.code)
        val deleted = query(base.copy(facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map {
            if (it.id == "1") it.copy(deletedAt = it.createdAt) else it })))
        assertEquals("not_found", assertFailsWith<CashProjectionDomainFailure> { deleted.evidence("cash-entry:entry:1") }.code)
    }

    @Test fun evidenceKeepsRawSourceSeparateFromEntryAndPreservesWideOrdinals() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first()
        val part = LedgerCashComponentFactDto("11", "1", "20", "-0.000000000000000001", "CNY", "2147483648", "raw label", "", JsonNull, source.createdAt)
        val q = query(base.copy(facts = base.facts.copy(cashTransactionComponents = listOf(part, part.copy(id = "12", ordinal = "0", amount = "-0.000")))))
        val evidence = q.evidence("cash-entry:entry:1")
        val root = evidence.getValue("root_record").jsonObject
        assertEquals(JsonPrimitive("source value"), root["counterparty"])
        assertEquals(JsonPrimitive("Cafe"), evidence.getValue("projection").jsonObject["counterparty"])
        assertEquals(JsonPrimitive("-12.340000000000000001"), root["amount"])
        assertEquals(JsonPrimitive("2026-08-12T03:00:00+00:00"), root["occurred_at"])
        val components = root.getValue("components").jsonArray
        assertEquals(listOf("12", "11"), components.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertEquals(JsonPrimitive(2147483648L), components[1].jsonObject["ordinal"])
        assertEquals(JsonPrimitive("0"), components[0].jsonObject["amount"])
        assertEquals(setOf("id", "name", "type", "active"), components[1].jsonObject.getValue("account").jsonObject.keys)
    }

    private fun fundingEvidenceSnapshot(evidence: JsonElement): LedgerSnapshotDto {
        val base = snapshot()
        val source = base.facts.cashTransactions.first()
        val link = LedgerInvestmentFundingFactDto("3", "11", "4", "cash_to_investment", "accepted", "fixture", evidence,
            "active", "system", source.createdAt, "user", source.createdAt, "", LedgerFundingEventFactDto("20", "USD", null, "2.340000000000000001"))
        return base.copy(facts = base.facts.copy(cashTransactionComponents = listOf(
            LedgerCashComponentFactDto("11", "1", "10", source.amount, "CNY", "0", "", "", JsonNull, source.createdAt)),
            cashInvestmentFundingRelations = listOf(link)))
    }

    @Test fun fundingEvidenceExposesOnlyExistingSafeFieldsAndRejectsCorruption() {
        val safe = Json.parseToJsonElement("""{"business_day_window":7,"candidate_count":100000,"cash_record_type":"investment_out","match_keys":["amount","currency","direction","business_day"]}""")
        val q = query(fundingEvidenceSnapshot(JsonObject(safe.jsonObject + ("private" to JsonPrimitive("secret")))))
        val result = q.evidence("cash-entry:entry:1")
        assertEquals(safe, result.getValue("funding_relation").jsonObject["evidence"])
        assertEquals(JsonPrimitive("2.340000000000000001"), result.getValue("projection").jsonObject.getValue("transfer").jsonObject["to_amount"])
        for (value in listOf("[]", "null", """{"business_day_window":8,"candidate_count":"2","cash_record_type":"private","match_keys":["amount"]}""")) {
            assertEquals(JsonObject(emptyMap()), query(fundingEvidenceSnapshot(Json.parseToJsonElement(value))).evidence("cash-entry:entry:1").getValue("funding_relation").jsonObject["evidence"])
        }
        for (value in listOf("[]", "{}", "[1]")) {
            val bad = fundingEvidenceSnapshot(JsonObject(mapOf("cash_record_type" to Json.parseToJsonElement(value))))
            assertFailsWith<IllegalStateException> { query(bad).evidence("cash-entry:entry:1") }
        }
    }

    @Test fun fullRecordKeepsOriginalPayloadPublicFieldsTokenAndReadOnlyCapabilities() {
        val base = snapshot()
        val payload = Json.parseToJsonElement("""{"note":"original","integer":9007199254740993,"float":1.2300}""")
        val facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map {
            if (it.id == "1") it.copy(recordOrigin = "manual", sourcePayload = payload) else it })
        val q = query(base.copy(facts = facts))
        val record = q.record("0001", true)
        assertEquals(JsonPrimitive("1"), record["transaction_id"])
        assertEquals(payload, record.getValue("fields").jsonObject["source_payload"])
        assertEquals(JsonPrimitive("source value"), record.getValue("fields").jsonObject["counterparty"])
        val entry = record.getValue("entry").jsonObject
        assertEquals(JsonPrimitive("Cafe"), entry.getValue("fields").jsonObject["counterparty"])
        assertEquals(JsonPrimitive("original"), entry["original_note"])
        assertEquals(JsonPrimitive("72b0a18d6e36a56da9b1f1cea25e913a165383699fbf1a168f0df426a8cc2f1c"), entry["original_note_token"])
        assertTrue(JsonPrimitive("amount") in record.getValue("editable_fields").jsonArray)
        assertFalse(JsonPrimitive("source_payload") in record.getValue("editable_fields").jsonArray)
        val readOnly = q.record("1", false)
        assertEquals(JsonArray(emptyList()), readOnly["editable_fields"])
        assertEquals(JsonArray(emptyList()), readOnly.getValue("entry").jsonObject["editable_fields"])
        assertEquals(JsonPrimitive(9007199254740993L), record.getValue("projection_ref").jsonObject["projection_version"])
        for (id in listOf("", "0", "-1", "+1", " 1", "1.0", "999", "9223372036854775808"))
            assertEquals("record_not_found", assertFailsWith<CashProjectionDomainFailure> { q.record(id, true) }.code)
    }

    @Test fun sourceIssueDraftAmountChoiceAndExtremeOverrideTruthMatchOriginalPolicy() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first()
        val issue = LedgerCashIssueFactDto("9", "1", "pending", "source_update_pending", null, JsonNull,
            buildJsonObject { put("amount", "-99") }, JsonObject(emptyMap()), "9007199254740993", source.createdAt, source.createdAt)
        for ((override, protected) in listOf("0" to false, "-0.00" to false, "1" to true, "0e2147483647" to false,
            "0e2147483648" to false, "0e2147483649" to true, "0.0e2147483649" to false, "1e2147483649" to true, "0.0e-2147483647" to true)) {
            val facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map {
                if (it.id == "1") it.copy(state = "draft", manualOverrides = Json.parseToJsonElement("{\"record_type\":$override}")) else it },
                cashTransactionIssues = listOf(issue), cashTransactionComponents = listOf(
                    LedgerCashComponentFactDto("11", "1", "10", source.amount, "CNY", "0", "", "", JsonNull, source.createdAt)))
            val q = query(base.copy(facts = facts))
            val actual = q.record("1", true).getValue("issues").jsonArray.single().jsonObject
            assertEquals(JsonPrimitive(9007199254740993L), actual["revision"])
            assertEquals(JsonArray(listOf("apply_source", "keep_existing").map(::JsonPrimitive)), actual["allowed_resolutions"])
            assertEquals(JsonArray(emptyList()), q.record("1", false).getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
            val observable = facts.copy(cashTransactions = facts.cashTransactions.map {
                if (it.id == "1") it.copy(state = "posted", recordOrigin = "manual") else it },
                cashTransactionIssues = listOf(issue.copy(pendingFields = buildJsonObject { put("record_type", "unknown") })))
            assertEquals(JsonArray((if (protected) listOf("apply_source", "keep_existing") else listOf("keep_existing")).map(::JsonPrimitive)),
                query(base.copy(facts = observable)).record("1", true).getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
        }
    }

    @Test fun corruptPendingFieldsAndIdentityCoverageStopInsteadOfPublishingPartialDetails() {
        val base = snapshot()
        val source = base.facts.cashTransactions.first().copy(state = "draft", sourceType = "fixture")
        val issue = LedgerCashIssueFactDto("9", "1", "pending", "source_identity_ambiguous", null, JsonNull,
            JsonObject(emptyMap()), JsonObject(emptyMap()), "1", source.createdAt, source.createdAt)
        val binding = LedgerSourceBindingFactDto("1", "1", "fixture", "account", "scope", "provider_id", "provider", "provider", null,
            "fixture", JsonArray(emptyList()), "1", source.createdAt, source.createdAt)
        val facts = base.facts.copy(cashTransactions = base.facts.cashTransactions.map { if (it.id == "1") source else it },
            cashTransactionIssues = listOf(issue), cashSourceBindings = listOf(binding))
        val q = query(base.copy(facts = facts))
        assertFailsWith<IllegalStateException> { q.record("1", true) }
        assertEquals(JsonArray(emptyList()), q.record("1", false).getValue("issues").jsonArray.single().jsonObject["allowed_resolutions"])
        val corrupt = query(base.copy(facts = facts.copy(cashSourceBindings = emptyList(),
            cashTransactionIssues = listOf(issue.copy(pendingFields = JsonArray(listOf(JsonPrimitive("invalid"))))))))
        for (canWrite in listOf(true, false)) assertFailsWith<IllegalStateException> { corrupt.record("1", canWrite) }
    }

    @Test fun importedPendingSubsetFiltersRowsCountsAndCursorTogether() {
        val base = snapshot()
        val issues = base.facts.cashTransactions.filter { it.id in setOf("1", "4") }.map {
            LedgerCashIssueFactDto("99${it.id}", it.id, "pending", "source_update_pending", null,
                JsonObject(emptyMap()), JsonObject(emptyMap()), JsonObject(emptyMap()), "1", it.createdAt, it.createdAt)
        }
        val q = query(base.copy(facts = base.facts.copy(cashTransactionIssues = issues)))
        fun scope(ids: Set<String>) = normalizeLocalCashLedgerQueryInput(
            CashFiltersDto(confirmationStatus = "pending", importTransactionIds = ids), null,
        ) { _, _, _ -> CashLedgerDateBounds(null, null, "UTC", null, null) }
        val batchIds = setOf("1", "4")
        val first = q.page(scope(batchIds), limit = 1)
        assertEquals(2, q.page(scope(batchIds), startIndex = 0, limit = 1).totalCount)
        assertEquals(2L, first.pendingItemCount)
        val second = q.page(scope(batchIds.reversed().toSet()), cursor = assertNotNull(first.nextCursor), limit = 1)
        assertEquals(batchIds, (first.items + second.items).map { it.transactionId }.toSet())
        assertFailsWith<CashProjectionDomainFailure> { q.page(scope(setOf("1", "3")), cursor = first.nextCursor) }
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
