package com.finance.tracker.domain

enum class CashCreationMode { ORDINARY, TRANSFER }
data class CashOrdinaryInput(val values: Map<String, String>)
data class CashTransferInput(val fromAccountId: String = "", val toAccountId: String = "",
    val fromCurrency: String = "CNY", val toCurrency: String = "CNY", val amount: String = "", val toAmount: String = "",
    val occurredAt: String = "", val note: String = "")
data class CashCreationDraft(val ordinary: CashOrdinaryInput, val transfer: CashTransferInput,
    val mode: CashCreationMode = CashCreationMode.ORDINARY)
interface CashCreationRepository {
    suspend fun createRecord(input: StructuredObject)
    suspend fun createTransfer(input: StructuredObject)
}
