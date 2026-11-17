package com.finance.tracker

import com.finance.tracker.business.domain.*
import com.finance.tracker.data.*
import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportPlanReadsTest {
    @Test fun theRealCalculationProvidesTypedPagesAndCompleteEditableDetails() {
        val f = LocalCashImportRebaseFixture(includeRefund = true)
        val frozen = freezeLocalCashImportPreviewPlan(f.original.first)
        val calculation = LocalCashImportCalculation(frozen, f.review, f.request, LocalCashImportReview.forPreview(f.original.first))
        val read = calculation.reader("actual-task-plan")
        read.validateReadable()
        val page = apiJson.decodeFromJsonElement<CashPlanItemPageDto>(read.page("items", indexed = true))
        assertTrue(page.items.isNotEmpty())
        assertEquals(page.items.size, page.totalCount)
        for (item in page.items) {
            val detail = apiJson.decodeFromJsonElement<CashPlanItemDetailDto>(read.detail(item.previewItemId))
            assertEquals(item.previewItemId, detail.previewItemId)
            assertTrue(detail.members.isNotEmpty())
            assertTrue(detail.relationChoices.isNotEmpty())
        }
        assertEquals(frozen, calculation.plan)
        assertFalse("expires_at" in calculation.plan)
        assertFalse("session_revision" in calculation.plan)
    }

    private fun read(rows: JsonArray) = localCashImportPlanReader(buildJsonObject { put("monthly_source", rows) })
    private fun row(time: String, amount: String = "-1.00") = buildJsonObject {
        put("occurred_at", time); put("amount", amount); put("currency", "CNY"); put("economic_type", "expense")
        put("statistics_eligible", true); put("confirmation_status", "confirmed")
    }
    private fun month(reader: LocalCashImportPlanReader, zone: String) = reader.monthlySummaries(zone)
        .getValue("all").jsonArray.single().jsonObject.getValue("month").jsonPrimitive.content

    @Test fun actualPlatformCalendarsKeepMonthBoundariesAndFixedOffsetAliases() {
        val reader = read(buildJsonArray { add(row("2026-04-01T04:30:00Z")) })
        assertEquals("2026-04", month(reader, "America/New_York"))
        assertEquals("2026-03", month(reader, "EST"))
        assertEquals("2026-03", month(reader, "MST"))
        assertEquals("2026-03", month(reader, "HST"))
        assertEquals("2026-04", month(reader, "Asia/Shanghai"))
        val naive = read(buildJsonArray { add(row("2026-03-31T20:30:00")) })
        assertEquals("2026-03", month(naive, "UTC")); assertEquals("2026-04", month(naive, "Asia/Shanghai"))
    }

    @Test fun invalidTimezonesFailEvenWhenThereAreNoRows() {
        val reader = read(JsonArray(emptyList()))
        for (zone in listOf("Not/AZone", "", "UTC" + "x".repeat(126), " UTC", "+08:00"))
            assertEquals("plan_input_invalid", assertFailsWith<CashImportPlanError> { reader.monthlySummaries(zone) }.code)
        assertEquals(JsonArray(emptyList()), reader.monthlySummaries("UTC").getValue("all"))
    }

    @Test fun monthlyTotalsUseAllRowsAndPreserveTheImportDecimalScale() {
        val rows = (1..55).map { row("2026-04-01T00:30:00Z") }
        val result = read(JsonArray(rows)).monthlySummaries("UTC")
        val currencies = result.getValue("all").jsonArray.single().jsonObject.getValue("currencies").jsonArray
        assertEquals("-55.00", currencies.single().jsonObject.getValue("expense").jsonPrimitive.content)
        assertEquals("0", currencies.single().jsonObject.getValue("income").jsonPrimitive.content)
        assertEquals(result["all"], result["confirmed"]); assertEquals(JsonArray(emptyList()), result["pending"])
    }

    @Test fun malformedInternalRowsKeepProgramErrors() {
        for (bad in listOf(row("invalid-time"), JsonObject(row("2026-04-01T00:00:00Z") + ("amount" to JsonPrimitive("invalid-money"))))) {
            val error = assertFails { read(buildJsonArray { add(bad) }).monthlySummaries("UTC") }
            assertFalse(error is CashImportPlanError)
        }
    }
}
