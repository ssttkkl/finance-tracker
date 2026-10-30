package com.finance.tracker.backend

import java.math.BigInteger
import java.time.Instant
import kotlin.test.*
import kotlinx.serialization.json.*

class CashImportChoiceContextTest {
    private val CONTEXT_KEY = CashImportChoiceContext.CONTEXT_KEY

    private fun golden(): JsonArray = Json.parseToJsonElement(javaClass.getResourceAsStream("/python-cash-import-context.json")!!
        .bufferedReader().use { it.readText() }).jsonObject.getValue("choices").jsonArray

    @Suppress("UNCHECKED_CAST")
    private fun sessionOf(contextData: Map<String, Any?> = emptyMap(), plan: Map<String, Any?>? = null) =
        ParsedImportSession("session", "workspace", "user", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
            BigInteger.ONE, emptyList(), contextData = contextData, plan = plan)

    @Suppress("UNCHECKED_CAST")
    private fun map(value: JsonElement?): Map<String, Any?>? = value?.takeIf { it != JsonNull }?.let {
        cashSourceValue(it) as Map<String, Any?>
    }

    @Test fun choiceContextsMatchPythonContracts() {
        val cases = golden()
        assertEquals(36, cases.size)
        for ((index, raw) in cases.withIndex()) {
            val item = raw.jsonObject
            when (item.getValue("kind").jsonPrimitive.content) {
                "validated" -> {
                    val result = runCatching { CashImportChoiceContext.validated(cashSourceValue(item.getValue("input"))) }
                    if (item["expected"] == null) {
                        assertTrue(result.isFailure, "case $index should be invalid")
                        assertIs<CashImportChoiceContextError>(result.exceptionOrNull(), "case $index")
                    } else {
                        assertEquals(item.getValue("expected"), sourceJson(result.getOrThrow()), "case $index")
                    }
                }
                "from_plan" -> assertEquals(item.getValue("expected"),
                    sourceJson(CashImportChoiceContext.fromPlan(map(item.getValue("input"))!!)), "case $index")
                "retained" -> assertEquals(item.getValue("expected"),
                    sourceJson(CashImportChoiceContext.retained(sessionOf(map(item["context"])!!, map(item["plan"])))), "case $index")
                "retained_invalid" -> assertFailsWith<CashImportChoiceContextError>("case $index") {
                    CashImportChoiceContext.retained(sessionOf(map(item["context"])!!, map(item["plan"])))
                }
                "previous" -> assertEquals(item.getValue("expected"),
                    sourceJson(CashImportChoiceContext.previous(sessionOf(map(item["context"])!!))!!), "case $index")
                "previous_invalid" -> assertFailsWith<CashImportChoiceContextError>("case $index") {
                    CashImportChoiceContext.previous(sessionOf(map(item["context"])!!))
                }
                else -> fail("unknown golden kind at case $index")
            }
        }
    }

    @Test fun latestPlanReplacesTheSingleProofInsteadOfBuildingHistory() {
        val prior = CashImportChoiceContext.retained(sessionOf(mapOf("other" to 1),
            mapOf("operations" to emptyList<Any?>(), "relation_groups" to emptyList<Any?>())))
        val latest = CashImportChoiceContext.retained(sessionOf(prior,
            mapOf("operations" to emptyList<Any?>(), "relation_groups" to emptyList<Any?>())))
        assertEquals(1, latest.keys.count { it == CONTEXT_KEY })
        assertEquals(emptyList<Any?>(), (latest[CONTEXT_KEY] as Map<*, *>)["relation_groups"])
        assertFalse(sourceJson(latest).toString().contains("private"))
    }

    @Test fun commonDecisionSurvivesRepeatedRestore() {
        val common = mapOf("note" to "", "counterparty" to "共同对方", "category_id" to null)
        val plan = mapOf("operations" to emptyList<Any?>(), "relation_groups" to emptyList<Any?>(),
            "invalidated_relation_choices" to mapOf("sg_stale" to mapOf(
                "choice" to mapOf("suggestion_id" to "sg_stale", "action" to "accept", "common_fields" to common),
                "member_refs" to listOf(mapOf("row_ref" to "f:r1")))),
            "relation_choice_generations" to emptyMap<String, Any?>())
        var current = sessionOf(emptyMap(), plan)
        repeat(3) {
            val retained = CashImportChoiceContext.retained(current)
            val choice = ((retained[CONTEXT_KEY] as Map<*, *>)["invalidated_relation_choices"] as Map<*, *>)["sg_stale"] as Map<*, *>
            assertEquals(common, (choice["choice"] as Map<*, *>)["common_fields"])
            assertEquals(choice, CashImportChoiceContext.previous(sessionOf(retained))!!.let { proof ->
                (proof["invalidated_relation_choices"] as Map<*, *>)["sg_stale"]
            })
            current = sessionOf(retained)
        }
    }

    @Test fun previousAndWithoutSeparateChoiceProof() {
        val context = golden().first { it.jsonObject.getValue("kind").jsonPrimitive.content == "validated" &&
            it.jsonObject["expected"] != null }.jsonObject.let { map(it.getValue("input"))!! }
        val current = sessionOf(mapOf(CONTEXT_KEY to context, "other" to 1))
        assertEquals(sourceJson(context), sourceJson(CashImportChoiceContext.previous(current)!!))
        assertEquals(mapOf("other" to 1), CashImportChoiceContext.without(current))
        assertNull(CashImportChoiceContext.previous(sessionOf(mapOf("other" to 1))))
        assertFailsWith<CashImportChoiceContextError> {
            CashImportChoiceContext.previous(sessionOf(mapOf(CONTEXT_KEY to mapOf("version" to 2))))
        }
    }
}
