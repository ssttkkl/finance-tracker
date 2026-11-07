package com.finance.tracker.data

import com.finance.tracker.business.contracts.AccountReferenceDto
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.SessionDto

/** Read-only local browsing surface backed by one verified or offline-restored session. */
internal interface LedgerLocalReader {
    /** Null means the local cache is not ready; callers fall back to the server. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto?

    suspend fun categories(): CategoryDirectoryDto?
    suspend fun options(): LedgerOptionsDto?
    suspend fun accounts(): List<AccountReferenceDto>?
}

/** One restoration operation: authenticates, then serves local facts until it is closed. */
internal interface LedgerSessionLocal : LedgerLocalReader, LedgerSessionResource {
    /** True only when the identity came from the local recovery record after a recognized network failure. */
    val offline: Boolean
    /** The exact bearer token this operation was created for; used to scope rejection cleanup. */
    val token: String?
    suspend fun restore(): SessionDto
}
