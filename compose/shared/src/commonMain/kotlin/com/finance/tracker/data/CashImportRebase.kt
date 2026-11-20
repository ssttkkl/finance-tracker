package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashWriteSetRequestDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.PreparedLedgerSnapshot
import com.finance.tracker.business.domain.CASH_RULES_VERSION
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.calculateLocalCashImportBusinessPlan
import com.finance.tracker.business.domain.recalculate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock

internal sealed interface CashImportRebaseSnapshot {
    val base: String
    data class Decoded(val snapshot: LedgerSnapshotDto) : CashImportRebaseSnapshot {
        override val base get() = snapshot.ledgerSeq
    }
    data class Encoded(val snapshot: PreparedLedgerSnapshot) : CashImportRebaseSnapshot {
        override val base get() = snapshot.ledgerSeq
    }
}

internal data class CashImportRebaseInput(val snapshot: CashImportRebaseSnapshot, val review: String, val files: List<String>)

internal typealias CashImportRebase = suspend (CashImportRebaseInput, String, LocalCashImportResultInput?, () -> Unit) -> LocalCashImportSubmission

internal expect suspend fun captureCashImportRebaseSnapshot(ledger: LocalCashLedgerOwner): CashImportRebaseSnapshot?
internal expect suspend fun rebaseCashImport(source: CashImportRebaseInput, batchId: String,
    input: LocalCashImportResultInput?, requireOpen: () -> Unit): LocalCashImportSubmission

internal suspend fun rebaseCashImportLocally(source: CashImportRebaseInput, batchId: String,
    input: LocalCashImportResultInput?, requireOpen: () -> Unit, calculate: LocalCashImportCalculator? = null): LocalCashImportSubmission {
    val context = currentCoroutineContext()
    val snapshot = (source.snapshot as CashImportRebaseSnapshot.Decoded).snapshot
    val review = LocalCashImportReview.decode(source.review)
    val files = source.files.map { LocalCashImportFile.decode(it).parsedFile() }
    require(files.size <= 20 && files.map { it.fileRef }.toSet().size == files.size) { "cash_import_files_invalid" }
    val result = review.recalculate(snapshot) { facts, choices, previous, ids ->
        if (calculate != null) calculate(files, facts, choices, previous, ids)
        else calculateLocalCashImportBusinessPlan(files, facts, choices, previous, ids, {
            context.ensureActive()
            requireOpen()
        }, Clock.System::now)
    }
    if (!result.conclusionUnchanged) throw CashImportPlanError("plan_changed")
    return LocalCashImportSubmission.freeze(batchId, CashWriteSetRequestDto(snapshot.ledgerSeq, CASH_RULES_VERSION, result.changes), input)
}
