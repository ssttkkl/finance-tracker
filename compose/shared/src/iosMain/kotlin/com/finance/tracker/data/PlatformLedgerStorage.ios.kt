package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope

internal actual suspend fun createPlatformLedgerFactCache(scope: LedgerCacheScope, existingOnly: Boolean,
    registration: LedgerSessionRegistration): LedgerStorage =
    createAppleLedgerFactCache(scope, existingOnly = existingOnly, registration = registration)

internal actual fun createPlatformLedgerCacheErasure(scope: LedgerCacheScope,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): LedgerCacheErasure = createAppleLedgerCacheErasure(scope, directory, ticket = ticket)
