package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationResultDto

internal actual suspend fun assembleCashImportArchive(batchId: String, submission: LocalCashImportSubmission,
    serverItems: List<CashMutationResultDto>?): LocalCashImportResult? = LocalCashImportResult.from(batchId, submission, serverItems)

internal actual suspend fun inspectCashImportArchive(body: String): LocalCashImportResult = LocalCashImportResult.decode(body)

internal actual suspend fun readStoredCashImportSubmission(body: String): LocalCashImportSubmission = LocalCashImportSubmission.decode(body)

internal actual val archiveCashImportPagesInWorker = false

internal actual suspend fun assembleCashImportArchivePages(batchId: String, submission: LocalCashImportSubmission,
    pages: List<String>): LocalCashImportResult? =
    LocalCashImportResult.from(batchId, submission, pages.flatMap { apiJson.decodeFromString<com.finance.tracker.business.contracts.CashMutationResultPageDto>(it).items })

internal actual suspend fun readCashImportArchivePage(archive: LocalCashImportResult, kind: String, offset: Int, limit: Int):
    com.finance.tracker.business.contracts.CashImportArchivePage = archive.page(kind, offset, limit)
