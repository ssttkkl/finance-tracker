package com.finance.tracker.data

import com.finance.tracker.business.domain.LocalCashImportReview
import kotlinx.serialization.json.JsonObject

internal actual suspend fun openCashImportPreview(
    source: CashImportPreviewSource, input: JsonObject, previous: LocalCashImportReview?,
    identity: CashImportPreviewIdentity, requireOpen: () -> Unit,
): OpenedCashImportPreview = openLocalCashImportPreview(source.files(), source.snapshot(), input, previous, identity, requireOpen)
