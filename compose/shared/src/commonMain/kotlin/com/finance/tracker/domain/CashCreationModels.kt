package com.finance.tracker.domain

enum class CashCreationMode { ORDINARY, TRANSFER }
data class CashOrdinaryInput(val values: Map<String, String>)
data class CashTransferInput(val fromAccountId: String = "", val toAccountId: String = "",
    val fromCurrency: String = "CNY", val toCurrency: String = "CNY", val amount: String = "", val toAmount: String = "",
    val occurredAt: String = "", val note: String = "")
data class CashCreationDraft(val ordinary: CashOrdinaryInput, val transfer: CashTransferInput,
    val mode: CashCreationMode = CashCreationMode.ORDINARY)
interface CashCreationRepository {
    val supportsIdempotentSave: Boolean get() = false
    suspend fun createRecord(input: StructuredObject)
    suspend fun createTransfer(input: StructuredObject)
    suspend fun save(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) {
        if (mode == CashCreationMode.ORDINARY) createRecord(input) else createTransfer(input)
    }
    suspend fun resume(mode: CashCreationMode, input: StructuredObject, key: String, accountIds: List<String>,
        workspaceId: String, principalEmail: String) = save(mode, input, key, accountIds, workspaceId, principalEmail)
}
