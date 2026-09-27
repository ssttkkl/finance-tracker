package com.finance.tracker

import com.finance.tracker.app.*
import com.finance.tracker.core.*
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import com.finance.tracker.presentation.*

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformTokenStoreIOSTest {
    @Test
    fun keychainTokenRoundTripsOrFailsClosedWhenUnavailable() = runTest {
        val store = createPlatformTokenStore()
        try {
            store.clear()
            store.set("compose-keychain-fixture")
            assertEquals("compose-keychain-fixture", store.get())
            store.clear()
            assertNull(store.get())
        } catch (failure: IllegalStateException) {
            assertEquals("token_store_unavailable", failure.message)
        }
    }
}
