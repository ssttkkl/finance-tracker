package com.finance.tracker.business.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CashStatementDocumentContractTest {
    private val explicit = Json { encodeDefaults = true; explicitNulls = true }

    @Test fun rawRowsKeepNullEmptyAndDocumentPositionDistinct() {
        val rows = listOf(
            RawCashRow(1, null, "row_unreadable", "p1:t0:r1"),
            RawCashRow(2, emptyMap()),
            RawCashRow(3, mapOf("商品" to "  原文\n不裁剪  ", "金额" to "-0.00")),
        )
        val wire = """[{"position":1,"payload":null,"errorCode":"row_unreadable","documentPosition":"p1:t0:r1"},{"position":2,"payload":{},"errorCode":null,"documentPosition":null},{"position":3,"payload":{"商品":"  原文\n不裁剪  ","金额":"-0.00"},"errorCode":null,"documentPosition":null}]"""
        assertEquals(wire, explicit.encodeToString(rows))
        assertEquals(rows, explicit.decodeFromString<List<RawCashRow>>(wire))
    }

    @Test fun fullDocumentKeepsCountsCoverageAndOptionalDefaults() {
        val wire = """{"sourceType":"wechat","tableKey":"csv:0","parserVersion":"v1","rows":[{"position":1,"payload":null}],"sourceAccountIdentifier":null,"sourceAccountLabel":"来源原文","coverage":{"start":null,"end":null,"complete":false,"evidence":"","filters":{},"strategyVersion":"v1"},"declaredCount":9223372036854775807,"observedCount":1}"""
        val document = Json.decodeFromString<CashStatementDocument>(wire)
        assertEquals(Long.MAX_VALUE, document.declaredCount)
        assertNull(document.sourceCurrency)
        assertNull(document.sourceNickname)
        assertEquals(document, explicit.decodeFromString<CashStatementDocument>(explicit.encodeToString(document)))
        assertEquals(document.copy(sourceCurrency = "CNY", sourceNickname = ""),
            explicit.decodeFromString<CashStatementDocument>(explicit.encodeToString(document.copy(sourceCurrency = "CNY", sourceNickname = ""))))
    }
}
