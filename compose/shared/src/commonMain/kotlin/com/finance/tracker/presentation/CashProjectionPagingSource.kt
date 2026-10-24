package com.finance.tracker.presentation

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.finance.tracker.core.DiagnosticAction
import com.finance.tracker.core.DiagnosticFeature
import com.finance.tracker.core.DiagnosticLogger
import com.finance.tracker.core.NoOpDiagnosticLogger
import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.domain.CashLedgerRepository
import com.finance.tracker.domain.CashPage
import com.finance.tracker.domain.CashProjection
import kotlinx.coroutines.CancellationException

internal class CashProjectionPagingSource(
    private val repository: CashLedgerRepository,
    private val filters: CashFilters,
    private val diagnostics: DiagnosticLogger = NoOpDiagnosticLogger,
    private val onSnapshotInvalidated: () -> Unit = {},
    private val onPageLoaded: (CashPage, firstPage: Boolean) -> Unit,
) : PagingSource<String, CashProjection>() {
    private var projectionVersion: Long? = null
    private val loadedProjectionIds = mutableSetOf<String>()

    override fun getRefreshKey(state: PagingState<String, CashProjection>): String? = null

    override suspend fun load(params: LoadParams<String>): LoadResult<String, CashProjection> {
        val cursor = params.key
        return try {
            val page = repository.fetchCashPage(filters, cursor)
            if (cursor != null) {
                val expectedVersion = projectionVersion
                if (expectedVersion == null || page.projectionVersion != expectedVersion) {
                    onSnapshotInvalidated()
                    return LoadResult.Invalid()
                }
            } else {
                projectionVersion = page.projectionVersion
            }

            onPageLoaded(page, cursor == null)
            LoadResult.Page(
                data = page.items.filter { loadedProjectionIds.add(it.projectionId) },
                prevKey = null,
                nextKey = page.nextCursor,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            if ((cause as? DomainFailure)?.code == "read_snapshot_changed") {
                onSnapshotInvalidated()
                return LoadResult.Invalid()
            }
            cause.recordDiagnosticFailure(
                diagnostics,
                DiagnosticFeature.CASH_LEDGER,
                if (cursor == null) DiagnosticAction.LOAD else DiagnosticAction.LOAD_NEXT_PAGE,
            )
            LoadResult.Error(cause)
        }
    }
}
