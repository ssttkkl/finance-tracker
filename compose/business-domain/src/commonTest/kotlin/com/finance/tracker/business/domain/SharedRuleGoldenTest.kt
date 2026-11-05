package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedRuleGoldenTest {
    private val golden = Json.parseToJsonElement(sharedRuleGoldenJson).jsonObject

    @Test fun decimalAcceptanceMatchesTheOriginalPythonNumericBoundary() {
        for (value in golden.getValue("decimals").jsonArray) {
            val case = value.jsonObject
            val text = case.getValue("input").jsonPrimitive.content
            assertEquals(case.getValue("valid").jsonPrimitive.boolean, LedgerDecimal.parseOrNull(text) != null, text)
        }
    }

    @Test fun knownCashComponentsMatchPythonIncludingIncompleteDraftsAndPreciseErrors() {
        for (value in golden.getValue("components").jsonArray) {
            val case = value.jsonObject
            val record = case.getValue("record").jsonObject
            val actual = cashComponentError(record)
            val expected = case["error"]
            if (expected == JsonNull) assertEquals(null, actual, record.toString())
            else {
                val error = expected!!.jsonObject
                assertEquals(error.getValue("code").jsonPrimitive.content, actual?.code, record.toString())
                assertEquals(error.getValue("fields").jsonArray.map { it.jsonPrimitive.content }, actual?.fields, record.toString())
            }
        }
    }

    @Test fun aNonNullOrdinalAlwaysRequiresItsValidRange() {
        assertEquals(CashRuleError("invalid_field", listOf("components")), cashComponentError(
            CashFinancialSnapshot(null, null, null, components = listOf(CashComponent(null, null, null, -1, false)))))
    }
}
