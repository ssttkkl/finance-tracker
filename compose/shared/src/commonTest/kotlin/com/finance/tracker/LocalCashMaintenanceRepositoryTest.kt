package com.finance.tracker

import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.core.LocalCashLedgerReader
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocalCashMaintenanceRepositoryTest {
    private class Reader : LedgerLocalReader {
        private val query = LocalCashLedgerReader(localCashLedgerOwnerSnapshot())
        override suspend fun page(filters: CashFiltersDto, timeZoneId: String?, cursor: String?, startIndex: Int?, expectedLedgerSeq: Long?, limit: Int) =
            query.page(filters, timeZoneId, cursor, startIndex, expectedLedgerSeq, limit)
        override suspend fun currentGroup(projectionId: String) = query.currentGroup(projectionId)
        override suspend fun record(transactionId: String) = query.record(transactionId, true)
        override suspend fun sourceRecords(projectionId: String) = query.sourceRecords(projectionId, true)
        override suspend fun accounts(): List<AccountDto>? = null
        override suspend fun categories(): CashCategoryDirectoryDto? = null
        override suspend fun options(): LedgerOptionsDto? = null
    }

    private fun remote() = object : CashMaintenanceRepository by DemoCashMaintenanceRepository(DemoFinanceRepository()) {
        override suspend fun record(transactionId: String): CashCurrentRecord = error("No remote detail fallback")
        override suspend fun currentGroup(projectionId: String): CashCurrentGroup = error("No remote group fallback")
        override suspend fun sources(projectionId: String): CashSourceEvidence = error("No remote evidence fallback")
    }

    @Test fun completeLocalDetailsAndEvidenceUseOneExactVersionAndNoRemoteReads() = runTest {
        val repository = LocalCashMaintenanceRepository(remote(), { Reader() })
        val record = repository.record("1")
        val group = repository.currentGroup("cash-entry:entry:1")
        val source = repository.sources("cash-entry:entry:1")
        assertEquals(9007199254740993L, group.projectionVersion)
        assertEquals(group.projectionVersion, record.projectionRef?.projectionVersion)
        assertEquals(group.projectionVersion, source.projectionVersion)
        assertEquals(listOf("1"), group.members.map { it.transactionId })
        assertEquals(listOf("1"), source.members.map { it.transactionId })
        assertEquals(record.fields, source.members.single().fields)
    }

    @Test fun missingFactsAndClosedOwnersCannotSelectTheRemoteDetailPath() = runTest {
        val missing = LocalCashMaintenanceRepository(remote(), { null })
        assertEquals("projection.unavailable", assertFailsWith<DomainFailure> { missing.record("1") }.code)
        assertEquals("projection.unavailable", assertFailsWith<DomainFailure> { missing.currentGroup("cash-entry:entry:1") }.code)
        assertEquals("projection.unavailable", assertFailsWith<DomainFailure> { missing.sources("cash-entry:entry:1") }.code)
        val closed = LocalCashMaintenanceRepository(remote(), { throw CancellationException("closed original owner") })
        assertFailsWith<CancellationException> { closed.record("1") }
        assertEquals("record_not_found", assertFailsWith<DomainFailure> {
            LocalCashMaintenanceRepository(remote(), { Reader() }).record("999")
        }.code)
    }
}
