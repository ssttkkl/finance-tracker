package com.finance.tracker.domain

class SaveCashRecordUseCase(private val repository: CashLedgerRepository) {
    suspend operator fun invoke(id: String?, write: CashRecordWrite): CashRecordDetail {
        if (!isExactDecimalString(write.amount) || write.accountName.isBlank() || write.recordType.isBlank()) {
            throw DomainFailure("invalid_record", 400, FailureCategory.RECOVERABLE)
        }
        return if (id == null) repository.createCashRecord(write) else repository.updateCashRecord(id, write)
    }
}
