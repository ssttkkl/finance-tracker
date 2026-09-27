package com.finance.tracker

import com.finance.tracker.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticLoggingTest {
    @Test
    fun platformDiagnosticTimestampUsesUtcEpochMilliseconds() {
        val timestamp = diagnosticEpochMillis()

        assertTrue(timestamp in 1_577_836_800_000L..4_102_444_800_000L)
    }

    @Test
    fun recordsOnlyWhitelistedFieldsToBothSinks() {
        val store = FakeDiagnosticStore()
        val logger = SanitizedDiagnosticLogger(store) { NOW }

        logger.record(
            DiagnosticFeature.AUTHENTICATION,
            DiagnosticAction.AUTHENTICATE,
            errorCode = "network_unavailable",
            status = 503,
            exceptionType = "DomainFailure",
        )

        val expected = "$NOW\tAUTHENTICATION\tAUTHENTICATE\tnetwork_unavailable\t503\tDomainFailure"
        assertEquals(listOf(expected), store.developerLines)
        assertEquals("$expected\n", store.localContents)
    }

    @Test
    fun unknownCodesAndMalformedTypesCannotLeakExceptionContent() {
        val store = FakeDiagnosticStore()
        val logger = SanitizedDiagnosticLogger(store) { NOW }
        val sensitive = "alice@example.com password=secret bearer=token 123.45 CSV statement"

        logger.record(
            DiagnosticFeature.CASH_IMPORT,
            DiagnosticAction.CONFIRM_IMPORT,
            errorCode = sensitive,
            status = 400,
            exceptionType = sensitive,
        )

        val logs = store.developerLines.joinToString() + store.localContents
        assertTrue(logs.contains("unknown_error"))
        assertTrue(logs.contains("Throwable"))
        assertFalse(logs.contains("alice@example.com"))
        assertFalse(logs.contains("password"))
        assertFalse(logs.contains("secret"))
        assertFalse(logs.contains("token"))
        assertFalse(logs.contains("123.45"))
        assertFalse(logs.contains("CSV statement"))
    }

    @Test
    fun dropsExpiredAndCorruptEntriesBeforeAppending() {
        val store = FakeDiagnosticStore()
        val expired = NOW - SanitizedDiagnosticLogger.RETENTION_MILLIS - 1
        val retained = NOW - SanitizedDiagnosticLogger.RETENTION_MILLIS + 1
        store.localContents = listOf(
            "$expired\tAUTHENTICATION\tAUTHENTICATE\tunknown_error\t\tDomainFailure",
            "not-a-diagnostic-row-with-secret",
            "$retained\tWORKSPACE\tLOAD\tunknown_error\t\tDomainFailure",
        ).joinToString("\n")

        SanitizedDiagnosticLogger(store) { NOW }.record(
            DiagnosticFeature.WORKSPACE,
            DiagnosticAction.LOAD,
            "api_request_failed",
            503,
            "DomainFailure",
        )

        assertFalse(store.localContents.contains(expired.toString()))
        assertFalse(store.localContents.contains("secret"))
        assertTrue(store.localContents.contains(retained.toString()))
        assertTrue(store.localContents.contains(NOW.toString()))
    }

    @Test
    fun removesOldestEntriesUntilLocalLogFitsWithinOneMebibyte() {
        val store = FakeDiagnosticStore()
        val seed = (0 until 20_000).joinToString("\n") { index ->
            "${NOW - 20_000 + index}\tCASH_LEDGER\tLOAD\tunknown_error\t\tDomainFailure"
        }
        store.localContents = seed

        SanitizedDiagnosticLogger(store) { NOW }.record(
            DiagnosticFeature.CASH_LEDGER,
            DiagnosticAction.LOAD,
            "api_request_failed",
            503,
            "DomainFailure",
        )

        assertTrue(store.localContents.encodeToByteArray().size <= SanitizedDiagnosticLogger.MAX_LOG_BYTES)
        assertTrue(store.localContents.lineSequence().first().substringBefore('\t').toLong() > NOW - 20_000)
        assertTrue(store.localContents.contains("\n$NOW\t"))
    }

    @Test
    fun failedLocalStorageDoesNotSuppressDeveloperLogOrThrow() {
        val store = FakeDiagnosticStore(failRead = true, failWrite = true)

        SanitizedDiagnosticLogger(store) { NOW }.record(
            DiagnosticFeature.INVITATION,
            DiagnosticAction.ACCEPT_INVITATION,
            "unknown_error",
            null,
            "IllegalStateException",
        )

        assertEquals(1, store.developerLines.size)
    }

    private class FakeDiagnosticStore(
        private val failRead: Boolean = false,
        private val failWrite: Boolean = false,
    ) : PlatformDiagnosticLogStore {
        val developerLines = mutableListOf<String>()
        var localContents: String = ""

        override fun writeDeveloperLog(line: String) {
            developerLines += line
        }

        override fun readLocalLog(): String? {
            check(!failRead)
            return localContents
        }

        override fun writeLocalLog(contents: String) {
            check(!failWrite)
            localContents = contents
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
