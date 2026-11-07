package com.finance.tracker.data

import com.finance.tracker.business.domain.CashProjectionDomainFailure
import com.finance.tracker.core.getLocalTimeZoneId
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Serves the cash page, cash accounts and catalogs from committed local facts whenever the active
 * restoration is ready, and otherwise keeps the existing server path. Only reads are rerouted;
 * evidence, maintenance, import and investment behavior stay on the delegate.
 */
internal class LocalFirstFinanceRepository(
    private val delegate: FinanceRepository,
    private val reader: suspend () -> LedgerLocalReader?,
) : FinanceRepository by delegate {
    override suspend fun fetchCashPage(
        filters: CashFilters,
        cursor: String?,
        startIndex: Int?,
        projectionVersion: Long?,
        limit: Int,
    ): CashPage = local { it.page(filters.toDto(), timeZoneId = getLocalTimeZoneId(), cursor, startIndex, projectionVersion, limit) }
        ?.toDomain()
        ?: delegate.fetchCashPage(filters, cursor, startIndex, projectionVersion, limit)

    override suspend fun fetchCashAccounts(): List<Account> =
        local { it.accounts() }?.map { account -> account.toDomain() } ?: delegate.fetchCashAccounts()

    override suspend fun fetchCashCategories(): CashCategoryDirectory =
        local { it.categories() }?.toDomain() ?: delegate.fetchCashCategories()

    override suspend fun fetchLedgerOptions(): LedgerOptions =
        local { it.options() }?.toDomain() ?: delegate.fetchLedgerOptions()

    private suspend fun <T> local(read: suspend (LedgerLocalReader) -> T?): T? {
        val active = reader() ?: return null
        return try {
            read(active)
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            null // The reader was closed by a newer operation; the delegate still serves this read.
        } catch (failure: CashProjectionDomainFailure) {
            // Keep the codes and status the server path exposes for the same conditions.
            val category = if (failure.code == "internal") FailureCategory.UNKNOWN else FailureCategory.RECOVERABLE
            val status = when (failure.code) {
                "invalid_filter" -> 400
                "read_snapshot_changed" -> 409
                else -> 0
            }
            throw DomainFailure(failure.code, status, category, cause = failure)
        } catch (failure: Throwable) {
            throw failure.toDomainFailure()
        }
    }
}
