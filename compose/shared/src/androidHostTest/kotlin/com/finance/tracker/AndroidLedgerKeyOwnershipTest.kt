package com.finance.tracker

import com.finance.tracker.data.acquireAndroidLedgerKey
import java.io.IOException
import org.junit.Test
import kotlin.test.*

class AndroidLedgerKeyOwnershipTest {
    @Test fun releaseFailureWipesTheUndeliveredRawKeyAndPreservesTheFailure() {
        val generated = ByteArray(32) { (it + 1).toByte() }
        val failure = IOException("Synthetic lock release failure")
        assertSame(failure, assertFails {
            acquireAndroidLedgerKey(lock = { block -> block(); throw failure }) { generated }
        })
        assertTrue(generated.all { it == 0.toByte() })
    }

    @Test fun successfulReleaseTransfersTheSameArrayAndMissingReadRemainsNull() {
        val generated = ByteArray(32) { 7 }
        var released = false
        val result = acquireAndroidLedgerKey(lock = { block -> block(); released = true }) { generated }
        assertTrue(released)
        assertSame(generated, result)
        assertTrue(generated.all { it == 7.toByte() })
        generated.fill(0)
        assertNull(acquireAndroidLedgerKey(lock = { it() }) { null })
    }
}
