package com.finance.tracker.core

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashLedgerQuery
import com.finance.tracker.business.domain.normalizeLocalCashLedgerQueryInput

/** A committed capture, using the same chosen zone for filtering and monthly totals. */
internal class LocalCashLedgerReader(snapshot: LedgerSnapshotDto) {
    private val query = LocalCashLedgerQuery(snapshot, ::localCashLedgerMonth)

    fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto =
        query.page(normalizeLocalCashLedgerQueryInput(filters, timeZoneId, ::localCashLedgerDateBounds),
            cursor, startIndex, expectedLedgerSeq, limit)
}
