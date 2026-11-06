package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope

/** Scope-bound metadata actions. The platform factory must hold the actual storage lease throughout. */
internal class LedgerSessionRegistration(val scope: LedgerCacheScope,
    private val directory: LedgerSessionDirectory, private val ticket: LedgerSessionTicket) {
    init { require(scope.apiOrigin == ticket.apiOrigin) { "ledger_session_scope_invalid" } }
    fun requireScope(scope: LedgerCacheScope) { require(scope == this.scope) { "ledger_session_scope_invalid" } }
    suspend fun beforeOpen() = directory.register(ticket, scope)
    suspend fun afterErasure() = directory.forget(scope)
}
