package com.finance.tracker.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finance.tracker.core.ImportAccountRecovery
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ImportAccountUiState(
    val ready: Boolean = false, val busy: Boolean = false,
    val accounts: List<ImportAccountListItem> = emptyList(),
    val currentAccounts: Map<String, ImportAccount> = emptyMap(),
    val pendingKeys: List<String> = emptyList(), val changedAccountIds: Set<String> = emptySet(),
    val undoFailures: Map<String, String> = emptyMap(), val errorCode: String? = null,
    val internalFailure: Throwable? = null,
) {
    val needsExitDecision: Boolean get() = changedAccountIds.isNotEmpty() || pendingKeys.isNotEmpty()
}

class ImportAccountViewModel(
    private val repository: CashImportLifecycleRepository,
    private val recovery: ImportAccountRecovery,
    private val newKey: () -> String = { Random.nextBytes(24).joinToString("") { it.toUByte().toString(16).padStart(2, '0') } },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ImportAccountUiState())
    val state: StateFlow<ImportAccountUiState> = mutableState.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val pending = linkedMapOf<String, Pending>()
    private val chains = linkedMapOf<String, Chain>()
    private var nextStep = 0L
    private data class Step(val order: Long, val before: ImportAccount?, val after: ImportAccount)
    private class Chain(val steps: MutableList<Step>, var revision: Long, var blocked: Boolean = false)
    private enum class Undo { NONE, RESTORE, DEACTIVATE, DELETE }
    private data class Command(val key: String, val kind: String, val accountId: String?, val revision: Long?,
        val name: String? = null, val type: String? = null, val currencies: List<String>? = null,
        val patch: StructuredObject? = null, val before: ImportAccount? = null, val undo: Undo = Undo.NONE, val order: Long = 0)
    private class Pending(val command: Command?, var result: ImportAccount? = null, var proofApplied: Boolean = false,
        var refused: Boolean = false, var uncertain: Boolean = false)

    fun detach() {
        generation++; job?.cancel(); job = null
        pending.clear(); chains.clear(); mutableState.value = ImportAccountUiState()
    }

    fun restore() {
        if (state.value.ready || state.value.busy) return
        execute { token ->
            val keys = recovery.keys()
            if (!current(token)) return@execute
            absorbKeys(keys)
            publish()
            // 查询失败只影响该条账户操作，不阻止加载其他账户或主导入。
            for (key in keys) {
                try { resolveOne(token, key) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: DomainFailure) { if (cause.category == FailureCategory.UNKNOWN || cause.code == "internal_error") throw cause; if (current(token)) mutableState.value = state.value.copy(errorCode = safeCode(cause)) }
                if (!current(token)) return@execute
            }
            absorbRemaining(token)
            loadAccounts(token)
            if (current(token)) mutableState.value = state.value.copy(ready = true)
        }
    }

    fun resolvePending() = execute { token ->
        val keys = recovery.keys()
        if (!current(token)) return@execute
        absorbKeys(keys)
        publish()
        for (key in pending.keys.toList()) {
            try { resolveOne(token, key) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: DomainFailure) { if (cause.category == FailureCategory.UNKNOWN || cause.code == "internal_error") throw cause; if (current(token)) mutableState.value = state.value.copy(errorCode = safeCode(cause)) }
            if (!current(token)) return@execute
        }
        absorbRemaining(token)
        loadAccounts(token)
    }

    fun refreshAccounts() = execute { loadAccounts(it) }
    fun loadAccount(accountId: String, onLoaded: (ImportAccount) -> Unit = {},
        onReadFailure: ((DomainFailure) -> Unit)? = null) = execute { token ->
        val account = try { repository.account(accountId) }
        catch (failure: DomainFailure) {
            val temporaryReadFailure = failure.code == "network_unavailable" && failure.status == 0 ||
                failure.code == "account_unavailable" && failure.status == 503
            if (onReadFailure == null || !temporaryReadFailure) throw failure
            if (current(token)) onReadFailure(failure)
            return@execute
        }
        if (!current(token)) return@execute
        adoptCurrent(token, account, accountId)
        if (account.deleted || !account.active || account.type !in setOf("cash", "loan", "lend")) {
            mutableState.value = state.value.copy(errorCode = "account_unavailable")
        } else onLoaded(account)
    }

    fun create(name: String, type: String, currencies: List<String>) {
        if (!available() || pending.values.any { it.command?.accountId == null }) return
        begin(Command(newKey(), "account_create", null, null, name = name, type = type, currencies = currencies.toList()))
    }

    fun update(before: ImportAccount, patch: StructuredObject) {
        if (!available() || before.deleted || before.capabilities["can_update"] != true ||
            pending.values.any { it.command == null || it.command.accountId == before.accountId }) return
        val allowed = setOf("name", "active", "currencies")
        if (patch.fields.keys.any { it !in allowed }) {
            mutableState.value = state.value.copy(errorCode = "account_payload_invalid"); return
        }
        begin(Command(newKey(), "account_update", before.accountId, before.revision, patch = freeze(patch) as StructuredObject, before = before.copy(currencies = before.currencies.toList(), capabilities = before.capabilities.toMap())))
    }

    fun ensureCurrencies(requiredByAccount: Map<String, List<String>>, onComplete: (Map<String, ImportAccount>) -> Unit) {
        if (!available()) return
        execute { token ->
            val ready = linkedMapOf<String, ImportAccount>()
            val prepared = mutableListOf<Pair<Command, List<String>>>()
            for ((accountId, required) in requiredByAccount.entries.sortedBy { it.key }.map { it.key to it.value }) {
                if (!current(token)) return@execute
                if (pending.values.any { it.command == null || it.command.accountId == accountId }) {
                    mutableState.value = state.value.copy(errorCode = "account_result_unknown")
                    return@execute
                }
                // 选择账户时已经读过同一账户；只有快照缺失时才补读。
                val snapshot = state.value.currentAccounts[accountId] ?: run {
                    val read = repository.account(accountId)
                    if (!current(token)) return@execute
                    adoptCurrent(token, read, accountId)
                    read
                }
                val missing = required.distinct().filterNot { it in snapshot.currencies }
                if (missing.isEmpty()) { ready[accountId] = snapshot; continue }
                if (snapshot.deleted || snapshot.capabilities["can_update"] != true) {
                    mutableState.value = state.value.copy(errorCode = "workspace_forbidden")
                    return@execute
                }
                val command = Command(
                    newKey(), "account_update", accountId, snapshot.revision,
                    patch = StructuredObject(mapOf("currencies" to StructuredArray(
                        (snapshot.currencies + missing).distinct().map(::StructuredText),
                    ))),
                    before = snapshot.copy(currencies = snapshot.currencies.toList(), capabilities = snapshot.capabilities.toMap()),
                    order = ++nextStep,
                )
                prepared += command to missing
            }
            if (prepared.isEmpty()) {
                if (current(token) && state.value.errorCode == null) onComplete(ready)
                return@execute
            }
            // 先完成全部预检，再统一持久化恢复键；避免“键已保存但请求从未发出”。
            if (prepared.size > 100) {
                mutableState.value = state.value.copy(errorCode = "account_payload_invalid")
                return@execute
            }
            val existingKeys = recovery.keys()
            if (!current(token)) return@execute
            if (existingKeys.size + prepared.size > 64) {
                mutableState.value = state.value.copy(errorCode = "account_recovery_limit")
                return@execute
            }
            val persisted = mutableListOf<Command>()
            try {
                for ((command, _) in prepared) {
                    recovery.add(command.key)
                    persisted += command
                }
            } catch (failure: DomainFailure) {
                for (command in persisted) {
                    try { recovery.complete(command.key) } catch (_: DomainFailure) { }
                }
                throw failure
            }
            if (!current(token)) {
                for ((command, _) in prepared) {
                    try { recovery.complete(command.key) } catch (_: DomainFailure) { }
                }
                return@execute
            }
            for ((command, _) in prepared) pending[command.key] = Pending(command)
            publish()
            val results = try {
                repository.ensureAccountCurrencies(prepared.map { (command, missing) ->
                    ImportCurrencyRequirement(command.key, command.accountId!!, command.revision!!, missing)
                })
            } catch (failure: DomainFailure) {
                // 整请求被明确拒绝（或服务端保证未执行）时，批量中的每条命令都未执行；其余结果保留恢复键。
                val definite = failure.commitState == "not_committed" ||
                    (failure.category != FailureCategory.UNKNOWN && failure.status in 400..499 && failure.code in batchRefusals)
                if (definite) {
                    for ((command, _) in prepared) {
                        if (!current(token)) return@execute
                        pending[command.key]?.refused = true
                        finish(token, command.key)
                    }
                }
                throw failure
            }
            if (!current(token)) return@execute
            if (results.size != prepared.size) invalidAccount()
            val byAccount = results.associateBy { it.accountId }
            var failureCode: String? = null
            var unknownFailure: DomainFailure? = null
            for ((command, _) in prepared) {
                val key = command.key
                val operation = pending[key] ?: invalidAccount()
                val failure = (byAccount[command.accountId] ?: invalidAccount()).failure
                if (failure != null) {
                    if (failure.category != FailureCategory.UNKNOWN && failure.code != "internal_error" &&
                        failure.status in 400..499 && failure.code in definiteRefusals) {
                        operation.refused = true
                        if (command.undo != Undo.NONE) failUndo(command.accountId!!, failure.code)
                        if (failure.code == "account_changed") chains[command.accountId]?.blocked = true
                        finish(token, key)
                    }
                    if (failureCode == null) failureCode = failure.code
                    // 冲突账户的缓存修订已失效：移除快照，下一次操作重新读取。
                    if (failure.code == "account_changed" || failure.code == "account_not_found") {
                        mutableState.value = state.value.copy(
                            currentAccounts = state.value.currentAccounts - command.accountId!!)
                    }
                    // 未知结果先保留恢复键，但同批已成功的项仍需采纳；处理完再抛给最外层。
                    if ((failure.category == FailureCategory.UNKNOWN || failure.code == "internal_error") && unknownFailure == null) {
                        unknownFailure = failure
                    }
                    continue
                }
                // 收据视图不含能力位；沿用选择时快照的最近一次读取结果。
                val account = ((byAccount[command.accountId] ?: invalidAccount()).account ?: invalidAccount())
                    .let { it.copy(capabilities = command.before?.capabilities ?: it.capabilities) }
                validateResult(account, command)
                operation.result = account
                // 批量结果是提交事务写入的收据视图，不再逐项回读。
                finish(token, key, verify = false)
                ready[command.accountId!!] = state.value.currentAccounts[command.accountId] ?: account
            }
            if (!current(token)) return@execute
            unknownFailure?.let { throw it }
            if (failureCode != null) mutableState.value = state.value.copy(errorCode = failureCode)
            else if (state.value.errorCode == null) onComplete(ready)
        }
    }

    private fun begin(original: Command) = execute { token ->
        val command = original.copy(order = ++nextStep)
        recovery.add(command.key)
        if (!current(token)) return@execute
        pending[command.key] = Pending(command); publish()
        send(token, command.key)
    }

    fun retryOriginal(key: String) {
        if (pending[key]?.command == null || !available()) return
        execute { send(it, key) }
    }

    fun resolve(key: String) {
        if (key !in pending || state.value.busy) return
        execute { resolveOne(it, key) }
    }

    private suspend fun send(token: Long, key: String): Boolean {
        val operation = pending[key] ?: return false
        if (operation.result != null || operation.refused) return finish(token, key)
        val command = operation.command ?: return false
        // 每次原请求重试仍先确认恢复键在本机可保存。
        recovery.add(key)
        if (!current(token)) return false
        val previouslyUncertain = operation.uncertain
        operation.uncertain = true
        val result = try {
            when (command.kind) {
                "account_create" -> repository.createAccount(command.name!!, command.type!!, command.currencies!!, key)
                "account_update" -> repository.updateAccount(command.accountId!!, command.revision!!, command.patch!!, key)
                else -> repository.deleteAccount(command.accountId!!, command.revision!!, key)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) {
            if (!current(token)) return false
            if (cause !is DomainFailure || cause.category == FailureCategory.UNKNOWN || cause.code == "internal_error") throw cause
            val failure = cause as? DomainFailure
            if (!previouslyUncertain && failure != null && failure.status in 400..499 && failure.code in definiteRefusals) {
                operation.refused = true
                if (command.undo != Undo.NONE) failUndo(command.accountId!!, failure.code)
                if (failure.code == "account_changed") chains[command.accountId]?.blocked = true
                finish(token, key)
            }
            if (current(token)) mutableState.value = state.value.copy(errorCode = safeCode(cause))
            return false
        }
        if (!current(token)) return false
        validateResult(result, command)
        operation.result = result
        return finish(token, key)
    }

    private suspend fun resolveOne(token: Long, key: String): Boolean {
        val operation = pending[key] ?: return false
        if (operation.result == null && !operation.refused) {
            val receipt = repository.receiptByKey(key)
            if (!current(token)) return false
            val account = receipt.account
            if (!receipt.committed || receipt.operationKind !in accountOperations || account == null ||
                receipt.accountId != account.accountId || receipt.accountRevision != account.revision || receipt.receiptId != account.receiptId && account.receiptId != null)
                invalidAccount()
            validateResult(account.copy(committed = true, receiptId = receipt.receiptId), operation.command, receipt.operationKind)
            operation.result = account.copy(committed = true, receiptId = receipt.receiptId)
        }
        return finish(token, key)
    }

    private suspend fun finish(token: Long, key: String, verify: Boolean = true): Boolean {
        val operation = pending[key] ?: return false
        val result = operation.result
        if (result != null) {
            if (!operation.proofApplied) {
                operation.command?.let { applyProof(it, result) }
                operation.proofApplied = true
                publish()
            }
            if (result.deleted) {
                mutableState.value = state.value.copy(currentAccounts = state.value.currentAccounts - result.accountId,
                    accounts = state.value.accounts.filterNot { it.id == result.accountId })
            } else if (!verify) {
                adoptCurrent(token, result, result.accountId)
            } else {
                try { adoptCurrent(token, repository.account(result.accountId), result.accountId) }
                catch (failure: DomainFailure) {
                    if (failure.code != "account_not_found" || failure.status != 404) throw failure
                    if (!current(token)) return false
                    mutableState.value = state.value.copy(currentAccounts = state.value.currentAccounts - result.accountId,
                        accounts = state.value.accounts.filterNot { it.id == result.accountId })
                }
            }
            if (!current(token)) return false
        }
        recovery.complete(key)
        if (!current(token)) return false
        pending.remove(key); publish()
        return !operation.refused
    }

    private fun applyProof(command: Command, result: ImportAccount) {
        val id = result.accountId
        val chain = chains[id]
        if (command.undo == Undo.NONE) {
            if (chain == null) chains[id] = Chain(mutableListOf(Step(command.order, command.before, result)), result.revision)
            else {
                if (command.revision != chain.revision) chain.blocked = true
                chain.steps += Step(command.order, command.before, result); chain.revision = result.revision
            }
        } else if (chain != null) {
            chain.revision = result.revision
            if (command.undo != Undo.DEACTIVATE) chain.steps.removeAt(chain.steps.lastIndex)
            if (chain.steps.isEmpty()) chains.remove(id)
        }
    }

    fun undo() {
        if (!available()) return
        execute { token ->
            val paused = mutableSetOf<String>()
            while (true) {
                if (!current(token)) return@execute
                val id = chains.entries.filter { it.key !in paused && it.value.steps.isNotEmpty() }
                    .maxByOrNull { it.value.steps.last().order }?.key ?: break
                val chain = chains[id] ?: continue
                if (pending.values.any { it.command == null || it.command.accountId == id }) {
                    failUndo(id, "account_result_unknown"); paused += id; continue
                }
                try {
                    if (chain.blocked) { failUndo(id, "account_changed"); paused += id; continue }
                    val now = repository.account(id)
                    if (!current(token)) return@execute
                    adoptCurrent(token, now, id)
                    if (now.revision != chain.revision || now.deleted) {
                        chain.blocked = true; failUndo(id, "account_changed"); paused += id; continue
                    }
                    val step = chain.steps.last()
                    val command = if (step.before == null) {
                        if (now.active) Command(newKey(), "account_update", id, now.revision,
                            patch = StructuredObject(mapOf("active" to StructuredBoolean(false))), undo = Undo.DEACTIVATE)
                        else if (now.capabilities["can_delete"] == true) Command(newKey(), "account_delete", id, now.revision, undo = Undo.DELETE)
                        else { failUndo(id, "account_in_use"); paused += id; continue }
                    } else {
                        if (now.capabilities["can_update"] != true) { failUndo(id, "workspace_forbidden"); paused += id; continue }
                        Command(newKey(), "account_update", id, now.revision, patch = inversePatch(step), undo = Undo.RESTORE)
                    }
                    recovery.add(command.key)
                    if (!current(token)) return@execute
                    pending[command.key] = Pending(command); publish()
                    if (!send(token, command.key)) paused += id
                    else if (current(token)) mutableState.value = state.value.copy(undoFailures = state.value.undoFailures - id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: DomainFailure) { if (cause.category == FailureCategory.UNKNOWN || cause.code == "internal_error") throw cause; if (current(token)) { failUndo(id, safeCode(cause)); paused += id } }
            }
            publish()
        }
    }

    private fun freeze(value: StructuredValue): StructuredValue = when (value) {
        is StructuredObject -> StructuredObject(value.fields.mapValues { freeze(it.value) })
        is StructuredArray -> StructuredArray(value.values.map(::freeze))
        else -> value
    }

    private fun inversePatch(step: Step): StructuredObject {
        val before = step.before ?: invalidAccount()
        val fields = mutableMapOf<String, StructuredValue>()
        if (before.name != step.after.name) fields["name"] = StructuredText(before.name)
        if (before.active != step.after.active) fields["active"] = StructuredBoolean(before.active)
        if (before.currencies != step.after.currencies) fields["currencies"] = StructuredArray(before.currencies.map(::StructuredText))
        return StructuredObject(fields)
    }

    private fun absorbKeys(keys: List<String>) {
        for (key in keys) if (pending.size < 64 && key !in pending) pending[key] = Pending(null)
    }
    private suspend fun absorbRemaining(token: Long) {
        val keys = recovery.keys()
        if (current(token)) { absorbKeys(keys); publish() }
    }

    private suspend fun loadAccounts(token: Long) {
        val accounts = repository.accounts()
        if (accounts.any { it.id.toLongOrNull()?.let { id -> id > 0 } != true }) invalidAccount()
        if (current(token)) mutableState.value = state.value.copy(accounts = accounts)
    }
    private fun adoptCurrent(token: Long, account: ImportAccount, expectedId: String) {
        if (!current(token)) return
        if (account.accountId != expectedId || account.accountId.toLongOrNull()?.let { it > 0 } != true || account.revision <= 0) invalidAccount()
        mutableState.value = if (account.deleted) state.value.copy(currentAccounts = state.value.currentAccounts - expectedId,
            accounts = state.value.accounts.filterNot { it.id == expectedId })
        else state.value.copy(currentAccounts = state.value.currentAccounts + (expectedId to account),
            accounts = state.value.accounts.filterNot { it.id == expectedId } + ImportAccountListItem(account.accountId, account.name, account.type, account.active, account.currencies))
    }
    private fun validateResult(account: ImportAccount, command: Command?, operation: String? = command?.kind) {
        if (account.accountId.toLongOrNull()?.let { it > 0 } != true || account.revision <= 0 || account.committed != true || account.receiptId.isNullOrBlank() ||
            (operation == "account_delete") != account.deleted || command != null && (operation != command.kind ||
                command.accountId != null && command.accountId != account.accountId || command.revision != null && account.revision != command.revision + 1)) invalidAccount()
    }
    private fun publish() { mutableState.value = state.value.copy(pendingKeys = pending.keys.toList(), changedAccountIds = chains.keys.toSet()) }
    private fun failUndo(id: String, code: String) { mutableState.value = state.value.copy(undoFailures = state.value.undoFailures + (id to code)) }
    private fun available() = state.value.ready && !state.value.busy && state.value.internalFailure == null
    private fun current(token: Long) = generation == token
    private fun execute(action: suspend (Long) -> Unit) {
        if (state.value.busy) return
        val token = generation
        mutableState.value = state.value.copy(busy = true, errorCode = null)
        job = viewModelScope.launch {
            try { action(token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
                if (current(token)) {
                    val internal = cause !is DomainFailure || cause.category == FailureCategory.UNKNOWN || cause.code == "internal_error"
                    mutableState.value = state.value.copy(errorCode = safeCode(cause), internalFailure = cause.takeIf { internal })
                    if (internal) com.finance.tracker.core.createDiagnosticLogger().record(com.finance.tracker.core.DiagnosticFeature.CASH_IMPORT,
                        com.finance.tracker.core.DiagnosticAction.MAP_ACCOUNTS, "unknown_error", null, cause::class.simpleName)
                }
            }
            finally { if (current(token)) { publish(); mutableState.value = state.value.copy(busy = false) } }
        }
    }
    private fun safeCode(cause: Exception) = (cause as? DomainFailure)?.code ?: "unknown_error"
    private fun invalidAccount(): Nothing = throw IllegalStateException("account_result_invalid")
    private companion object {
        val accountOperations = setOf("account_create", "account_update", "account_delete")
        val definiteRefusals = setOf("account_payload_invalid", "account_name_unavailable", "account_changed", "account_in_use", "account_active", "workspace_forbidden", "account_not_found")
        val batchRefusals = setOf("account_payload_invalid", "workspace_forbidden", "idempotency_key_required")
    }
}
