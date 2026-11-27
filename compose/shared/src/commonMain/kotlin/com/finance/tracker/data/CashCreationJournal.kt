package com.finance.tracker.data

import com.finance.tracker.domain.CashCreationRecovery

internal interface CashCreationJournal {
    suspend fun read(): CashCreationRecovery?
    suspend fun swap(previous: CashCreationRecovery?, next: CashCreationRecovery?): Boolean
}
