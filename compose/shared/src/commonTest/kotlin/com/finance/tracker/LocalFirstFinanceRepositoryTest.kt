package com.finance.tracker

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalFirstFinanceRepositoryTest {
    private class Reader : LedgerLocalReader {
        var page: CashPageDto? = null
        var accounts: List<AccountReferenceDto>? = null
        var categories: CategoryDirectoryDto? = null
        var options: LedgerOptionsDto? = null
        var pageFailure: Throwable? = null
        var calls = 0
        val timeZones = mutableListOf<String?>()

        override suspend fun page(filters: CashFiltersDto, timeZoneId: String?, cursor: String?,
            startIndex: Int?, expectedLedgerSeq: Long?, limit: Int): CashPageDto? {
            calls++
            timeZones += timeZoneId
            pageFailure?.let { throw it }
            return page
        }

        override suspend fun categories(): CategoryDirectoryDto? = categories
        override suspend fun options(): LedgerOptionsDto? = options
        override suspend fun accounts(): List<AccountReferenceDto>? = accounts
    }

    private class Delegate : FinanceRepository {
        var calls = mutableListOf<String>()
        var page = CashPage(projectionVersion = 99)
        var accounts = listOf(Account(99, "Server", "cash"))
        var categories = CashCategoryDirectory(99, emptyList())
        var options = LedgerOptions()

        override suspend fun fetchCashPage(filters: CashFilters, cursor: String?, startIndex: Int?,
            projectionVersion: Long?, limit: Int): CashPage { calls += "page"; return page }
        override suspend fun fetchCashAccounts(): List<Account> { calls += "accounts"; return accounts }
        override suspend fun fetchCashCategories(): CashCategoryDirectory { calls += "categories"; return categories }
        override suspend fun fetchLedgerOptions(): LedgerOptions { calls += "options"; return options }

        override suspend fun fetchEvidence(id: String): Evidence = error("unused")
        override suspend fun classifyCashProjections(ids: List<String>, projectionVersion: Long, categoryId: String?): CashClassificationResult = error("unused")
        override suspend fun fetchCashProjectionDeleteImpact(ids: List<String>, projectionVersion: Long): CashProjectionDeleteImpact = error("unused")
        override suspend fun deleteCashProjections(ids: List<String>, projectionVersion: Long): CashProjectionDeleteResult = error("unused")
        override suspend fun createCashCategory(name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = error("unused")
        override suspend fun updateCashCategory(id: String, name: String, parentId: String?, description: String, expectedRevision: Long): CashCategoryWriteResult = error("unused")
        override suspend fun reorderCashCategory(id: String, direction: String, expectedRevision: Long): CashCategoryWriteResult = error("unused")
        override suspend fun fetchCashCategoryDeletionImpact(id: String): CashCategoryDeleteImpact = error("unused")
        override suspend fun deleteCashCategory(id: String, impact: CashCategoryDeleteImpact): CashCategoryDeleteResult = error("unused")
        override suspend fun fetchInvestmentAccounts(): List<Account> = error("unused")
        override suspend fun fetchInvestmentPage(filters: InvestmentFilters, cursor: String?): InvestmentPage = error("unused")
        override suspend fun fetchInvestmentEvidence(eventId: String): InvestmentEvidence = error("unused")
        override suspend fun fetchInvestmentPortfolio(displayCurrency: String?, period: String, phase: String): Portfolio = error("unused")
        override suspend fun refreshInvestmentPortfolio(displayCurrency: String?, period: String) = error("unused")
        override suspend fun streamInvestmentPortfolio(displayCurrency: String?, period: String,
            onPortfolio: (Portfolio) -> Unit, onRefreshError: () -> Unit) = error("unused")
    }

    @Test fun aReadyReaderServesThePageAndCatalogsWithoutTouchingTheServer() = runTest {
        val reader = Reader().apply {
            page = CashPageDto(projectionVersion = 7, totalCount = 3, pendingItemCount = 1)
            accounts = listOf(AccountReferenceDto(1, "Cash", "cash", active = true, currencies = listOf("CNY")))
            categories = CategoryDirectoryDto(revision = 4, items = emptyList())
            options = LedgerOptionsDto()
        }
        val delegate = Delegate()
        val repository = LocalFirstFinanceRepository(delegate) { reader }
        val page = repository.fetchCashPage(CashFilters())
        assertEquals(7, page.projectionVersion); assertEquals(3, page.totalCount)
        assertEquals(listOf<String?>(com.finance.tracker.core.getLocalTimeZoneId()), reader.timeZones)
        assertEquals(listOf(Account(1, "Cash", "cash", true, listOf("CNY"))), repository.fetchCashAccounts())
        assertEquals(4, repository.fetchCashCategories().revision)
        assertEquals(LedgerOptions(), repository.fetchLedgerOptions())
        assertTrue(delegate.calls.isEmpty())
    }

    @Test fun aReaderWithoutCommittedFactsFallsBackToTheServer() = runTest {
        val delegate = Delegate()
        val repository = LocalFirstFinanceRepository(delegate) { Reader() }
        assertEquals(99, repository.fetchCashPage(CashFilters()).projectionVersion)
        assertEquals(delegate.calls, listOf("page"))
        repository.fetchCashAccounts(); repository.fetchCashCategories(); repository.fetchLedgerOptions()
        assertEquals(listOf("page", "accounts", "categories", "options"), delegate.calls)
    }

    @Test fun noActiveRestorationUsesTheServerOnly() = runTest {
        val delegate = Delegate()
        val repository = LocalFirstFinanceRepository(delegate) { null }
        repository.fetchCashPage(CashFilters())
        assertEquals(listOf("page"), delegate.calls)
    }

    @Test fun anInvalidLocalFilterReportsTheSameDomainCodeAsTheServer() = runTest {
        val reader = Reader().apply { pageFailure = CashProjectionDomainFailure("invalid_filter") }
        val delegate = Delegate()
        val repository = LocalFirstFinanceRepository(delegate) { reader }
        val thrown = assertFailsWith<DomainFailure> { repository.fetchCashPage(CashFilters()) }
        assertEquals("invalid_filter", thrown.code)
        assertEquals(400, thrown.status)
        assertEquals(FailureCategory.RECOVERABLE, thrown.category)
        assertTrue(delegate.calls.isEmpty())
    }

    @Test fun anUnknownLocalFailureKeepsTheSharedFailureMapping() = runTest {
        val reader = Reader().apply { pageFailure = IllegalStateException("broken cache") }
        val repository = LocalFirstFinanceRepository(Delegate()) { reader }
        val thrown = assertFailsWith<DomainFailure> { repository.fetchCashPage(CashFilters()) }
        assertEquals("unknown_error", thrown.code)
    }

    @Test fun aReaderClosedDuringTheReadFallsBackToTheServer() = runTest {
        val reader = Reader().apply { pageFailure = kotlinx.coroutines.CancellationException("superseded") }
        val delegate = Delegate()
        val repository = LocalFirstFinanceRepository(delegate) { reader }
        assertEquals(99, repository.fetchCashPage(CashFilters()).projectionVersion)
        assertEquals(listOf("page"), delegate.calls)
    }
}
