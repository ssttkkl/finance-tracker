package com.finance.tracker.presentation

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.domain.CashLedgerRepository
import com.finance.tracker.domain.CashPage
import com.finance.tracker.domain.CashProjection
import com.finance.tracker.domain.DomainFailure
import kotlinx.coroutines.CancellationException
import kotlin.math.max

internal class CashProjectionPagingSource(
    private val repository: CashLedgerRepository,
    private val filters: CashFilters,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
    private val onFirstPageError: (Throwable) -> Unit = {},
    private val onPageLoaded: (CashPage, firstPage: Boolean) -> Unit,
) : PagingSource<Int, CashProjection>() {
    private var projectionVersion: Long? = null

    override val jumpingSupported: Boolean = true

    override fun getRefreshKey(state: PagingState<Int, CashProjection>): Int? =
        state.anchorPosition?.let { max(0, it - state.config.initialLoadSize / 2) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CashProjection> {
        val startIndex = params.key?.coerceAtLeast(0) ?: 0
        val expectedVersion = projectionVersion
        return try {
            val page = repository.fetchCashPage(
                filters = filters,
                startIndex = startIndex,
                projectionVersion = expectedVersion,
                limit = params.loadSize.coerceIn(1, MAX_CASH_PAGE_SIZE),
            )
            if (page.startIndex != startIndex || page.totalCount == null) {
                val cause = IllegalStateException("Indexed cash page omitted its absolute position or total count")
                if (expectedVersion == null) onFirstPageError(cause)
                return LoadResult.Error(cause)
            }
            if (expectedVersion != null) {
                if (page.projectionVersion != expectedVersion) {
                    return LoadResult.Invalid()
                }
            } else {
                projectionVersion = page.projectionVersion
            }

            val itemCount = page.totalCount.coerceAtLeast(0)
            val itemsBefore = page.itemsBefore ?: startIndex.coerceAtMost(itemCount)
            val itemsAfter = page.itemsAfter ?: (itemCount - itemsBefore - page.items.size).coerceAtLeast(0)
            val endIndex = startIndex + page.items.size
            onPageLoaded(page, expectedVersion == null)
            LoadResult.Page(
                data = page.items,
                prevKey = startIndex.takeIf { it > 0 }?.let { (it - params.loadSize).coerceAtLeast(0) },
                nextKey = endIndex.takeIf { it < itemCount },
                itemsBefore = itemsBefore,
                itemsAfter = itemsAfter,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            if (cause is DomainFailure && cause.code == "projection.updated") {
                return LoadResult.Invalid()
            }
            cause.recordDiagnosticFailure(
                diagnostics,
                DiagnosticFeature.CASH_LEDGER,
                if (expectedVersion == null) DiagnosticAction.LOAD else DiagnosticAction.LOAD_NEXT_PAGE,
            )
            if (expectedVersion == null) onFirstPageError(cause)
            LoadResult.Error(cause)
        }
    }

    private companion object {
        const val MAX_CASH_PAGE_SIZE = 50
    }
}
