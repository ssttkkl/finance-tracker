package com.finance.tracker

import com.finance.tracker.domain.*
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.presentation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentViewModelTest {
    @Test
    fun reportsInvalidCurrencyDraftAndRestoresTheLastValidValue() {
        val logger = RecordingDiagnosticLogger()
        val viewModel = InvestmentHoldingsViewModel(FakeInvestmentRepository(), diagnostics = logger)
        viewModel.updateCurrencyDraft("US")

        viewModel.reportInvalidCurrencyDraft()

        assertEquals("", viewModel.state.value.currencyDraft)
        assertEquals(
            listOf(
                RecordedDiagnostic(
                    DiagnosticFeature.INVESTMENT_HOLDINGS,
                    DiagnosticAction.VALIDATE_INPUT,
                    "valuation.invalid_display_currency",
                    null,
                    "InputValidationFailure",
                ),
            ),
            logger.entries,
        )
    }

    @Test
    fun holdingsLoadsDomainModelsAndStreamUpdatesRefreshState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeInvestmentRepository()
            val viewModel = InvestmentHoldingsViewModel(repository)
            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(listOf("Broker"), viewModel.state.value.accounts.map(Account::name))
            assertEquals("AAPL", viewModel.state.value.portfolio?.accounts?.single()?.positions?.single()?.ticker)

            viewModel.refresh()
            runCurrent()
            assertEquals(true, viewModel.state.value.refreshing)
            repository.emitPortfolio(repository.portfolio.copy(totalMarketValue = "125"))
            assertEquals(false, viewModel.state.value.refreshing)
            assertEquals("125", viewModel.state.value.portfolio?.totalMarketValue)
            assertEquals(null, viewModel.state.value.refreshMessageCode)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun investmentEventsKeepCursorPagingSelectionAndEvidenceInState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FakeInvestmentRepository()
            val viewModel = InvestmentEventsViewModel(repository)
            viewModel.initialize()
            advanceUntilIdle()

            assertEquals(listOf("event-1"), viewModel.state.value.events.map(InvestmentEvent::eventId))
            viewModel.loadMore()
            advanceUntilIdle()
            assertEquals(listOf("event-1", "event-2"), viewModel.state.value.events.map(InvestmentEvent::eventId))
            assertEquals(listOf(null, "next"), repository.pageCursors)

            val selected = viewModel.state.value.events.last()
            viewModel.selectEvent(selected)
            advanceUntilIdle()
            assertEquals(selected.eventId, viewModel.state.value.selected?.eventId)
            assertEquals(selected.eventId, viewModel.state.value.evidence?.event?.eventId)

            viewModel.updateFilters(InvestmentFilters(recordType = "trade"))
            advanceUntilIdle()
            assertEquals("trade", repository.lastFilters.recordType)
            assertEquals(null, viewModel.state.value.selected)
            assertFalse(viewModel.state.value.loading)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeInvestmentRepository : InvestmentRepository {
    val pageCursors = mutableListOf<String?>()
    var lastFilters = InvestmentFilters()
    var onPortfolio: ((Portfolio) -> Unit)? = null
    val account = Account(1, "Broker", "investment")
    val firstEvent = event("event-1")
    val secondEvent = event("event-2")
    val portfolio = Portfolio(
        accounts = listOf(
            PortfolioAccount(
                name = "Broker",
                currency = "USD",
                positions = listOf(PortfolioPosition("AAPL", shares = "1", totalCost = "100", costCurrency = "USD")),
            ),
        ),
        totalMarketValue = "100",
    )

    override suspend fun fetchInvestmentAccounts(): List<Account> = listOf(account)

    override suspend fun fetchInvestmentPage(filters: InvestmentFilters, cursor: String?): InvestmentPage {
        lastFilters = filters
        pageCursors += cursor
        return if (cursor == null) {
            InvestmentPage(4, listOf(firstEvent), nextCursor = "next", pageSize = 1)
        } else {
            InvestmentPage(4, listOf(firstEvent, secondEvent), nextCursor = null, pageSize = 2)
        }
    }

    override suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidence {
        val value = if (eventId == secondEvent.eventId) secondEvent else firstEvent
        return InvestmentEvidence(4, value)
    }

    override suspend fun fetchInvestmentPortfolio(displayCurrency: String?, period: String, phase: String): Portfolio = portfolio
    override suspend fun refreshInvestmentPortfolio(displayCurrency: String?, period: String) = Unit

    override suspend fun streamInvestmentPortfolio(
        displayCurrency: String?,
        period: String,
        onPortfolio: (Portfolio) -> Unit,
        onRefreshError: () -> Unit,
    ) {
        this.onPortfolio = onPortfolio
    }

    fun emitPortfolio(value: Portfolio) {
        onPortfolio?.invoke(value)
    }

    private fun event(id: String) = InvestmentEvent(
        eventId = id,
        occurredAt = "2026-09-26T10:00:00Z",
        account = account,
        recordType = "trade",
        currency = "USD",
        recordId = "record-$id",
    )
}
