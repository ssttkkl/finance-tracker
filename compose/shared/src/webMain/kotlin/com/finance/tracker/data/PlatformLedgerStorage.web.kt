package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope

internal actual suspend fun createPlatformLedgerFactCache(scope: LedgerCacheScope, existingOnly: Boolean,
    registration: LedgerSessionRegistration): LedgerStorage =
    createWebLedgerFactCache(scope, existingOnly = existingOnly, registration = registration)

internal actual fun createPlatformLedgerCacheErasure(scope: LedgerCacheScope,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): LedgerCacheErasure = createWebLedgerCacheErasure(scope, directory, ticket = ticket)
