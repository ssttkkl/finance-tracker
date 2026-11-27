package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.client.calculateCashImportReview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

internal expect suspend fun executeCashImportPreview(
    files: List<LocalCashImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    requireOpen: () -> Unit,
): LocalCashImportCalculation

internal suspend fun calculateCashImportPreviewLocally(
    files: List<LocalCashImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    requireOpen: () -> Unit,
): LocalCashImportCalculation = withContext(Dispatchers.Default) {
    calculateCashImportReview(files.map { it.parsedFile() }, snapshot, input, previous) {
        ensureActive()
        requireOpen()
    }
}
