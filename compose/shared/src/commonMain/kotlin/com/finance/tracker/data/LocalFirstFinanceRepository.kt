package com.finance.tracker.data

import com.finance.tracker.core.getLocalTimeZoneId
import com.finance.tracker.domain.*

/** Cash browsing always reads one committed local scope; writes stay on the server. */
internal class LocalFirstFinanceRepository(
    private val delegate: FinanceRepository,
    private val reader: suspend () -> LedgerLocalReader?,
    private val refreshBrowsing: suspend () -> Unit = {},
) : FinanceRepository by delegate {
    override suspend fun refreshCashLedger() = refreshBrowsing()
    override suspend fun ledgerVersion(): Long? = local { it.page(limit = 1)?.projectionVersion }
    override suspend fun fetchCashPage(
        filters: CashFilters,
        cursor: String?,
        startIndex: Int?,
        projectionVersion: Long?,
        limit: Int,
    ): CashPage = local { it.page(filters.toDto(), timeZoneId = getLocalTimeZoneId(), cursor, startIndex, projectionVersion, limit) }
        .toDomain()

    override suspend fun fetchCashAccounts(): List<Account> =
        local { it.accounts() }.map { account -> account.toDomain() }

    override suspend fun fetchCashCategories(): CashCategoryDirectory =
        local { it.categories() }.toDomain()

    override suspend fun fetchLedgerOptions(): LedgerOptions =
        local { it.options() }.toDomain()

    private suspend fun <T> local(read: suspend (LedgerLocalReader) -> T?): T = readLocalCash(reader, read)
}
