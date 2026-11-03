package com.finance.tracker.backend

import java.math.BigInteger
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportReadableOriginalTest {
    private val fallback = object : CashImportPlanViewEngine() {
        override fun independentDetail(value: Any?, operations: Map<String, Map<String, Any?>>) = false
    }
    private fun outcome(reader: CashImportPlanViewEngine, plan: Map<String, Any?>): JsonElement = try {
        reader.validateReadable(plan)
        JsonNull
    } catch (failure: CashImportPlanError) {
        buildJsonObject { put("type", "CashImportPlanError"); put("args", JsonArray(listOf(JsonPrimitive(failure.code)))) }
    } catch (failure: IllegalArgumentException) {
        buildJsonObject { put("type", "ValueError"); put("args", JsonArray(listOf(JsonPrimitive(failure.message)))) }
    }
    private fun verifyOriginalCases(key: String, count: Int) {
        val cases = Json.parseToJsonElement(javaClass.getResource("/python-cash-import-readable-original.json")!!.readText())
            .jsonObject.getValue(key).jsonArray
        assertEquals(count, cases.size)
        val mismatches = mutableListOf<String>()
        for (raw in cases) {
            val case = raw.jsonObject
            @Suppress("UNCHECKED_CAST") val plan = cashSourceValue(case.getValue("plan")) as Map<String, Any?>
            val before = sourceJson(plan)
            val fast = outcome(CashImportPlanViews, plan)
            if (case.getValue("outcome") != fast) mismatches += "${case.getValue("name")}: expected ${case.getValue("outcome")}, got $fast"
            assertEquals(before, sourceJson(plan))
            assertEquals(fast, outcome(fallback, plan))
            assertEquals(before, sourceJson(plan))
            if ("resolved" in case) {
                val detail = (plan["item_details"] as Map<*, *>)["pi_1"]
                assertEquals(case.getValue("resolved"), sourceJson(CashImportPlanViews.resolveDetail(plan, detail)))
                assertEquals(before, sourceJson(plan))
            }
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }
    @Test fun independentReadableMatchesOriginalPath() = verifyOriginalCases("path_matrix", 91)
    @Test fun componentConversionErrorsMatchOriginal() = verifyOriginalCases("component_identifiers", 6)
    @Test fun numericBoundariesAndStructuralErrorOrderMatchPython() = verifyOriginalCases("boundaries", 11)
    private fun plan(identifier: Any? = "9223372036854775809"): Map<String, Any?> = mapOf(
        "items" to listOf(mapOf("preview_item_id" to "pi_1", "display" to emptyMap<String, Any?>(), "detail_available" to true)),
        "operations" to listOf(mapOf("operation_ref" to "op_1", "row_refs" to listOf("a:r1"), "transaction_id" to null,
            "values" to mapOf("components" to listOf(mapOf("component_id" to identifier))))),
        "item_details" to mapOf("pi_1" to mapOf("entry" to mapOf("fields" to emptyMap<String, Any?>()),
            "members" to listOf(mapOf("row_ref" to "a:r1", "fields_ref" to mapOf("operation_ref" to "op_1"))),
            "relation_members" to emptyList<Any?>(), "field_diffs" to emptyList<Any?>(), "issues" to emptyList<Any?>(),
            "relation_choices" to emptyList<Any?>(), "relation_effects" to emptyList<Any?>())))
    @Test fun oversizedComponentIdentifierIsRejectedInFastAndResolvedPaths() {
        val plan = plan("9".repeat(5000))
        val message = "Exceeds the limit (4300 digits) for integer string conversion: value has 5000 digits; use sys.set_int_max_str_digits() to increase the limit"
        assertEquals(message, assertFailsWith<IllegalArgumentException> { CashImportPlanViews.validateReadable(plan) }.message)
        val detail = (plan["item_details"] as Map<*, *>)["pi_1"]
        assertEquals(message, assertFailsWith<IllegalArgumentException> { CashImportPlanViews.resolveDetail(plan, detail) }.message)
    }
    @Test fun independentReadableValidationDoesNotAllocatePublicDetails() {
        var calls = 0
        val reader = object : CashImportPlanViewEngine() {
            override fun resolveDetail(plan: Map<String, Any?>, value: Any?,
                operations: Map<String, Map<String, Any?>>, copyValues: Boolean): Map<String, Any?> {
                calls++
                error("unnecessary detail reconstruction")
            }
        }
        reader.validateReadable(plan())
        assertEquals(0, calls)
    }
    @Test fun fallbackValidationCopiesContainersWithoutCopyingLeafValues() {
        val modes = mutableListOf<Boolean>()
        val reader = object : CashImportPlanViewEngine() {
            override fun independentDetail(value: Any?, operations: Map<String, Map<String, Any?>>) = false
            override fun resolveDetail(plan: Map<String, Any?>, value: Any?,
                operations: Map<String, Map<String, Any?>>, copyValues: Boolean): Map<String, Any?> {
                modes += copyValues
                return super.resolveDetail(plan, value, operations, copyValues)
            }
        }
        val plan = plan()
        val before = sourceJson(plan)
        reader.validateReadable(plan)
        assertEquals(listOf(false), modes)
        assertEquals(before, sourceJson(plan))
    }
    @Test fun hugeIntegerStringificationRejectsBothSignsBeforeDigitTesting() {
        val message = "Exceeds the limit (4300 digits) for integer string conversion; use sys.set_int_max_str_digits() to increase the limit"
        for (value in listOf(BigInteger.TEN.pow(4300), -BigInteger.TEN.pow(4300))) {
            for (reader in listOf(CashImportPlanViews, fallback)) {
                assertEquals(message, assertFailsWith<IllegalArgumentException> { reader.validateReadable(plan(value)) }.message)
            }
        }
        for (value in listOf(BigInteger.TEN.pow(4299), -BigInteger.TEN.pow(4299))) {
            assertEquals(JsonNull, outcome(CashImportPlanViews, plan(value)))
            assertEquals(JsonNull, outcome(fallback, plan(value)))
        }
    }
    @Test fun ordinaryStringLookupAndDuplicateReferencesKeepDefaultInstanceIsolated() {
        val valid = plan()
        assertEquals(JsonNull, outcome(CashImportPlanViews, valid))
        assertEquals(JsonNull, outcome(fallback, valid))
        val operation = (valid["operations"] as List<*>).single()
        val invalid = valid + ("operations" to listOf(operation, operation))
        for (reader in listOf(CashImportPlanViews, fallback)) {
            assertEquals("plan_build_failed", assertFailsWith<CashImportPlanError> { reader.validateReadable(invalid) }.code)
        }
        CashImportPlanViews.validateReadable(valid)
    }
}
