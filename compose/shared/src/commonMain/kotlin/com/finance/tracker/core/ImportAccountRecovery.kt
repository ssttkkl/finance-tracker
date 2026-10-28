package com.finance.tracker.core

import com.finance.tracker.domain.DomainFailure
import com.finance.tracker.domain.FailureCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface ImportAccountRecovery {
    suspend fun keys(): List<String>
    suspend fun add(key: String)
    suspend fun complete(key: String)
    suspend fun verify(key: String): Boolean
}

class ScopedImportAccountRecovery(scope: ImportRecoveryScope, private val storage: ImportRecoveryStorage) : ImportAccountRecovery {
    private val prefix = importRecoveryNamespace(scope) + ":account-command:"
    private val mutex = Mutex()

    override suspend fun keys(): List<String> = mutex.withLock { readKeys() }

    override suspend fun add(key: String) = mutex.withLock {
        validateKey(key)
        val keys = readKeys()
        if (key !in keys && keys.size >= 64 && access { storage.read(prefix + key) } != "1") failure("account_recovery_limit")
        access { storage.write(prefix + key, "1") }
    }

    override suspend fun complete(key: String) = mutex.withLock {
        validateKey(key)
        access { storage.remove(prefix + key) }
    }

    override suspend fun verify(key: String): Boolean = mutex.withLock {
        validateKey(key)
        when (access { storage.read(prefix + key) }) {
            null -> false
            "1" -> true
            else -> failure("account_recovery_invalid")
        }
    }

    private suspend fun readKeys(): List<String> {
        val slots = access { storage.keys(prefix, 64) }
        if (slots.size > 64 || slots.distinct().size != slots.size) failure("account_recovery_invalid")
        return slots.map { slot ->
            if (!slot.startsWith(prefix)) failure("account_recovery_invalid")
            val key = slot.removePrefix(prefix)
            validateKey(key)
            if (access { storage.read(slot) } != "1") failure("account_recovery_invalid")
            key
        }.sorted()
    }

    private fun validateKey(key: String) {
        if (!key.matches(Regex("[A-Za-z0-9_:.~-]{1,255}"))) failure("account_recovery_invalid")
    }
    private suspend fun <T> access(action: suspend () -> T): T = try { action() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { failure("account_recovery_unavailable") }
    private fun failure(code: String): Nothing = throw DomainFailure(code, 0, FailureCategory.RECOVERABLE)
}
