package com.finance.tracker.data

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.domain.LedgerCacheScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only opaque scope references belong here; verified identity remains in encrypted LedgerStorage. */
internal interface LedgerSessionLocatorStorage {
    suspend fun read(key: String): String?
    /** Compare and replace one entire origin record atomically across all supported writers. */
    suspend fun compareAndSet(key: String, expected: String?, replacement: String): Boolean
}

internal expect fun createPlatformLedgerSessionLocatorStorage(): LedgerSessionLocatorStorage

internal class LedgerSessionTicket internal constructor(val apiOrigin: String, internal val generation: Long)

/** Discovery only: a reference never proves that a committed ledger or matching identity exists. */
internal class LedgerSessionDirectory(private val storage: LedgerSessionLocatorStorage) {
    suspend fun begin(apiOrigin: String): LedgerSessionTicket {
        val next = change(apiOrigin) { state ->
            check(state.generation < Long.MAX_VALUE) { "ledger_session_generation_exhausted" }
            state.copy(generation = state.generation + 1)
        }
        return LedgerSessionTicket(apiOrigin, next.generation)
    }

    /** The caller holds this scope's real storage lease, before creating any facts or keys. */
    suspend fun register(ticket: LedgerSessionTicket, scope: LedgerCacheScope) {
        require(scope.apiOrigin == ticket.apiOrigin) { "ledger_session_scope_invalid" }
        validateScope(scope)
        change(ticket.apiOrigin) { state ->
            state.requireTicket(ticket)
            if (state.scopes.any { it.toScope(ticket.apiOrigin) == scope }) state
            else state.copy(scopes = state.scopes + listOf(listOf(scope.principal, scope.workspaceId)))
        }
    }

    /** Publish only after complete facts and the original token-bound SessionDto have been committed. */
    suspend fun publish(ticket: LedgerSessionTicket, scope: LedgerCacheScope) {
        require(scope.apiOrigin == ticket.apiOrigin) { "ledger_session_scope_invalid" }
        validateScope(scope)
        change(ticket.apiOrigin) { state ->
            state.requireTicket(ticket)
            check(state.scopes.any { it.toScope(ticket.apiOrigin) == scope }) { "ledger_session_scope_missing" }
            state.copy(currentId = ledgerStorageId(scope))
        }
    }

    suspend fun current(ticket: LedgerSessionTicket): LedgerCacheScope? {
        val state = read(ticket.apiOrigin).second
        state.requireTicket(ticket)
        return state.scopes.map { it.toScope(ticket.apiOrigin) }
            .singleOrNull { ledgerStorageId(it) == state.currentId }
    }

    suspend fun scopes(apiOrigin: String): List<LedgerCacheScope> =
        read(apiOrigin).second.scopes.map { it.toScope(apiOrigin) }

    /** Hold the scope lease until files, keys and this conditional removal all succeed. */
    suspend fun forget(scope: LedgerCacheScope) {
        validateScope(scope)
        val id = ledgerStorageId(scope)
        change(scope.apiOrigin) { state -> state.copy(
            scopes = state.scopes.filterNot { it.toScope(scope.apiOrigin) == scope },
            currentId = state.currentId.takeUnless { it == id },
        ) }
    }

    private suspend fun change(apiOrigin: String, update: (Directory) -> Directory): Directory {
        while (true) {
            val (text, previous) = read(apiOrigin)
            val next = update(previous)
            if (next == previous) return next
            if (storage.compareAndSet(key(apiOrigin), text, Json.encodeToString(next))) return next
            yield()
        }
    }

    private suspend fun read(apiOrigin: String): Pair<String?, Directory> {
        val key = key(apiOrigin)
        val text = storage.read(key) ?: return null to Directory(apiOrigin = apiOrigin)
        val state = Json.decodeFromString<Directory>(text)
        check(state.version == 1 && state.apiOrigin == apiOrigin && state.generation > 0) { "ledger_session_directory_invalid" }
        val scopes = state.scopes.map { it.toScope(apiOrigin).also(::validateScope) }
        check(scopes.distinct().size == scopes.size &&
            (state.currentId == null || scopes.count { ledgerStorageId(it) == state.currentId } == 1)) {
            "ledger_session_directory_invalid"
        }
        return text to state
    }

    private fun key(apiOrigin: String): String {
        require(normalizeApiOrigin(apiOrigin, allowInsecureHttp = true) == apiOrigin) { "ledger_session_origin_invalid" }
        val bytes = apiOrigin.encodeToByteArray(throwOnInvalidSequence = true)
        return "ledger-session-current-v1:" + sha256Hex(bytes)
    }

    private fun validateScope(scope: LedgerCacheScope) {
        key(scope.apiOrigin)
        require(scope.principal.length == 64 && scope.principal.all { it in '0'..'9' || it in 'a'..'f' }) {
            "ledger_session_principal_invalid"
        }
        ledgerStorageId(scope) // Strict UTF-8 validation; preserve the exact opaque workspace identifier.
    }

    private fun List<String>.toScope(apiOrigin: String): LedgerCacheScope {
        check(size == 2) { "ledger_session_directory_invalid" }
        return LedgerCacheScope(apiOrigin, this[0], this[1])
    }

    private fun Directory.requireTicket(ticket: LedgerSessionTicket) {
        if (generation != ticket.generation) throw CancellationException("ledger_session_operation_superseded")
    }

    @Serializable
    private data class Directory(val version: Int = 1, val apiOrigin: String,
        val generation: Long = 0, val scopes: List<List<String>> = emptyList(), val currentId: String? = null)
}
