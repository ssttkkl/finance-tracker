package com.finance.tracker.data

/** Erase-only ownership for a fixed scope; retain this instance until clear succeeds. */
internal interface LedgerCacheErasure {
    suspend fun clear()
}
