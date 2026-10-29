package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class ReceiptWriteGoldenTest {
    @Test fun summaryAndResultValidationMatchesPythonIncludingTypesAndAsciiBudget() {
        val document=Json.parseToJsonElement(javaClass.getResource("/python-receipt-write.json")!!.readText()).jsonObject
        var checked=0
        for((name,cases) in document) for((index,raw) in cases.jsonArray.withIndex()) {
            val case=raw.jsonObject;val context="$name case $index"
            fun evaluate(): JsonElement=if(name=="summary") normalizeReceiptSummary(case["input"]) else JsonArray(normalizeReceiptResults(case["input"]))
            if("error" in case) assertEquals(case.getValue("error").jsonPrimitive.content,assertFailsWith<IllegalArgumentException>(context) {evaluate()}.message,context)
            else {
                val actual=evaluate();assertEquals(case["value"],actual,context)
                assertEquals(case.getValue("ascii_bytes").jsonPrimitive.int,receiptAsciiJson(actual).length,context)
            }
            checked++
        }
        assertEquals(1052,checked)
    }
}
