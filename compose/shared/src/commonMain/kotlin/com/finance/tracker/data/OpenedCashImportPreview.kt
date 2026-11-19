package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.CashImportPlanError
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.core.LocalCashLedgerOwner
import kotlinx.serialization.json.*

internal class CashImportPreviewIdentity(allocateId: () -> String, val batchId: String, val revision: Long, val timezone: String) {
    val planId: String by lazy(allocateId)
}

internal interface OpenedCashImportPreview {
    val review: LocalCashImportReview
    val initialView: JsonObject
    suspend fun view(timezone: String): JsonObject
    suspend fun page(kind: String, cursor: String?, startIndex: Int?, limit: Int, status: String?, indexed: Boolean): JsonObject
    suspend fun detail(itemId: String): JsonObject
    suspend fun previousReview(input: JsonObject): LocalCashImportReview
    suspend fun confirmation(batchId: String): LocalCashImportSubmission
}

internal class CashImportPreviewSource(
    private val batches: LedgerImportBatches,
    private val ledger: LocalCashLedgerOwner,
    private val batchId: String,
    val workspaceId: String,
) {
    suspend fun files() = checkNotNull(batches.read(batchId)) { "cash_import_files_missing" }
    suspend fun snapshot() = checkNotNull(ledger.snapshot()) { "cash_import_snapshot_missing" }
    suspend fun encodedFiles() = checkNotNull(batches.readEncoded(batchId)) { "cash_import_files_missing" }
    suspend fun encodedSnapshot() = checkNotNull(ledger.snapshotText()) { "cash_import_snapshot_missing" }
}

internal expect suspend fun openCashImportPreview(
    source: CashImportPreviewSource, input: JsonObject, previous: LocalCashImportReview?,
    identity: CashImportPreviewIdentity, requireOpen: () -> Unit,
): OpenedCashImportPreview

internal suspend fun openLocalCashImportPreview(
    files: List<LocalCashImportFile>, snapshot: LedgerSnapshotDto, input: JsonObject, previous: LocalCashImportReview?,
    identity: CashImportPreviewIdentity, requireOpen: () -> Unit,
): OpenedCashImportPreview {
    val calculation = calculateCashImportPreviewLocally(files, snapshot, input, previous, requireOpen)
    val plan = com.finance.tracker.business.domain.identifyCashImportPreview(
        calculation.plan, identity.planId, identity.batchId, identity.revision,
    )
    val reader = localCashImportPlanReader(plan)
    reader.validateReadable()
    return object : OpenedCashImportPreview {
        override val review = calculation.review
        override val initialView = reader.view(identity.timezone)
        override suspend fun view(timezone: String) = reader.view(timezone)
        override suspend fun page(kind: String, cursor: String?, startIndex: Int?, limit: Int, status: String?, indexed: Boolean) =
            reader.page(kind, cursor, startIndex, limit, status, indexed)
        override suspend fun detail(itemId: String) = reader.detail(itemId)
        override suspend fun previousReview(input: JsonObject) = calculation.previousReviewFor(input)
        override suspend fun confirmation(batchId: String): LocalCashImportSubmission {
            if (!plan.getValue("can_commit").jsonPrimitive.boolean) throw CashImportPlanError("plan_changed")
            return LocalCashImportSubmission.freeze(batchId, calculation.request, LocalCashImportResultInput.freeze(calculation.plan))
        }
    }
}
