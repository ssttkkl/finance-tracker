package com.finance.tracker.core

import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// 只保存原命令定位；财务字段、来源原文和表单草稿不写本机存储。
data class CashMaintenanceRecoveryPointer(val transactionId: String, val command: ImportFrozenCommand)
interface CashMaintenanceRecoveryStore {
    suspend fun load(): CashMaintenanceRecoveryPointer?
    suspend fun save(value: CashMaintenanceRecoveryPointer)
    suspend fun clear()
}
class ScopedCashMaintenanceRecoveryStore(scope: ImportRecoveryScope, private val storage: ImportRecoveryStorage) : CashMaintenanceRecoveryStore {
    private val key = importRecoveryNamespace(scope).replace(":cash-import:", ":cash-maintenance:")
    private val mutex = Mutex()
    override suspend fun load(): CashMaintenanceRecoveryPointer? = mutex.withLock { access {
        val text = storage.read(key) ?: return@access null
        if (text.length > 2048) invalid()
        val value = try { Json.decodeFromString<StoredMaintenancePointer>(text) } catch (_: Exception) { invalid() }
        value.pointer()
    } }
    override suspend fun save(value: CashMaintenanceRecoveryPointer) = mutex.withLock { access {
        val stored = StoredMaintenancePointer(1, value.transactionId, value.command.idempotencyKey, value.command.planId, value.command.revision)
        stored.pointer()
        storage.write(key, Json.encodeToString(stored))
    } }
    override suspend fun clear() = mutex.withLock { access { storage.remove(key) } }
    private suspend fun <T> access(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: DomainFailure) { throw error }
    catch (_: Exception) { throw DomainFailure("maintenance_recovery_unavailable", 0, FailureCategory.RECOVERABLE) }
    private fun invalid(): Nothing = throw DomainFailure("maintenance_recovery_invalid", 0, FailureCategory.RECOVERABLE)
    @Serializable private data class StoredMaintenancePointer(val version: Int, val transactionId: String, val key: String, val planId: String, val revision: Long) {
        fun pointer(): CashMaintenanceRecoveryPointer {
            require(version == 1 && transactionId.matches(Regex("[1-9][0-9]{0,18}")) && key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}")))
            require(planId.matches(Regex("mp_[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{16}")) && revision > 0)
            return CashMaintenanceRecoveryPointer(transactionId, ImportFrozenCommand(planId, revision, key))
        }
    }
}

class InMemoryCashMaintenanceRecoveryStore : CashMaintenanceRecoveryStore {
    private var pointer: CashMaintenanceRecoveryPointer? = null
    override suspend fun load() = pointer
    override suspend fun save(value: CashMaintenanceRecoveryPointer) { pointer = value }
    override suspend fun clear() { pointer = null }
}
