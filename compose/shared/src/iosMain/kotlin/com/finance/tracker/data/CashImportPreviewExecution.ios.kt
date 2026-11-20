package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.JsonObject

internal actual suspend fun executeCashImportPreview(
    files: List<LocalCashImportFile>,
    snapshot: LedgerSnapshotDto,
    input: JsonObject,
    previous: LocalCashImportReview?,
    requireOpen: () -> Unit,
): LocalCashImportCalculation = calculateCashImportPreviewLocally(files, snapshot, input, previous, requireOpen)
