package com.finance.tracker.backend

import java.math.BigDecimal
import kotlin.test.*

class CashFundingFailureOriginalTest {
    private fun file(methods: List<String> = listOf("招商银行储蓄卡(1234)")): ParsedImportFile {
        val rows = methods.mapIndexed { i, method -> ParsedCashRow(i + 1,
            mapOf("amount" to "-30", "currency" to "CNY", "occurred_at" to "2026-10-01T10:00:00+00:00",
                "record_type" to "consumption", "record_subtype" to "not_applicable", "note" to "  原始备注  ",
                "source_payload" to mapOf("付款方式" to method, "商品说明" to "")), method) }
        return ParsedImportFile("a", "a".repeat(64), "parsed", ParsedCashDocument("alipay", "table", "native-v1", rows,
            "owner", "owner", CashSourceCoverage(null, null, false, "", emptyMap(), "source-coverage-v1"), rows.size, rows.size),
            clientFileRef = "a", filename = "fixture.csv", size = 100, sourceType = "alipay")
    }
    private fun resolve(engine: CashImportFundingEngine, scan: CashFundingScan) = engine.resolve(scan,
        scan.groups.mapIndexed { i, g -> g.sourceAccountRef to mapOf("account_id" to (i + 1).toString(), "expected_account_revision" to 3) }.toMap(),
        scan.groups.indices.associate { i -> (i + 1).toString() to mapOf("id" to (i + 1).toString(), "revision" to 3,
            "active" to true, "type" to "cash", "currencies" to listOf("CNY")) })

    @Test fun test_internal_adapter_failure_stops_funding_scan() {
        val failure = IllegalStateException("sensitive importer error")
        val visited = mutableListOf<Any?>()
        val engine = object : CashImportFundingEngine() {
            override fun parseAlipayPaymentComponents(row: Map<String, Any?>): List<LinkedHashMap<String, Any?>> {
                visited += row["payment_method"]
                if (row["payment_method"] == "异常") throw failure
                return super.parseAlipayPaymentComponents(row)
            }
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { engine.scan(listOf(file(listOf("异常", "余额")))) })
        assertEquals(listOf<Any?>("异常"), visited)
    }

    @Test fun test_internal_funding_failure_stops_instead_of_returning_a_bad_source_row() {
        for (stage in listOf("scan", "resolve")) {
            val failure = IllegalStateException("synthetic internal funding failure")
            var calls = 0
            if (stage == "scan") {
                val engine = object : CashImportFundingEngine() {
                    override fun parts(file: ParsedImportFile, row: ParsedCashRow): Pair<List<SourcePart>, List<String>> {
                        calls++
                        throw failure
                    }
                }
                assertSame(failure, assertFailsWith<IllegalStateException> { engine.scan(listOf(file())) })
            } else {
                val scan = CashImportFunding.scan(listOf(file()))
                val engine = object : CashImportFundingEngine() {
                    override fun total(values: List<BigDecimal>): BigDecimal {
                        calls++
                        throw failure
                    }
                }
                assertSame(failure, assertFailsWith<IllegalStateException> { resolve(engine, scan) })
            }
            assertEquals(1, calls, stage)
        }
    }

    @Test fun failingInstanceLeavesDefaultAndOtherInstancesUnchanged() {
        val input = listOf(file())
        val expected = CashImportFunding.scan(input)
        val failure = IllegalStateException("instance failure")
        val broken = object : CashImportFundingEngine() {
            override fun total(values: List<BigDecimal>): BigDecimal = throw failure
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { resolve(broken, expected) })
        val normal = CashImportFundingEngine()
        assertEquals(expected, normal.scan(input))
        assertEquals(expected, CashImportFunding.scan(input))
        assertEquals(resolve(CashImportFunding, expected), resolve(normal, expected))
        assertTrue(resolve(CashImportFunding, expected).single().ready)
    }
}
