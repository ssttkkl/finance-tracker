package com.finance.tracker

import com.finance.tracker.data.ledgerStorageId
import com.finance.tracker.domain.LedgerCacheScope
import kotlin.test.*

class LedgerStorageIdTest {
    @Test fun fullScopePreservesTheExistingNativeIdentity() {
        assertEquals("8e900c852f51a91ebb3419b17c602a895bcea053aa19ddb4a320bb11220f44c2",
            ledgerStorageId(LedgerCacheScope("https://example.test", "user-一", "workspace-😀")))
        val scope = LedgerCacheScope("ab", "c", "d")
        for (different in listOf(scope.copy(apiOrigin = "a"), scope.copy(principal = "bc"),
            scope.copy(workspaceId = "cd"), LedgerCacheScope("a", "bc", "d"))) {
            assertNotEquals(ledgerStorageId(scope), ledgerStorageId(different))
        }
        assertNotEquals(ledgerStorageId(scope), ledgerStorageId(scope.copy(apiOrigin = "ab/")))
    }

    @Test fun invalidUtf16IsRejectedInEveryScopeField() {
        val scope = LedgerCacheScope("origin", "user", "workspace")
        for (code in listOf(0xD800, 0xDC00)) {
            val invalid = "bad" + charArrayOf(code.toChar()).concatToString()
            assertEquals(code, invalid.last().code)
            assertFails { ledgerStorageId(scope.copy(apiOrigin = invalid)) }
            assertFails { ledgerStorageId(scope.copy(principal = invalid)) }
            assertFails { ledgerStorageId(scope.copy(workspaceId = invalid)) }
        }
    }
}
