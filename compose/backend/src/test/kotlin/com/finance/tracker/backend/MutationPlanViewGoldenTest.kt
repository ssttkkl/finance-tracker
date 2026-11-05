package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanViewGoldenTest {
    @Test fun frozenPlanViewPagingAndItemMatchPython() {
        val fixture = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-mutation-plan-view.json"))
            .bufferedReader().use { it.readText() }).jsonObject
        val plan = fixture.getValue("plan").jsonObject
        MutationPlanView.validateBuild(plan)
        val checks = fixture.getValue("checks").jsonArray
        assertEquals(10, checks.size)
        for ((index, raw) in checks.withIndex()) {
            val check = raw.jsonObject
            val result = when (check.getValue("kind").jsonPrimitive.content) {
                "view" -> runCatching { MutationPlanView.view(plan) }
                "page" -> {
                    val args = check["args"]?.jsonObject
                    runCatching {
                        MutationPlanView.page(plan, "items",
                            (check["cursor"] ?: args?.get("cursor"))?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                            (check["limit"] ?: args?.get("limit"))?.let { it.jsonPrimitive.int } ?: 50,
                            (check["confirmation_status"] ?: args?.get("confirmation_status"))?.takeIf { it != JsonNull }?.jsonPrimitive?.content)
                    }
                }
                "item" -> {
                    val identifier = check["id"] ?: check["args"]?.jsonObject?.get("id")
                    runCatching { MutationPlanView.item(plan, identifier?.jsonPrimitive?.content) }
                }
                else -> error("unknown check")
            }
            if ("expected_error" in check) {
                val failure = result.exceptionOrNull()
                assertIs<MutationPlanFailure>(failure, "case $index $check")
                assertEquals(check.getValue("expected_error").jsonPrimitive.content, failure.code, "case $index")
            } else {
                assertEquals(check.getValue("expected"), result.getOrThrow(), "case $index $check")
            }
        }
    }
}
