package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.freezeLocalCashImportPreviewPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

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
    val (plan, changes) = calculateLocalCashImportBusinessPlan(
        files.map { it.parsedFile() }, snapshot, input, previous?.previousPlan().orEmpty(), previous?.recordIds().orEmpty(),
        { ensureActive(); requireOpen() }, Clock.System::now,
    )
    LocalCashImportCalculation(
        freezeLocalCashImportPreviewPlan(plan),
        LocalCashImportReview.freeze(input, plan, snapshot, changes),
        CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, changes),
        LocalCashImportReview.forPreview(plan),
    )
}
