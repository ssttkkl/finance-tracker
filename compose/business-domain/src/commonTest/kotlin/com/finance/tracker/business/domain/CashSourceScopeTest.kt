package com.finance.tracker.business.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CashSourceScopeTest {
    @Test
    fun platformScopesKeepTheirExistingOwnerRules() {
        assertEquals("alipay:transaction:v1", CashSourceScope.sourceTransactionScopeKey("alipay", "owner"))
        assertEquals("owner", CashSourceScope.sourceAccountKey("alipay", "owner"))
        assertEquals(null, CashSourceScope.sourceAccountKey("alipay"))
        for (owner in listOf(null, "", "owner", "😀".repeat(257))) {
            assertEquals("wechat:default:v1", CashSourceScope.sourceAccountKey("wechat", owner))
            assertEquals("wechat:default:v1", CashSourceScope.sourceTransactionScopeKey("wechat", owner))
        }
        assertEquals("bank owner", CashSourceScope.sourceTransactionScopeKey("icbc_credit", "bank owner"))
    }

    @Test
    fun ownerLimitsCountCodePointsAndDoNotTrimAcceptedValues() {
        assertEquals("😀".repeat(256), CashSourceScope.sourceAccountKey("bank", "😀".repeat(256)))
        assertEquals(" owner ", CashSourceScope.sourceAccountKey("bank", " owner "))
        for (owner in listOf("", "a".repeat(257), "😀".repeat(257))) {
            assertEquals("source_account_invalid", assertFailsWith<IllegalArgumentException> {
                CashSourceScope.sourceAccountKey("bank", owner)
            }.message)
        }
    }

    @Test
    fun platformTransactionIdentityRequiresUnpaddedNonPlaceholderText() {
        for (value in listOf(null, "", "/", "-", "--", " id", "id ", "\u001cid", "id\u001c", "😀".repeat(513))) {
            assertFalse(CashSourceScope.isValidPlatformTransactionId(value), value)
        }
        for (value in listOf("0", "001", "id/part", "😀".repeat(512))) {
            assertTrue(CashSourceScope.isValidPlatformTransactionId(value), value)
        }
    }
}
