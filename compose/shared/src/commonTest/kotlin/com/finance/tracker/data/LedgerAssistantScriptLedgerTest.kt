package com.finance.tracker.data

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashMonthlyCurrencySummaryDto
import com.finance.tracker.business.contracts.CashMonthlySummaryDto
import com.finance.tracker.business.contracts.CashPageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
