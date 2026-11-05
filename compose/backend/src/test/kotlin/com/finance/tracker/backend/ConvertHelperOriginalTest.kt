package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class ConvertHelperOriginalTest {
    private fun cases() = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/original-convert-helper-assertions.json"))
        .bufferedReader().use { it.readText() }).jsonArray
    @Test fun originalPlatformAndPrefixAssertionsKeepEveryCallAndOperator() {
        val cases = cases().filter { isPlatformOrPrefix(it) }
        assertEquals(72, cases.size)
        assertEquals(72, cases.map { it.jsonObject.getValue("source") }.toSet().size)
        assertEquals(87, cases.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        verify(cases)
    }
    @Test fun originalIcbcPaymentAndBrandPriorityKeepEveryCall() {
        val all = cases()
        assertEquals(81, all.size)
        assertEquals(81, all.map { it.jsonObject.getValue("source") }.toSet().size)
        assertEquals(98, all.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        val cases = all.filterNot { isPlatformOrPrefix(it) }
        assertEquals(9, cases.size)
        assertEquals(11, cases.sumOf { it.jsonObject.getValue("checks").jsonArray.size })
        verify(cases)
    }
    private fun isPlatformOrPrefix(case: JsonElement): Boolean = case.jsonObject.getValue("source").jsonPrimitive.content
        .split("::")[1] in setOf("TestInferPlatform", "TestPlatformEdgeCases", "TestStripPaymentPrefix")
    private fun verify(cases: List<JsonElement>) {
        for (case in cases) {
            val source = case.jsonObject.getValue("source").jsonPrimitive.content
            for (check in case.jsonObject.getValue("checks").jsonArray) {
                val item = check.jsonObject
                val args = item.getValue("args").jsonArray.map { it.jsonPrimitive.content }
                val actual = when (item.getValue("function").jsonPrimitive.content) {
                    "_infer_platform" -> { assertEquals(3, args.size); inferCashPlatform(args[0], args[1]) }
                    "_strip_payment_prefix" -> stripCashPaymentPrefix(args.single())
                    "_infer_payment_source" -> {
                        assertEquals(3, args.size); assertEquals("icbc", args[0]); inferIcbcPayment(args[1], args[2])
                    }
                    else -> error("Unexpected original helper")
                }
                val expected = item.getValue("expected").jsonPrimitive.content
                when (item.getValue("operator").jsonPrimitive.content) {
                    "eq" -> assertEquals(expected, actual, source)
                    "ne" -> assertNotEquals(expected, actual, source)
                    else -> error("Unexpected original assertion")
                }
            }
        }
    }
}
