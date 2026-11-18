package com.finance.tracker.business.domain

import kotlinx.serialization.json.*
import kotlin.test.*

class CashImportRelationChoiceRulesTest {
    @Test fun everyOriginalFrozenChoiceCaseKeepsItsCompleteResultOrError() {
        val cases = Json.parseToJsonElement(CashRelationChoiceOriginalVectors.json).jsonArray
        assertEquals(18, cases.size)
        for (raw in cases) {
            val case = raw.jsonObject
            val groups = case.getValue("groups").jsonArray
            val choices = case.getValue("choices").jsonArray
            val previous = case.getValue("previous").takeUnless { it == JsonNull }?.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val beforeGroups = groups.toString(); val beforeChoices = choices.toString(); val beforePrevious = previous?.toString()
            if ("error" in case) assertEquals(case.getValue("error").jsonPrimitive.content,
                assertFailsWith<CashImportPlanError>(name) { applyLocalCashImportRelationChoices(groups, choices, previous) }.code)
            else assertEquals(case.getValue("expected"), applyLocalCashImportRelationChoices(groups, choices, previous), name)
            assertEquals(beforeGroups, groups.toString()); assertEquals(beforeChoices, choices.toString()); assertEquals(beforePrevious, previous?.toString())
        }
    }
    @Test fun anExplicitCurrentChoiceWinsAStoredInvalidatedChoiceInEitherOrder() {
        val stale = buildJsonObject { put("suggestion_id", "old"); put("action", "accept") }
        val member = buildJsonObject { put("transaction_id", "t1") }
        val group = buildJsonObject {
            put("suggestion_id", "new"); put("action", "accept"); put("origin", "discovered")
            put("allowed_actions", JsonArray(listOf("accept", "defer", "ignore").map(::JsonPrimitive)))
            put("member_refs", JsonArray(listOf(member))); put("edges", JsonArray(emptyList())); put("candidates", JsonArray(emptyList()))
        }
        val groups = JsonArray(listOf(group))
        val previous = buildJsonObject {
            put("relation_groups", groups)
            put("invalidated_relation_choices", buildJsonObject { put("old", buildJsonObject {
                put("choice", stale); put("member_refs", JsonArray(listOf(member)))
            }) })
        }
        for (action in listOf("accept", "ignore")) for (oldFirst in listOf(true, false)) {
            val current = buildJsonObject { put("suggestion_id", "new"); put("action", action) }
            val choices = JsonArray(if (oldFirst) listOf(stale, current) else listOf(current, stale))
            val result = applyLocalCashImportRelationChoices(groups, choices, previous)
            assertEquals(action, result.getValue("groups").jsonArray.single().jsonObject.getValue("action").jsonPrimitive.content)
            assertEquals(1, result.getValue("invalidated").jsonArray.size)
        }
    }
    @Test fun generationIsAnExactUnboundedIntegerRatherThanAnAmountOrLedgerSequence() {
        val case = Json.parseToJsonElement(CashRelationChoiceOriginalVectors.json).jsonArray.map { it.jsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "huge_generation" }
        val result = applyLocalCashImportRelationChoices(case.getValue("groups").jsonArray, case.getValue("choices").jsonArray,
            case.getValue("previous").jsonObject)
        assertEquals("1208925819614629174706176", result.getValue("generations").jsonObject.values.single().jsonPrimitive.content)
        assertEquals(case.getValue("expected"), result)
    }
}
