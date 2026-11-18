package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashSourceCoverage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CashSourceCoverageTest {
    private fun coverage() = CashSourceCoverage("2026-08-01T00:00:00Z", "2026-09-01T00:00:00Z",
        true, "declared", mapOf("export_type" to "all"), "coverage-v1")

    @Test
    fun rangeBoundariesFollowExplicitInclusivityAtMicrosecondPrecision() {
        val value = coverage()
        assertTrue(value.contains("2026-08-01T00:00:00Z"))
        assertFalse(value.copy(startInclusive = false).contains("2026-08-01T00:00:00Z"))
        assertFalse(value.contains("2026-09-01T00:00:00Z"))
        assertTrue(value.copy(endInclusive = true).contains("2026-09-01T00:00:00Z"))
        assertTrue(value.contains(Instant.parse("2026-08-01T00:00:00.000000999Z")))
        assertTrue(value.contains("2026-08-15T08:00:00+08:00"))
    }

    @Test
    fun incompleteNaiveOrInvalidEvidenceNeverProvesContainment() {
        for (value in listOf(coverage().copy(complete = false), coverage().copy(evidence = ""),
            coverage().copy(start = null), coverage().copy(end = "wrong"),
            coverage().copy(start = "2026-08-01T00:00:00"), coverage().copy(end = coverage().start))) {
            assertFalse(value.contains("2026-08-15T00:00:00Z"))
        }
        for (value in listOf(null, 123, true, "2026-08-15T00:00", "wrong",
            Instant.parse("+10000-01-01T00:00:00Z"))) {
            assertFalse(coverage().contains(value))
        }
    }

    @Test
    fun comparabilityUsesBothRangesFiltersPrecisionAndStrategy() {
        val first = coverage()
        val time = "2026-08-15T00:00:00Z"
        assertTrue(first.comparable(first.copy(evidence = "another proof"), time))
        for (other in listOf(first.copy(filters = emptyMap()), first.copy(timePrecision = "day"),
            first.copy(strategyVersion = "v2"), first.copy(start = "2026-08-16T00:00:00Z"))) {
            assertFalse(first.comparable(other, time))
        }
        assertEquals(first, first.copy())
    }
}
