package com.finance.tracker.presentation

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.finance.tracker.domain.CashImportLifecycleRepository
import com.finance.tracker.domain.ImportProjectedItem
import kotlinx.coroutines.CancellationException
import kotlin.math.max

internal class CashImportItemsPagingSource(
    private val repository: CashImportLifecycleRepository,
    private val planId: String,
    private val confirmationStatus: String,
    private val onPageLoaded: () -> Unit = {},
) : PagingSource<Int, ImportProjectedItem>() {
    override val jumpingSupported: Boolean = true

    override fun getRefreshKey(state: PagingState<Int, ImportProjectedItem>): Int? =
        state.anchorPosition?.let { max(0, it - state.config.initialLoadSize / 2) }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ImportProjectedItem> {
        val startIndex = params.key?.coerceAtLeast(0) ?: 0
        val limit = params.loadSize.coerceIn(1, MAX_PAGE_SIZE)
        return try {
            val page = repository.items(planId, limit = limit, confirmationStatus = confirmationStatus, startIndex = startIndex)
            val totalCount = page.totalCount
            if (page.startIndex != startIndex || totalCount == null || totalCount < 0 || startIndex + page.items.size > totalCount) {
                return LoadResult.Error(IllegalStateException("Import plan page omitted its absolute range"))
            }
            onPageLoaded()
            val endIndex = startIndex + page.items.size
            LoadResult.Page(
                data = page.items,
                prevKey = startIndex.takeIf { it > 0 }?.let { (it - limit).coerceAtLeast(0) },
                nextKey = endIndex.takeIf { it < totalCount },
                itemsBefore = startIndex,
                itemsAfter = (totalCount - endIndex).coerceAtLeast(0),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            LoadResult.Error(cause)
        }
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
    }
}
