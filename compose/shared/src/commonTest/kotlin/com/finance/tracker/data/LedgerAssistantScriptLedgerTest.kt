package com.finance.tracker.data

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMonthlyCurrencySummaryDto
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.LedgerCashRelationFactDto
import com.finance.tracker.business.contracts.LedgerCashComponentFactDto
import com.finance.tracker.core.LocalCashLedgerReader
import com.finance.tracker.localCashLedgerOwnerSnapshot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LedgerAssistantScriptLedgerTest {
    @Test
    fun checksVersionBeforeReturningReadsAndBeforeCommitting() = runTest {
        val reader = Reader()
        val host = LedgerAssistantScriptLedger(reader, "workspace") {}
        val filter = Json.parseToJsonElement("""[{"currency":"CNY","time_zone_id":"Asia/Shanghai"}]""") as JsonArray
        val result = host.read("ft.transactions.summarize", filter).jsonObject
        assertEquals(JsonPrimitive("9007199254740993"), result["ledger_seq"])
        assertEquals(Json.parseToJsonElement("""[{"month":"2026-11","currencies":[{"currency":"CNY","income":"0","expense":"-88.000"}]}]"""), result["months"])
        val accounts = host.read("ft.accounts.list", JsonArray(emptyList())) as JsonArray
        assertEquals(JsonPrimitive("9007199254740993"), accounts.single().jsonObject["id"])
        reader.version++
        assertEquals("ledger_changed", assertFailsWith<LedgerAssistantScriptFailure> { host.checkVersion() }.reason)
        assertEquals("ledger_changed", assertFailsWith<LedgerAssistantScriptFailure> {
            host.read("ft.transactions.summarize", filter)
        }.reason)
    }


    @Test
    fun matchesCommittedLedgerAcrossThreeCurrenciesAndAnAcceptedRefund() = runTest {
        val base = localCashLedgerOwnerSnapshot()
        val facts = base.facts
        val original = facts.cashTransactions.first()
        val purchases = listOf("CNY" to "-88.25", "USD" to "-12.34", "JPY" to "-900")
        val transactions = purchases.mapIndexed { index, (currency, amount) ->
            val id = (index + 1).toString()
            original.copy(id = id, recordId = "record:$id", currency = currency, amount = amount,
                occurredAt = "2026-03-31T16:15:00Z")
        } + original.copy(id = "4", recordId = "record:4", currency = "CNY", amount = "8.25",
            recordType = "refund", occurredAt = "2026-04-02T00:00:00Z")
        val relation = LedgerCashRelationFactDto("1", "refund_offset", "1", "4", "1", "4", "1", "1",
            "expense", "refund", "confirmed", true, "1", null, "system", JsonObject(emptyMap()), "8.25",
            "fixture", "qa", original.createdAt, "qa", original.createdAt, "")
        val reader = LocalCashLedgerReader(base.copy(facts = facts.copy(
            cashTransactions = transactions,
            cashTransactionComponents = transactions.map { row ->
                LedgerCashComponentFactDto(row.id, row.id, row.accountId, row.amount, row.currency, "0", "", "",
                    JsonObject(emptyMap()), row.createdAt)
            },
            cashEntries = purchases.indices.map { index ->
                val id = (index + 1).toString()
                facts.cashEntries.first().copy(id = "entry:$id", rootTransactionId = id, identityTransactionId = id)
            },
            cashEntryMembers = transactions.map { row ->
                facts.cashEntryMembers.first().copy(transactionId = row.id, entryId = "entry:${if (row.id == "4") "1" else row.id}")
            },
            transactionRelations = listOf(relation),
        )))
        val adapter = object : LedgerLocalReader by Reader() {
            override suspend fun page(filters: CashFiltersDto, timeZoneId: String?, cursor: String?,
                startIndex: Int?, expectedLedgerSeq: Long?, limit: Int) =
                reader.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
        }
        val host = LedgerAssistantScriptLedger(adapter, base.workspaceId) {}
        val filter = Json.parseToJsonElement("""[{"time_zone_id":"Asia/Shanghai"}]""") as JsonArray
        val inApp = reader.page(timeZoneId = "Asia/Shanghai")
        val summary = host.read("ft.transactions.summarize", filter).jsonObject
        assertEquals(Json.encodeToJsonElement(inApp.monthlySummaries), summary["months"])
        assertEquals("2026-04", inApp.monthlySummaries.single().month)
        assertEquals(mapOf("CNY" to "-80", "USD" to "-12.34", "JPY" to "-900"),
            inApp.monthlySummaries.single().currencies.associate { it.currency to it.expense })
        val page = host.read("ft.transactions.list", JsonArray(filter + JsonNull)).jsonObject
        val expected = inApp.items.map { it.projectionId to it.amount }
        val actual = (page["items"] as JsonArray).map {
            val row = it.jsonObject
            (row["projection_id"] as JsonPrimitive).content to (row["amount"] as JsonPrimitive).content
        }
        assertEquals(3, actual.size)
        assertEquals(expected, actual)
    }

    private class Reader : LedgerLocalReader {
        var version = 9_007_199_254_740_993L
        override suspend fun ledgerVersion() = version
        override suspend fun page(
            filters: CashFiltersDto,
            timeZoneId: String?,
            cursor: String?,
            startIndex: Int?,
            expectedLedgerSeq: Long?,
            limit: Int,
        ): CashPageDto {
            assertEquals("Asia/Shanghai", timeZoneId)
            assertEquals("CNY", filters.currency)
            return CashPageDto(version, monthlySummaries = listOf(
                CashMonthlySummaryDto("2026-11", listOf(CashMonthlyCurrencySummaryDto("CNY", "0", "-88.000"))),
            ))
        }

        override suspend fun currentGroup(projectionId: String) = error("no detail read")
        override suspend fun record(transactionId: String) = error("no record read")
        override suspend fun sourceRecords(projectionId: String) = error("no source record read")
        override suspend fun accounts() = listOf(AccountReferenceDto(version, "Test", "cash"))
        override suspend fun categories() = error("no directory read")
        override suspend fun options() = error("no options read")
    }
}
