package com.finance.tracker

import com.finance.tracker.data.AppleLedgerKeyVault
import kotlin.test.Test
import kotlin.test.assertFails

class AppleLedgerKeyIdTest {
    @Test
    fun invalidIdentifiersAreRejectedBeforeTouchingKeychain() {
        for (id in listOf("", "workspace", "a".repeat(63), "g".repeat(64), "A".repeat(64))) {
            assertFails { AppleLedgerKeyVault(id) }
        }
        AppleLedgerKeyVault("0123456789abcdef".repeat(4))
    }
}
