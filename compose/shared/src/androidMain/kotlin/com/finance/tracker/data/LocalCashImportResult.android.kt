package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashMutationResultDto

internal actual suspend fun assembleCashImportArchive(batchId: String, submission: LocalCashImportSubmission,
    serverItems: List<CashMutationResultDto>?): LocalCashImportResult? = LocalCashImportResult.from(batchId, submission, serverItems)

internal actual suspend fun inspectCashImportArchive(body: String): LocalCashImportResult = LocalCashImportResult.decode(body)

internal actual suspend fun readStoredCashImportSubmission(body: String): LocalCashImportSubmission = LocalCashImportSubmission.decode(body)
