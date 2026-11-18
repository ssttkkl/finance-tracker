package com.finance.tracker.data

import com.finance.tracker.core.AndroidTokenStoreContext
import com.finance.tracker.domain.LedgerCacheScope

internal actual suspend fun createPlatformLedgerFactCache(scope: LedgerCacheScope, existingOnly: Boolean,
    registration: LedgerSessionRegistration): LedgerStorage = createAndroidLedgerFactCache(
        checkNotNull(AndroidTokenStoreContext.applicationContext) { "ledger_cache_context_unavailable" },
        scope, existingOnly = existingOnly, registration = registration)

internal actual fun createPlatformLedgerCacheErasure(scope: LedgerCacheScope,
    directory: LedgerSessionDirectory, ticket: LedgerSessionTicket): LedgerCacheErasure = createAndroidLedgerCacheErasure(
        checkNotNull(AndroidTokenStoreContext.applicationContext) { "ledger_cache_context_unavailable" },
        scope, directory, ticket = ticket)
