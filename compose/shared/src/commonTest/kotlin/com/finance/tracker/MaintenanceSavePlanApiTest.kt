package com.finance.tracker

import com.finance.tracker.core.TokenStore
import com.finance.tracker.data.*
import com.finance.tracker.domain.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class MaintenanceSavePlanApiTest {
    @Test fun ordinarySaveSendsDraftIntentAndPreservesExplicitClear() = runTest {
        var sent: JsonObject? = null
        val api = api { request -> sent = apiJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject; PLAN }
        val repo = KtorCashMaintenanceRepository(api)
        repo.preview(CashMaintenanceRequest("save_draft", "11", 3, StructuredObject(mapOf("note" to StructuredText(""), "category_id" to StructuredNull))))
        assertEquals("save_draft", sent!!["intent"]!!.jsonPrimitive.content)
        assertEquals("", sent!!["patch"]!!.jsonObject["note"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, sent!!["patch"]!!.jsonObject["category_id"])
        assertFalse("issue_resolutions" in sent!!)
        api.close()
    }
    @Test fun draftSaveRejectsHiddenSourceOrRelationDecisionsBeforeSending() = runTest {
        var calls = 0
        val api = api { calls++; PLAN }
        val repo = KtorCashMaintenanceRepository(api)
        for (request in listOf(CashMaintenanceRequest("save_draft", "11", 3, issueResolutions = listOf(CashIssueResolution("31", 1, "keep_existing"))),
            CashMaintenanceRequest("save_draft", "11", 3, relationChoices = listOf(CashRelationDecision("sg", "defer"))))) {
            assertFailsWith<DomainFailure> { repo.preview(request) }
        }
        assertEquals(0, calls)
        api.close()
    }
    @Test fun absentEmptyAndConcreteEffectsRemainDistinctInDomain() = runTest {
        for (value in listOf<JsonElement?>(null, JsonNull, JsonArray(emptyList()), buildJsonArray { add(buildJsonObject { put("kind", "funding_relation_removed"); put("reference", "9007199254740993") }) })) {
            val response = JsonObject(apiJson.parseToJsonElement(PLAN).jsonObject + if (value == null) emptyMap() else mapOf("additional_effects" to value))
            val api = api { response.toString() }
            val plan = KtorCashMaintenanceRepository(api).preview(CashMaintenanceRequest("save_draft", "11", 3))
            when (value) {
                null, JsonNull -> assertNull(plan.additionalEffects)
                is JsonArray -> if (value.isEmpty()) assertEquals(emptyList(), plan.additionalEffects)
                    else assertEquals(listOf(CashAdditionalEffect("funding_relation_removed", "9007199254740993")), plan.additionalEffects)
                else -> error("unexpected fixture")
            }
            api.close()
        }
    }
    private fun api(body: suspend (io.ktor.client.request.HttpRequestData) -> String) = FinanceApiClient({ "https://api.example.test" }, object : TokenStore {
        override suspend fun get() = "test-token"
        override suspend fun set(value: String) = Unit
        override suspend fun clear() = Unit
    }, MockEngine { respond(body(it), headers = headersOf(HttpHeaders.ContentType, "application/json")) })
}
private const val PLAN = """{"plan_id":"mp_one","edit_context_id":"context","plan_revision":1,"expires_at":"2099-01-01T00:00:00Z","summary":{},"item_counts":{"all":0,"pending":0},"can_commit":true,"items":[],"pages":{"items":null},"invalidated_choices":[]}"""
