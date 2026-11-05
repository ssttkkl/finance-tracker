package com.finance.tracker.backend

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.test.*
import kotlinx.serialization.json.*

/**
 * 原 `tests/test_convert.py::TestIcbcParseLines` 与 `TestIcbcEdgeCases` 中可在当前管线同义执行的定义。
 *
 * 旧私有文本解析器已退役；这里把原测试的扁平文本单元映射成当前 PDF 表格读取器公开的单元格，
 * 走真实 `normalizeIcbcDocument`，再按原断言核对归一后的原生记录。预期值全部来自原断言字面量。
 */
class IcbcRowNormalizationOriginalTest {
    private val cases: JsonArray by lazy {
        Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-icbc-row-normalization.json"))
            .bufferedReader().use { it.readText() }).jsonArray
    }

    @Test fun originalTimeFromNextDateLine() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_时间从日期下行读取")

    @Test fun originalUsdCurrency() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_USD币种检测")

    @Test fun originalHkdCurrency() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_HKD币种检测")

    @Test fun originalCounterpartyAndSummarySplit() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_交易对方_描述分离")

    @Test fun originalUsdTransferDescription() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_美元转账_描述不被污染")

    @Test fun originalPlatformInferredFromCounterparty() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_平台从交易对方推断")

    @Test fun originalMultipleRowsKeepIndependentTimes() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_多条记录_时间独立")

    @Test fun originalNegativeAmountExpense() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_负金额_支出")

    @Test fun originalPositiveAmountIncome() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_正金额_收入")

    @Test fun originalUnsignedPositiveAmountIncome() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_正金额无加号_收入")

    @Test fun originalDebitTimeNotZero() =
        runDefinition("tests/test_convert.py::TestIcbcParseLines::test_借记卡_时间不归一为零")

    @Test fun originalSingleChargeLineNegatesAmount() =
        runDefinition("tests/test_convert.py::TestIcbcEdgeCases::test_单独借行_仍触发负号")

    @Test fun originalCurrencySkipsBlankBeforeAmount() =
        runDefinition("tests/test_convert.py::TestIcbcEdgeCases::test_币种_前一行空白_向后扫描")

    @Test fun originalCreditTransferSummaryAndVenue() =
        runDefinition("tests/test_convert.py::TestIcbcEdgeCases::test_信用卡转账摘要保留且不混入交易场所")

    @Test fun originalDebitFallbackSkipsBusinessSerial() =
        runDefinition("tests/test_convert.py::TestIcbcEdgeCases::test_借记卡回退解析跳过业务流水号并保留转账摘要")

    private fun runDefinition(source: String) {
        val definition = cases.map { it.jsonObject }
            .firstOrNull { it.getValue("source").jsonPrimitive.content == source } ?: fail("Missing case $source")
        val credit = definition.getValue("credit").jsonPrimitive.boolean
        val cells = definition.getValue("cells").jsonArray.map { row ->
            row.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        }
        val account = definition["source_account_identifier"]?.jsonPrimitive?.contentOrNull
        val sourceType = if (credit) "icbc_credit" else "icbc_debit"
        val label = if (credit) "工商银行信用卡" else "工商银行借记卡"
        val document = CashStatementDocument(sourceType, "pdf:0", "$sourceType-row-normalization-v1",
            cells.mapIndexed { index, cell ->
                RawCashRow(index + 1, cell, documentPosition = "p1:t0:r${index + 1}")
            }, account, label,
            CashSourceCoverage(null, null, false, "", emptyMap(), "icbc-pdf-coverage-v1"),
            cells.size.toLong(), cells.size)
        val observed = mutableListOf<Map<String, Any?>>()
        val parsed = CashStatementRowNormalization.normalizeIcbcDocument(document) { _, record -> observed += record }
        assertEquals(cells.size, observed.size, source)
        assertEquals(cells.size, parsed.rows.size, source)
        for (row in parsed.rows) {
            assertNull(row.errorCode, source)
            assertEquals(emptyList<String>(), row.issueCodes, source)
        }
        for (check in definition.getValue("checks").jsonArray) evaluate(check.jsonObject, source, observed, parsed.rows)
    }

    private fun evaluate(check: JsonObject, source: String, records: List<Map<String, Any?>>,
        rows: List<ParsedCashRow>) {
        val expected = expectedValue(check.getValue("value").jsonObject)
        val index = check["index"]?.jsonPrimitive?.intOrNull
        when (check.getValue("kind").jsonPrimitive.content) {
            "count" -> assertTrue(equal(records.size, expected), "$source $check")
            "record_field" -> {
                val actual = records[assertNotNull(index)][check.getValue("field").jsonPrimitive.content]
                assertTrue(equal(actual, expected), "$source $check actual=$actual")
            }
            "record_field_ne" -> {
                val actual = records[assertNotNull(index)][check.getValue("field").jsonPrimitive.content]
                assertFalse(equal(actual, expected), "$source $check actual=$actual")
            }
            "fields_field" -> {
                val actual = rows[assertNotNull(index)].fields?.get(check.getValue("field").jsonPrimitive.content)
                assertTrue(equal(actual, expected), "$source $check actual=$actual")
            }
            "fields_local_time" -> {
                val actual = rows[assertNotNull(index)].fields?.get("occurred_at")
                val local = OffsetDateTime.parse(assertNotNull(actual).toString())
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                assertTrue(equal(local, expected), "$source $check actual=$local")
            }
            else -> error("Unexpected check kind")
        }
    }

    private fun expectedValue(value: JsonObject): Any = when (value.getValue("kind").jsonPrimitive.content) {
        "decimal" -> BigDecimal(value.getValue("value").jsonPrimitive.content)
        "literal" -> when (val literal = value.getValue("value")) {
            JsonNull -> JsonNull
            else -> if (literal.jsonPrimitive.isString) literal.jsonPrimitive.content else literal.jsonPrimitive.boolean
        }
        else -> error("Unexpected expected value")
    }

    private fun equal(actual: Any?, expected: Any): Boolean = when {
        expected == JsonNull -> actual == null
        actual is Number && expected is BigDecimal -> BigDecimal(actual.toString()).compareTo(expected) == 0
        else -> actual == expected
    }
}
