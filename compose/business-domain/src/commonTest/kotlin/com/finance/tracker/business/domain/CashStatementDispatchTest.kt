package com.finance.tracker.business.domain

import kotlin.test.*

class CashStatementDispatchTest {
    private val ole = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31)
    @Test fun originalByteAndSourcePriorityIsPreserved() {
        assertEquals(CashStatementFormat.PDF, cashStatementFormat("%PDF-test".encodeToByteArray(), "alipay"))
        assertEquals(CashStatementFormat.PDF, cashStatementFormat(byteArrayOf(1), "icbc-credit"))
        assertEquals(CashStatementFormat.XLS, cashStatementFormat(ole))
        assertEquals(CashStatementFormat.XLS, cashStatementFormat(ole, "ccb-debit"))
        assertEquals(CashStatementFormat.ICBC_ASIA, cashStatementFormat(byteArrayOf(-1, -2)))
        assertEquals(CashStatementFormat.ICBC_ASIA, cashStatementFormat(byteArrayOf(-2, -1), "icbc_asia"))
        assertEquals(CashStatementFormat.XLSX, cashStatementFormat("PK".encodeToByteArray(), "wechat"))
        assertEquals(CashStatementFormat.CSV, cashStatementFormat(ole, "alipay"))
        assertEquals(CashStatementFormat.CSV, cashStatementFormat(byteArrayOf(-1, -2), "alipay"))
        assertEquals(CashStatementFormat.CSV, cashStatementFormat(byteArrayOf(1)))
    }
    @Test fun emptyAndExplicitBankMismatchKeepTheirOriginalErrors() {
        assertEquals("statement_input_invalid", assertFailsWith<StatementDocumentFailure> { cashStatementFormat(byteArrayOf(), "icbc") }.code)
        for ((bytes, source) in listOf(ole to "icbc_asia", byteArrayOf(-1, -2) to "ccb_debit", byteArrayOf(1) to "ccb_debit"))
            assertEquals("statement_source_unsupported", assertFailsWith<StatementDocumentFailure> { cashStatementFormat(bytes, source) }.code)
    }
}
