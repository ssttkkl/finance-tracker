package com.finance.tracker.data

/** Bound to its encrypted ledger scope and lifecycle; these are inputs, not submitted facts. */
internal interface LedgerImportFiles {
    suspend fun list(): List<String>
    suspend fun read(batchId: String): List<LocalCashImportFile>?
    suspend fun save(batchId: String, files: List<LocalCashImportFile>)
    suspend fun remove(batchId: String)
}
