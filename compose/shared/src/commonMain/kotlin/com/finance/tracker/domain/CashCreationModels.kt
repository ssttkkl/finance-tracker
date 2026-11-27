package com.finance.tracker.domain

import com.finance.tracker.client.ClientPendingCommand
import kotlinx.serialization.Serializable

@Serializable
enum class CashCreationMode { ORDINARY, TRANSFER }
@Serializable
data class CashOrdinaryInput(val values: Map<String, String>)
@Serializable
data class CashTransferInput(val fromAccountId: String = "", val toAccountId: String = "",
    val fromCurrency: String = "CNY", val toCurrency: String = "CNY", val amount: String = "", val toAmount: String = "",
    val occurredAt: String = "", val note: String = "")
@Serializable
data class CashCreationDraft(val ordinary: CashOrdinaryInput, val transfer: CashTransferInput,
    val mode: CashCreationMode = CashCreationMode.ORDINARY)
@Serializable
data class CashCreationRecovery(val command: ClientPendingCommand, val draft: CashCreationDraft) {
    override fun toString(): String = "CashCreationRecovery(command=$command)"
}

interface CashCreationRepository {
    val supportsDurableRecovery: Boolean get() = false
    suspend fun pending(workspaceId: String, principalEmail: String): CashCreationRecovery? = null
    suspend fun saveRecovery(
        recovery: CashCreationRecovery, workspaceId: String, principalEmail: String, lookupFirst: Boolean,
    ): Unit = error("cash_creation_durable_storage_required")
    suspend fun acknowledgeRecovery(commandId: String, workspaceId: String, principalEmail: String) {}

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
