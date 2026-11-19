package com.finance.tracker.core

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto

internal const val CASH_LEDGER_WORKER_FAILED = "cash_ledger_worker_failed"

internal interface BackgroundCashLedger {
    suspend fun page(snapshot: String, filters: CashFiltersDto, timeZoneId: String?,
        cursor: String?, startIndex: Int?, expectedLedgerSeq: Long?, limit: Int): CashPageDto
    fun close()
}

internal expect fun createBackgroundCashLedger(workspace: String): BackgroundCashLedger?
internal expect val cashLedgerUsesBackgroundQueries: Boolean
