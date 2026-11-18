package com.finance.tracker.business.domain

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlin.test.*

class LocalCashRelationRowParityTest {
    @Test fun ordinalMatchesTheOriginalJvmIntegerGateAcrossEveryBmpUnitAndBounds() {
        val row = mapOf<String, Any?>("id" to "row", "amount" to "1.00")
        for (code in 0..0xffff) {
            val raw = code.toChar().toString()
            assertEquals(raw.toIntOrNull(), LocalCashRelationPlanning.factFromRow(row + ("ordinal" to raw)).componentOrdinal,
                "U+${code.toString(16)}")
        }
        for (raw in listOf("+００３", "-١٢", "2147483647", "-2147483648", "2147483648", "-2147483649", " 1", "1.0", "𐒠", "9".repeat(2000)))
            assertEquals(raw.toIntOrNull(), LocalCashRelationPlanning.factFromRow(row + ("ordinal" to raw)).componentOrdinal, raw)
    }

    @Test fun computedDecimalJsonRetainsTheNativeBigDecimalTextIncludingExponent() {
        for (text in listOf("1E-7", "1.0000", "1E+20", "0.0000000", "-0E+12")) {
            val expected = sourceJson(mapOf("amount" to BigDecimal(text)), { (it as? BigDecimal)?.toString() }, ::pythonFloatText)
            assertEquals(expected, LocalCashRelationPlanning.json(mapOf("amount" to LocalCashRelationArithmetic.amount(text))))
        }
    }

    @Test fun sourceFloatingValuesMatchTheOriginalNativeDoubleEncoding() {
        for (text in listOf("1.000000000000000001", "1.000000000000000002", "1e400", "-1e400", "1e-400", "-0.0")) {
            val expected = sourceJson(mapOf("value" to text.toDouble()), { null }, { value -> when {
                value == Double.POSITIVE_INFINITY -> "Infinity"
                value == Double.NEGATIVE_INFINITY -> "-Infinity"
                else -> pythonFloatText(value)
            } })
            assertEquals(expected.toString(), LocalCashRelationPlanning.json(mapOf("value" to Json.parseToJsonElement(text))).toString(), text)
        }
    }
}
