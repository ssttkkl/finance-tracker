package com.finance.tracker.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportAccountChangeResult(
    @SerialName("operation_id") val operationId: String,
    @SerialName("account_id") val accountId: String?,
    val status: String,
    val code: String? = null,
)

class ClientImportAccountOperations(
    private val imports: ClientCashImportOperations,
    private val writes: ClientCashWriteOperations,
    private val session: ClientSessionOperations,
) {
    suspend fun create(
        workspace: String?, batchId: String, revision: Long, name: String, type: CashAccountType, currencies: List<String>,
    ): ClientCashWriteResult {
        val batch = imports.editable(workspace, batchId, revision)
        return writes.createAccount(batch.workspaceId, name, type, currencies, batch)
    }

    suspend fun ensureCurrencies(
        workspace: String?, batchId: String, revision: Long, accountId: String, accountRevision: Long, currencies: List<String>,
    ): ClientCashWriteResult {
        val batch = imports.editable(workspace, batchId, revision)
        val account = session.account(batch.ledger, accountId)
        if (account.accountRevision != accountRevision) throw ClientOperationFailure("account_changed", 409)
        return writes.ensureImportAccountCurrencies(batch, account, currencies)
    }

    suspend fun cancel(workspace: String?, batchId: String, revision: Long): List<ImportAccountChangeResult> {
        val batch = imports.editable(workspace, batchId, revision)
        val commands = writes.importCommands(batch)
        if (commands.any { it.receipt == null && it.rejection == null })
            throw ClientOperationFailure("account_result_unknown", commitState = "unknown")
        imports.cancel(batch)
        val reversed = commands.filter { it.receipt != null && it.completesReversal }.mapNotNull { it.reverses }.toSet()
        return commands.filter { it.reverses == null && it.receipt != null && it.id !in reversed }
            .map { ImportAccountChangeResult(it.id, it.receipt?.accountId, "kept") }
    }

    suspend fun undo(workspace: String?, batchId: String, revision: Long): List<ImportAccountChangeResult> {
        val batch = imports.editable(workspace, batchId, revision)
        val commands = writes.importCommands(batch)
        if (commands.any { it.receipt == null && it.rejection == null })
            throw ClientOperationFailure("account_result_unknown", commitState = "unknown")
        val receipts = commands.filter { it.receipt?.account != null }
        val latest = receipts.groupBy { checkNotNull(it.receipt?.account).accountId }
            .mapValues { (_, values) -> values.maxOf { checkNotNull(it.receipt?.account).accountRevision } }.toMutableMap()
        val reversed = receipts.filter { it.completesReversal }.mapNotNull { it.reverses }.toSet()
        val original = receipts.filter { it.reverses == null }
        val broken = original.groupBy { checkNotNull(it.receipt?.account).accountId }.filterValues { changes ->
            changes.sortedBy { checkNotNull(it.receipt?.account).accountRevision }.zipWithNext().any { (earlier, later) ->
                earlier.receipt?.account?.accountRevision != later.beforeAccount?.accountRevision
            }
        }.keys
        val candidates = original.filter { it.id !in reversed }
            .sortedByDescending { checkNotNull(it.receipt?.account).accountRevision }
        val outcomes = mutableListOf<ImportAccountChangeResult>()
        val blocked = mutableSetOf<String>()
        for (change in candidates) {
            val saved = checkNotNull(change.receipt?.account)
            val accountId = saved.accountId
            if (accountId in blocked) {
                outcomes += ImportAccountChangeResult(change.id, accountId, "kept", "account_changed")
                continue
            }
            try {
                if (accountId in broken) throw ClientOperationFailure("account_changed", 409)
                var current = session.account(batch.ledger, accountId)
                if (current.deleted || current.accountRevision != latest[accountId])
                    throw ClientOperationFailure("account_changed", 409)
                if (change.beforeAccount == null || change.beforeAccount.currencies != saved.currencies) {
                    val methodResult = writes.reverseAccountChange(batch, change, current)
                    val applied = checkNotNull(methodResult.receipt.account)
                    latest[accountId] = applied.accountRevision
                    if (change.beforeAccount == null && current.active && !applied.deleted) {
                        current = session.account(batch.ledger, accountId)
                        if (current.accountRevision != applied.accountRevision) throw ClientOperationFailure("account_changed", 409)
                        latest[accountId] = checkNotNull(writes.reverseAccountChange(batch, change, current).receipt.account).accountRevision
                    }
                }
                outcomes += ImportAccountChangeResult(change.id, accountId, "restored")
            } catch (failure: ClientOperationFailure) {
                outcomes += ImportAccountChangeResult(change.id, accountId, "pending", failure.code)
                blocked += accountId
            }
        }
        return outcomes
    }
}
