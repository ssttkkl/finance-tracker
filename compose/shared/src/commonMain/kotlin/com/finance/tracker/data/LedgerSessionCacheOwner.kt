package com.finance.tracker.data

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CategoryDirectoryDto
import com.finance.tracker.business.contracts.LedgerOptionsDto
import com.finance.tracker.business.contracts.SessionDto
import com.finance.tracker.core.LocalCashLedgerOwner
import com.finance.tracker.domain.LedgerCacheScope
import com.finance.tracker.domain.LedgerSnapshotSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One fixed recovery operation. Retain this instance, including after failure, until close succeeds. */
internal class LedgerSessionCacheOwner(
    private val api: FinanceApiClient,
    private val directory: LedgerSessionDirectory,
    private val ticket: LedgerSessionTicket,
    private val scope: LedgerCacheScope,
    private val credentials: ApiCredentialSnapshot,
    private val source: LedgerSnapshotSource = KtorLedgerSnapshotSource(api, credentials),
    private val openCache: suspend (existingOnly: Boolean, registration: LedgerSessionRegistration) -> LedgerStorage,
) {
    private val token = requireNotNull(credentials.token?.takeIf { it.isNotBlank() }) { "ledger_session_token_invalid" }
    private val resourceMutex = Mutex()
    // Also fences an open which has not returned its storage yet; the browsing owner cannot fence that interval.
    private val closing = MutableStateFlow(false)
    private class Resource(val storage: LedgerStorage, val browsing: LocalCashLedgerOwner)
    private var owned: Resource? = null // Only under resourceMutex; failed closure must retain this reference.

    init {
        directory.validateScope(scope)
        require(credentials.apiOrigin == scope.apiOrigin && ticket.apiOrigin == scope.apiOrigin) { "ledger_session_scope_invalid" }
    }

    /** No HTTP and no creation of substitute materials. Null means no eligible bound identity. */
    suspend fun readOfflineSession(): SessionDto? {
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            withContext(NonCancellable) {
                requireCurrent(requirePublished = true)
                val resource = acquire(existingOnly = true)
                val session = resource.storage.readOfflineSession(scope, token)
                requireCurrent(requirePublished = true)
                requireOpen()
                session
            }
        }
    }

    /** The caller has verified this session with the original credentials; network work holds no resource mutex. */
    suspend fun refresh(session: SessionDto, committedLedgerSeq: String? = null) {
        requireOpen()
        val resource = resourceMutex.withLock {
            requireOpen()
            withContext(NonCancellable) { acquire(existingOnly = false) }
        }
        resource.browsing.refresh(committedLedgerSeq)
        resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = false)
            api.publishVerifiedLedgerSession(credentials, directory, ticket, scope, resource.storage, session)
            requireCurrent(requirePublished = true)
            requireOpen()
        }
    }

    /** Only complete committed facts are read; authorization and operation fences are checked around derivation. */
    suspend fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto? =
        read { it.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit) }

    suspend fun categories(): CategoryDirectoryDto? = read { it.categories() }
    suspend fun options(): LedgerOptionsDto? = read { it.options() }

    private suspend fun <T> read(read: suspend (LocalCashLedgerOwner) -> T?): T? {
        requireOpen()
        return resourceMutex.withLock {
            requireOpen()
            requireCurrent(requirePublished = true)
            val resource = owned
            val result = if (resource == null || resource.storage.readOfflineSession(scope, token) == null) null
                else read(resource.browsing)
            requireCurrent(requirePublished = true)
            requireOpen()
            result
        }
    }

    /** Preserve materials, or finish erasure already selected by the scoped browsing owner. Never reopen. */
    suspend fun close() {
        closing.value = true
        withContext(NonCancellable) {
            resourceMutex.withLock {
                owned?.browsing?.close()
                owned = null
            }
        }
    }

    /** Called under resourceMutex and NonCancellable: publish ownership before any cancellable hand-off. */
    private suspend fun acquire(existingOnly: Boolean): Resource {
        requireOpen()
        requireCurrent(requirePublished = false)
        val resource = owned ?: openCache(existingOnly, LedgerSessionRegistration(scope, directory, ticket)).let { storage ->
            Resource(storage, LocalCashLedgerOwner(scope, storage, source)).also { owned = it }
        }
        requireCurrent(requirePublished = false)
        requireOpen()
        return resource
    }

    private suspend fun requireCurrent(requirePublished: Boolean) {
        if (api.readToken() != token) superseded()
        val current = directory.current(ticket)
        if (requirePublished && current != scope) superseded()
        if (api.readToken() != token) superseded()
    }

    private fun requireOpen() { if (closing.value) throw CancellationException("ledger_cache_closed") }
    private fun superseded(): Nothing = throw CancellationException("ledger_session_operation_superseded")
}
