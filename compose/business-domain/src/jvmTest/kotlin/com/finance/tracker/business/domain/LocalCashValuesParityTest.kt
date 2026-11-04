package com.finance.tracker.business.domain

import java.time.Instant as NativeInstant
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalCashValuesParityTest {
    @Test fun localInstantEncodingPreservesNativeFractionGroups() {
        val random = Random(62111)
        val samples = mutableListOf<Pair<Long, Int>>()
        for (seconds in listOf(-62135596800L, -1L, 0L, 60L, 253402300799L))
            for (nanos in listOf(0, 1, 100, 1000, 100000, 1000000, 123000000, 123456000, 123456789)) samples += seconds to nanos
        repeat(5_000) { samples += random.nextLong(-62135596800L, 253402300800L) to random.nextInt(1_000_000_000) }
        for ((seconds, nanos) in samples) assertEquals(NativeInstant.ofEpochSecond(seconds, nanos.toLong()).toString(),
            localCashInstantText(kotlin.time.Instant.fromEpochSeconds(seconds, nanos.toLong())))
    }
}
