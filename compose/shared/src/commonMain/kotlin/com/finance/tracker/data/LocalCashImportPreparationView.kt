package com.finance.tracker.data

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashImportReview
import com.finance.tracker.business.domain.buildLocalCashImportContextFiles
import com.finance.tracker.business.domain.prepareLocalCashImportView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun localCashImportPreparationView(batchId: String, files: List<LocalCashImportFile>,
    source: LedgerSnapshotDto, review: LocalCashImportReview?): CashImportSessionDto =
    prepareLocalCashImportView(batchId, files.map(LocalCashImportFile::parsedFile), source, review)

internal suspend fun prepareCashImportLocally(batchId: String, source: CashImportPreviewSource, review: String?): CashImportSessionDto {
    val files = source.files()
    val snapshot = source.snapshot()
    return withContext(Dispatchers.Default) {
        localCashImportPreparationView(batchId, files, snapshot, review?.let(LocalCashImportReview::decode))
    }
}

internal expect suspend fun prepareCashImportView(batchId: String, source: CashImportPreviewSource, review: String?): CashImportSessionDto

internal fun localCashImportContextFiles(files: List<LocalCashImportFile>): List<CashImportContextFileDto> =
    buildLocalCashImportContextFiles(files.map(LocalCashImportFile::parsedFile))
