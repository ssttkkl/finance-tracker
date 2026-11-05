package com.finance.tracker.backend

import java.time.Instant
import kotlin.test.*

class FxRateOriginalTest {
    @Test fun businessDayFromNaiveString() {
        assertEquals("2025-12-19", FxRates().businessDayUtc("2025-12-19 06:30:57"))
    }
    @Test fun rateErrorCloseMatch() {
        val result = fxRateError("144.61", "159.40", "CNY", "HKD", "1.1051")
        assertNotNull(result); assertTrue(result.toBigDecimal() < "0.015".toBigDecimal())
    }
    @Test fun rateErrorWrongPairLarge() {
        val result = fxRateError("144.61", "2240", "CNY", "JPY", "1.1051")
        assertNotNull(result); assertTrue(result.toBigDecimal() > "0.5".toBigDecimal())
    }
    @Test fun getMidRateUsesFetcher() {
        val rates = FxRates(); rates.clearCache(); val calls = mutableListOf<List<String>>()
        val result = rates.getMidRate("2025-12-19", "USD", "CNY", FxMidFetcher { day, base, quote ->
            calls.add(listOf(day, base, quote)); "7.1" })
        assertEquals(0, "7.1".toBigDecimal().compareTo(assertNotNull(result).toBigDecimal()))
        assertEquals(listOf(listOf("2025-12-19", "USD", "CNY")), calls)
    }
    @Test fun providerUsesFetcherAndSameCurrency() {
        val calls = mutableListOf<List<String>>(); val rates = FxRates(clock = { Instant.parse("2026-07-25T00:00:00Z") }); rates.clearCache()
        val provider = FxRateProvider(FxMidFetcher { day, base, quote -> calls.add(listOf(day, base, quote)); "7.2" }, rates)
        assertEquals(0, "1".toBigDecimal().compareTo(assertNotNull(provider.getMid("USD", "USD")).toBigDecimal()))
        assertEquals(0, "7.2".toBigDecimal().compareTo(assertNotNull(provider.getMid("USD", "CNY", "2026-07-25")).toBigDecimal()))
        assertTrue(calls.isNotEmpty()); assertEquals(listOf("USD", "CNY"), calls[0].drop(1))
    }
}
