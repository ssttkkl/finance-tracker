package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportMonthlySummariesTest {
    private val oracle = Json.parseToJsonElement(javaClass.getResourceAsStream("/cash-import-monthly-oracle.json")!!
        .bufferedReader().use { it.readText() }).jsonObject

    @Test fun completeFrozenRowsMatchIndependentLiteralMonthlyOracle() {
        for (raw in oracle.getValue("cases").jsonArray) {
            val case = raw.jsonObject
            val rows = cashSourceValue(case.getValue("monthly_source")) as List<*>
            for ((zone, expected) in case.getValue("expected_by_timezone").jsonObject)
                assertEquals(expected, sourceJson(CashImportMonthlySummaries.summarize(rows, zone)), "${case["name"]} $zone")
        }
    }

    @Test fun invalidTimezoneFailsEvenWithNoRowsAndMalformedInternalRowsStayProgramErrors() {
        for (raw in oracle.getValue("invalid_timezone").jsonArray) {
            val case = raw.jsonObject
            assertEquals(case.getValue("expected_error").jsonPrimitive.content,
                assertFailsWith<CashImportPlanError> { CashImportMonthlySummaries.summarize(emptyList<Any?>(), cashSourceValue(case["input"])) }.code)
        }
        val row = mapOf("occurred_at" to "2026-05-01T00:00:00Z", "amount" to "invalid-money", "currency" to "CNY",
            "statistics_eligible" to true, "economic_type" to "income", "confirmation_status" to "confirmed")
        assertFailsWith<NumberFormatException> { CashImportMonthlySummaries.summarize(listOf(row)) }
        assertFailsWith<java.time.format.DateTimeParseException> { CashImportMonthlySummaries.summarize(listOf(row + ("occurred_at" to "invalid-time"))) }
    }
}
