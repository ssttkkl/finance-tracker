package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class LocalCashImportPlanReaderTest {
    private fun golden() = Json.parseToJsonElement(CashImportPlanViewOriginalVectors.json).jsonObject
    private fun reader(plan: JsonObject = golden().getValue("plan").jsonObject) = LocalCashImportPlanReader(plan) { rows, zone ->
        require(zone in setOf("UTC", "Asia/Shanghai"))
        summarizeLocalCashImportMonths(rows) { text ->
            cashLedgerMonthAtOffset(parsePythonCashTimestamp(text, allowNaive = true), if (zone == "UTC") 0 else 28800)
        }
    }
    private fun expected(name: String) = golden().getValue("cases").jsonObject.getValue(name).jsonObject.getValue("result")

    @Test fun publicPagesAndFrozenDetailsMatchTheOriginalOracle() {
        val read = reader()
        val emptyMonthly = buildJsonObject { for (status in listOf("all", "pending", "confirmed")) put(status, JsonArray(emptyList())) }
        assertEquals(JsonObject(expected("view").jsonObject + ("monthly_summaries" to emptyMonthly)), read.view())
        assertEquals(expected("items"), read.page("items"))
        assertEquals(expected("confirmed"), read.page("items", confirmationStatus = "confirmed"))
        assertEquals(expected("indexed"), read.page("items", startIndex = 1, limit = 1, indexed = true))
        assertEquals(expected("processing"), read.page("processing_details", limit = 1))
        val cursor = golden().getValue("cases").jsonObject.getValue("cursor_input").jsonPrimitive.content
        assertEquals(expected("cursor"), read.page("items", cursor = cursor, limit = 1))
        for (id in listOf("it_1", "it_2")) assertEquals(expected("detail_${id.takeLast(1)}"), read.detail(id))
    }

    @Test fun cursorScopeAndPageErrorsRetainTheOriginalCodes() {
        val read = reader()
        fun error(block: () -> Any?) = assertFailsWith<CashImportPlanError>(block = block).code
        val errors = golden().getValue("cases").jsonObject.getValue("errors").jsonObject
        val actual = mapOf("limit_zero" to error { read.page("items", limit = 0) },
            "limit_large" to error { read.page("items", limit = 201) },
            "cursor_garbage" to error { read.page("items", cursor = "not base64!") },
            "start_index" to error { read.page("items", startIndex = 3) },
            "confirmation" to error { read.page("items", confirmationStatus = "invalid") })
        for ((key, code) in actual) assertEquals(errors.getValue(key).jsonObject.getValue("code").jsonPrimitive.content, code)
        val cursor = read.page("items", limit = 1).getValue("next_cursor").jsonPrimitive.content
        assertEquals("plan_cursor_invalid", error { read.page("processing_details", cursor = cursor) })
        assertEquals("plan_cursor_invalid", error { read.page("items", cursor = cursor, confirmationStatus = "pending") })
        assertEquals("plan_item_not_found", error { read.detail("missing") })
    }

    @Test fun allOriginalReadableCasesKeepTheirOutcomeAndDoNotMutateTheInput() {
        val vectors = Json.parseToJsonElement(CashImportReadableOriginalVectors.json).jsonObject
        assertEquals(listOf(91, 6, 11), listOf("path_matrix", "component_identifiers", "boundaries").map { vectors.getValue(it).jsonArray.size })
        for ((_, cases) in vectors) for (raw in cases.jsonArray) {
            val case = raw.jsonObject
            val plan = case.getValue("plan").jsonObject
            val before = plan.toString()
            val read = reader(plan)
            val outcome = try { read.validateReadable(); JsonNull }
            catch (failure: CashImportPlanError) { buildJsonObject { put("type", "CashImportPlanError"); put("args", buildJsonArray { add(failure.code) }) } }
            catch (failure: IllegalArgumentException) { buildJsonObject { put("type", "ValueError"); put("args", buildJsonArray { add(failure.message) }) } }
            assertEquals(case.getValue("outcome"), outcome, "${case["name"]}")
            assertEquals(before, plan.toString())
            if ("resolved" in case) assertEquals(case.getValue("resolved"), read.resolvedDetail("pi_1"))
            assertEquals(before, plan.toString())
        }
    }

    @Test fun completeMonthlyRowsMatchTheUnmodifiedLiteralOracle() {
        val oracle = Json.parseToJsonElement(CashImportMonthlyOriginalVectors.json).jsonObject
        assertEquals(4, oracle.getValue("cases").jsonArray.size)
        for (raw in oracle.getValue("cases").jsonArray) {
            val case = raw.jsonObject
            for ((zone, expected) in case.getValue("expected_by_timezone").jsonObject) {
                val plan = JsonObject(golden().getValue("plan").jsonObject + ("monthly_source" to case.getValue("monthly_source")))
                assertEquals(expected, reader(plan).view(zone).getValue("monthly_summaries"), "${case["name"]}:$zone")
            }
        }
    }

    private fun componentPlan(identifier: JsonElement = JsonPrimitive("9223372036854775808")): JsonObject {
        val detail = buildJsonObject {
            put("entry", buildJsonObject { put("fields", buildJsonObject { }); put("editable_fields", JsonArray(emptyList())) })
            put("members", buildJsonArray { add(buildJsonObject {
                put("row_ref", "f:r1"); put("row_revision", Json.parseToJsonElement("9223372036854775807"))
                put("fields_ref", buildJsonObject { put("operation_ref", "op") })
            }) })
            for (key in listOf("relation_members", "field_diffs", "issues", "relation_choices", "relation_effects")) put(key, JsonArray(emptyList()))
        }
        return buildJsonObject {
            put("plan_id", "actual-plan")
            put("items", buildJsonArray { add(buildJsonObject { put("preview_item_id", "pi_1"); put("display", buildJsonObject { }); put("detail_available", true) }) })
            put("item_details", buildJsonObject { put("pi_1", detail) })
            put("operations", buildJsonArray { add(buildJsonObject {
                put("operation_ref", "op"); put("row_refs", buildJsonArray { add("f:r1") })
                put("values", buildJsonObject {
                    put("source_payload", buildJsonObject { put("exact", Json.parseToJsonElement("1.000000000000000001")); put("huge", Json.parseToJsonElement("9007199254740993")) })
                    put("components", buildJsonArray { add(buildJsonObject { put("component_id", identifier); put("amount", "-1.00") }) })
                })
            }) })
        }
    }

    @Test fun expandedFieldsKeepRawNumbersAndOnlyHideVirtualComponentIdentities() {
        for ((id, hidden) in listOf("9223372036854775807" to false, "9223372036854775808" to true, "１２３" to false)) {
            val source = componentPlan(JsonPrimitive(id)); val before = source.toString(); val read = reader(source)
            read.validateReadable()
            val member = read.detail("pi_1").getValue("members").jsonArray.single().jsonObject
            assertEquals("9223372036854775807", member.getValue("row_revision").jsonPrimitive.content)
            val fields = member.getValue("fields").jsonObject
            assertEquals(Json.parseToJsonElement("1.000000000000000001"), fields.getValue("source_payload").jsonObject["exact"])
            assertEquals(Json.parseToJsonElement("9007199254740993"), fields.getValue("source_payload").jsonObject["huge"])
            assertEquals(!hidden, "component_id" in fields.getValue("components").jsonArray.single().jsonObject)
            assertEquals(before, source.toString())
        }
        for (id in listOf(JsonPrimitive("9".repeat(5000)), Json.parseToJsonElement("9".repeat(5000)), Json.parseToJsonElement("-" + "9".repeat(5000)))) {
            val read = reader(componentPlan(id))
            assertTrue(assertFailsWith<IllegalArgumentException> { read.validateReadable() }.message!!.startsWith("Exceeds the limit (4300 digits)"))
            assertTrue(assertFailsWith<IllegalArgumentException> { read.resolvedDetail("pi_1") }.message!!.startsWith("Exceeds the limit (4300 digits)"))
        }
    }

    @Test fun unicodePlanCursorsRemainBoundAndRejectMalformedOrMixedPositions() {
        val original = golden().getValue("plan").jsonObject
        val source = JsonObject(original + ("plan_id" to JsonPrimitive("真实😀")))
        val read = reader(source)
        val first = read.page("items", limit = 1, indexed = true)
        val cursor = first.getValue("next_cursor").jsonPrimitive.content
        assertEquals(1, read.page("items", cursor = cursor, limit = 1, indexed = true).getValue("start_index").jsonPrimitive.int)
        assertEquals("plan_cursor_invalid", assertFailsWith<CashImportPlanError> { reader(original).page("items", cursor = cursor) }.code)
        assertEquals("plan_page_invalid", assertFailsWith<CashImportPlanError> { read.page("items", cursor = cursor, startIndex = 0) }.code)
        for (invalid in listOf("!", "a", "=".repeat(3), "x".repeat(4097)))
            assertEquals("plan_cursor_invalid", assertFailsWith<CashImportPlanError> { read.page("items", cursor = invalid) }.code)
    }

    @Test fun laterContainerEditsCannotChangeTheFrozenReader() {
        val items = componentPlan().getValue("items").jsonArray.toMutableList()
        val plan = JsonObject(componentPlan() + ("items" to JsonArray(items)))
        val read = reader(plan)
        items.clear()
        read.validateReadable()
        assertEquals(1, read.page("items").getValue("items").jsonArray.size)
        val first = read.detail("pi_1")
        assertEquals(first, read.detail("pi_1"))
    }

    private fun payloadPlan(payload: JsonObject): JsonObject {
        val base = componentPlan()
        val operation = base.getValue("operations").jsonArray.single().jsonObject
        val values = JsonObject(operation.getValue("values").jsonObject + ("source_payload" to payload))
        return JsonObject(base + ("operations" to JsonArray(listOf(JsonObject(operation + ("values" to values))))))
    }
    private fun sourcePayload(read: LocalCashImportPlanReader) = read.detail("pi_1").getValue("members").jsonArray.single()
        .jsonObject.getValue("fields").jsonObject.getValue("source_payload").jsonObject

    @Test fun openJsonNumbersKeepTheirLiteralTokensAtTheReadBoundary() {
        for (token in listOf("-0", "1.000000000000000001", "1E400", "-0.0", "9007199254740993")) {
            val plan = payloadPlan(buildJsonObject { put("number", Json.parseToJsonElement(token)) })
            val read = reader(plan)
            assertEquals(token, sourcePayload(read).getValue("number").jsonPrimitive.content)
            assertEquals(token, plan.getValue("operations").jsonArray.single().jsonObject.getValue("values")
                .jsonObject.getValue("source_payload").jsonObject.getValue("number").jsonPrimitive.content)
        }
    }

    @Test fun escapedLegacyUtf16LeavesDoNotBecomeANewInputRejection() {
        for (unit in intArrayOf(0xd800, 0xdc00)) {
            val text = charArrayOf(unit.toChar()).concatToString()
            assertEquals(unit, text.single().code)
            val plan = payloadPlan(buildJsonObject { put("text", text) })
            val read = reader(plan)
            read.validateReadable()
            assertEquals(unit, sourcePayload(read).getValue("text").jsonPrimitive.content.single().code)
        }
    }

    @Test fun missingMembersGetTheDefaultWhileExplicitNullStillFails() {
        val base = componentPlan()
        val detail = base.getValue("item_details").jsonObject.getValue("pi_1").jsonObject
        val missing = JsonObject(base + ("item_details" to buildJsonObject { put("pi_1", JsonObject(detail - "members")) }))
        val explicitNull = JsonObject(base + ("item_details" to buildJsonObject { put("pi_1", JsonObject(detail + ("members" to JsonNull))) }))
        reader(missing).validateReadable()
        assertEquals(JsonArray(emptyList()), reader(missing).detail("pi_1").getValue("members"))
        assertEquals("plan_build_failed", assertFailsWith<CashImportPlanError> { reader(explicitNull).validateReadable() }.code)
        assertEquals("plan_build_failed", assertFailsWith<CashImportPlanError> { reader(explicitNull).detail("pi_1") }.code)
    }

    @Test fun aRealComputedRefundPlanIsReadableWithItsCompleteChoicesAndDetails() {
        val fixture = LocalCashImportBusinessPlanTest()
        val files = fixture.files()
        val plan = buildLocalCashImportBusinessPlan(files, fixture.source(), fixture.input(files), requireActive = {}) {
            kotlin.time.Instant.parse("2026-10-01T00:00:00Z")
        }
        val frozen = freezeLocalCashImportPreviewPlan(plan)
        val read = reader(JsonObject(frozen + ("plan_id" to JsonPrimitive("actual-local-task-plan"))))
        read.validateReadable()
        val page = read.page("items", indexed = true)
        assertEquals(frozen.getValue("items").jsonArray.size, page.getValue("total_count").jsonPrimitive.int)
        for (raw in page.getValue("items").jsonArray) {
            val item = raw.jsonObject
            val detail = read.detail(item.getValue("preview_item_id").jsonPrimitive.content)
            assertTrue(detail.getValue("members").jsonArray.isNotEmpty())
            assertTrue(detail.getValue("relation_choices").jsonArray.isNotEmpty())
            assertTrue(detail.getValue("members").jsonArray.all { "fields_ref" !in it.jsonObject && "fields" in it.jsonObject })
        }
        assertEquals(frozen, freezeLocalCashImportPreviewPlan(plan))
    }
}
