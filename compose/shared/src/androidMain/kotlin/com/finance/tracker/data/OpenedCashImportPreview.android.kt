package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.JsonObject

internal actual suspend fun openCashImportPreview(
    files: List<LocalCashImportFile>, snapshot: LedgerSnapshotDto, input: JsonObject, previous: LocalCashImportReview?,
    identity: CashImportPreviewIdentity, requireOpen: () -> Unit,
): OpenedCashImportPreview = openLocalCashImportPreview(files, snapshot, input, previous, identity, requireOpen)
