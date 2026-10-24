package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.cashLedgerMonthPositionForOrdinal
import com.finance.tracker.presentation.cashProjectionAmountLabel
import com.finance.tracker.presentation.CashProjectionPagingSource
import androidx.paging.PagingSource
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CashCurrentListContractTest {
    @Test fun draftKeepsUnknownMoneyTimeAndStatisticsEligibility() {
        val page = apiJson.decodeFromString<CashPageDto>(PAGE).toDomain()
        val draft = page.items.single()
        assertNull(draft.occurredAt)
        assertNull(draft.amount)
        assertNull(draft.currency)
        assertNull(draft.economicType)
        assertEquals("11", draft.transactionId)
        assertEquals("source-transaction-11", draft.recordId)
        assertEquals("draft", draft.state)
        assertEquals("pending", draft.confirmationStatus)
        assertFalse(draft.statisticsEligible)
        assertEquals("entry-one", draft.entryId)
        assertEquals(2L, draft.attentionCount)
        assertEquals(1L, page.pendingItemCount)
        assertEquals("—", cashProjectionAmountLabel(draft))
    }
    @Test fun repeatedMonthAfterUnknownKeepsItsOwnAbsoluteStart() {
        val positions = listOf(CashMonthPosition("2026-10", 0, 1), CashMonthPosition("unknown", 1, 2), CashMonthPosition("2026-10", 2, 3))
        assertEquals(2, cashLedgerMonthPositionForOrdinal(positions, 2)?.startIndex)
        assertEquals("unknown", cashLedgerMonthPositionForOrdinal(positions, 1)?.month)
        assertNull(cashLedgerMonthPositionForOrdinal(positions, 3))
    }
    @Test fun zeroRefundIsStillARealVisibleAmount() {
        val draft = apiJson.decodeFromString<CashPageDto>(PAGE).toDomain().items.single()
        val refund = draft.copy(amount = "0.00", currency = "CNY", state = "posted", statisticsEligible = true)
        assertEquals("0.00 CNY", cashProjectionAmountLabel(refund))
    }
    @Test fun changedSnapshotInvalidatesInsteadOfOfferingAppendRetry() = runTest {
        val repository = object : CashLedgerRepository by DemoFinanceRepository() {
            override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?, projectionVersion: Long?, limit: Int): CashPage =
                throw DomainFailure("read_snapshot_changed", 409, FailureCategory.RECOVERABLE)
        }
        val source = CashProjectionPagingSource(repository, CashFilters(), onPageLoaded = { _, _ -> })
        assertTrue(source.load(PagingSource.LoadParams.Append(20, 20, true)) is PagingSource.LoadResult.Invalid)
    }
    @Test fun stateFilterKeepsOtherFiltersAndCurrentCursor() = runTest {
        var call: HttpRequestData? = null
        val api = FinanceApiClient({ "https://api.example.test" }, ListTokenStore(), MockEngine {
            call = it; respond(PAGE, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        api.fetchCashPage(CashFilters(currency = "CNY", categoryId = "category-one", state = "draft", confirmationStatus = "pending").toDto(), "next/+&")
        val query = assertNotNull(call).url.parameters
        assertEquals("draft", query["state"])
        assertEquals("pending", query["confirmation_status"])
        assertEquals("CNY", query["currency"])
        assertEquals("category-one", query["category_id"])
        assertEquals("next/+&", query["cursor"])
        api.close()
    }
}
private class ListTokenStore : TokenStore {
    override suspend fun get() = "synthetic-token"
    override suspend fun set(value: String) = Unit
    override suspend fun clear() = Unit
}
private const val PAGE = """{"projection_version":9,"pending_item_count":1,"items":[{"projection_id":"draft-11","record_id":"source-transaction-11","transaction_id":"11","entry_id":"entry-one","occurred_at":null,"amount":null,"currency":null,"economic_type":null,"state":"draft","statistics_eligible":false,"confirmation_status":"pending","attention_count":2}],"next_cursor":null,"page_size":20,"filters":{},"filter_options":{},"monthly_summaries":[]}"""
