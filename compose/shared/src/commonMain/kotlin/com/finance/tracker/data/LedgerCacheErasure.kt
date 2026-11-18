package com.finance.tracker.data

/** Explicit erasure ownership; failures retain the exact instance until its selected resources settle. */
internal interface LedgerCacheErasure {
    suspend fun clear()
    /** Never starts new deletion; finishes any already selected erasure or outstanding release. */
    suspend fun close()
}
