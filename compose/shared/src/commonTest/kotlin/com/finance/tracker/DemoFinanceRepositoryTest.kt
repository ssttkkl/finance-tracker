package com.finance.tracker

import com.finance.tracker.data.DemoFinanceRepository
import com.finance.tracker.domain.CashFilters
import com.finance.tracker.presentation.cashProjectionSourceResourceKey
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DemoFinanceRepositoryTest {
    @Test
    fun fetchEvidenceAcceptsCashProjectionIdForDetails() = runTest {
        val repository = DemoFinanceRepository()

        val evidence = repository.fetchEvidence("projection-demo-record-3")
        val detail = repository.fetchCashRecord(evidence.rootRecord.id)

        assertEquals("fact-demo-record-3", evidence.rootRecord.id)
        assertEquals("demo-record-3", detail.record.id)
    }

    @Test
    fun cashRecordDetailsResolveCategoryFromStoredCategoryId() = runTest {
        val repository = DemoFinanceRepository()

        val detail = repository.fetchCashRecord("demo-record-2")

        assertEquals("food", detail.record.category?.id)
        assertEquals("餐饮", detail.record.category?.name)
    }

    @Test
    fun createRelationUsesLocalizedTypeLabel() = runTest {
        val repository = DemoFinanceRepository()

        val detail = repository.createCashRelation("fact-demo-record-3", "fact-demo-record-2", "payment_mirror")

        assertEquals("同笔支付", detail.relations.single().label)
    }
    @Test
    fun singleRecordDemoHasNoMergedLabelAndRemainsInSingleFilter() = runTest {
        val repository = DemoFinanceRepository()
        val rows = repository.fetchCashPage(CashFilters()).items
        assertTrue(rows.isNotEmpty())
        rows.forEach { assertEquals(null, cashProjectionSourceResourceKey(it)) }
        assertEquals(rows.map { it.transactionId }, repository.fetchCashPage(CashFilters(composition = "single")).items.map { it.transactionId })
    }

}
