package com.finance.tracker.data

import com.finance.tracker.domain.LedgerCacheScope

/** Application entry points always bind scope registration to the platform's existing storage owner. */
internal expect suspend fun createPlatformLedgerFactCache(scope: LedgerCacheScope, existingOnly: Boolean,
    registration: LedgerSessionRegistration): LedgerStorage

internal expect fun createPlatformLedgerCacheErasure(scope: LedgerCacheScope,
    directory: LedgerSessionDirectory): LedgerCacheErasure
