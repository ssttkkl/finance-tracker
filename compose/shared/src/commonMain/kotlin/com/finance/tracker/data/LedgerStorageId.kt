package com.finance.tracker.data

import com.finance.tracker.business.domain.sha256Hex
import com.finance.tracker.domain.LedgerCacheScope

/** Exact native storage identity. Preserve UTF-16 lengths and reject malformed input before hashing. */
internal fun ledgerStorageId(scope: LedgerCacheScope): String {
    val encoded = buildString {
        append("ledger-cache-v1:")
        for (field in listOf(scope.apiOrigin, scope.principal, scope.workspaceId)) {
            field.encodeToByteArray(throwOnInvalidSequence = true)
            append(field.length).append(':').append(field)
        }
    }.encodeToByteArray(throwOnInvalidSequence = true)
    return sha256Hex(encoded)
}
