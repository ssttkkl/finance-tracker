package com.finance.tracker.data

/** One captured publication owns the HTTP identity, rejection cleanup and confirmed-fact refresh. */
internal interface CashBusinessOperations {
    suspend fun <T> run(action: suspend (FinanceApiClient, suspend (String?) -> Unit) -> T): T
}
