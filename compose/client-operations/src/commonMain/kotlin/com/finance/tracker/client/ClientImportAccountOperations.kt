package com.finance.tracker.client

import com.finance.tracker.business.contracts.AccountCommandViewDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportAccountChangeResult(
    @SerialName("operation_id") val operationId: String,
    @SerialName("account_id") val accountId: String?,
    val status: String,
    val code: String? = null,
)

sealed interface CashAccountCurrenciesResult {
    data class Unchanged(val account: AccountCommandViewDto) : CashAccountCurrenciesResult
    data class Updated(val write: ClientCashWriteResult) : CashAccountCurrenciesResult
}

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
    ): CashAccountCurrenciesResult {
        val batch = imports.editable(workspace, batchId, revision)
        val account = session.account(batch.ledger, accountId)
        if (account.accountRevision != accountRevision) throw ClientOperationFailure("account_changed", 409)
        if (!isSelectableCashAccount(account)) throw ClientOperationFailure("account_unavailable")
        val missing = missingCashAccountCurrencies(account, currencies)
        if (missing.isEmpty()) return CashAccountCurrenciesResult.Unchanged(account)
        validateCashAccountCurrencyChange(account)
        return CashAccountCurrenciesResult.Updated(writes.ensureImportAccountCurrencies(batch, account, missing))
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
        val edits = commands.filter { it.receipt?.account != null }
        val all = edits.associateBy { it.id }
        val chains = edits.groupBy { checkNotNull(it.receipt?.account).accountId }.mapValues { (_, accounts) ->
            val effects = mutableListOf<ImportAccountEffect>()
            var revision = 0L
            var blocked = false
            for (operation in accounts.sortedBy { checkNotNull(it.receipt?.account).accountRevision }) {
                val after = checkNotNull(operation.receipt?.account)
                if (effects.isEmpty()) blocked = false
                else if (after.accountRevision != revision + 1) blocked = true
                revision = after.accountRevision
                if (operation.reverses == null) {
                    effects += ImportAccountEffect(operation.id, revision, operation.beforeAccount, after)
                } else if (operation.completesReversal) {
                    effects.removeAll { it.key == operation.reverses }
                }
            }
            ImportAccountEffectChain(effects, revision, blocked)
        }.toMutableMap()
        val outcomes = mutableListOf<ImportAccountChangeResult>()
        compensateImportAccounts(object : ImportAccountCompensationHost {
            override fun chains() = chains
            override fun hasUnresolvedWrite(accountId: String) = false
            override suspend fun requireActive() = session.requireCurrent(batch.ledger.binding)
            override fun canSubmit() = !batch.ledger.offline
            override suspend fun read(accountId: String) = session.account(batch.ledger, accountId)

            override suspend fun apply(effect: ImportAccountEffect, action: AccountCompensationAction): Boolean {
                val id = action.current.accountId
                val chain = chains.getValue(id)
                val restored = action.kind != AccountCompensationKind.DEACTIVATE
                val revision = if (restored && action.patch?.isEmpty() == true) {
                    action.current.accountRevision
                } else {
                    val result = writes.reverseAccountChange(batch, all.getValue(effect.key), action)
                    checkNotNull(result.receipt.account).accountRevision
                }
                chains[id] = chain.copy(effects = if (restored) chain.effects.dropLast(1) else chain.effects, revision = revision)
                return true
            }

            override fun fail(effect: ImportAccountEffect, code: String, skipped: Boolean) {
                outcomes += ImportAccountChangeResult(effect.key, effect.after.accountId, if (skipped) "kept" else "pending", code)
            }

            override fun succeeded(effect: ImportAccountEffect, complete: Boolean) {
                if (complete) outcomes += ImportAccountChangeResult(effect.key, effect.after.accountId, "restored")
            }
        })
        return outcomes
    }
}
