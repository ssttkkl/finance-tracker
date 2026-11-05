package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanEffectsOriginalTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun effects(changes: String, request: String = "{}", baseline: List<Triple<String, String, String>> = emptyList()): JsonArray {
        val input = obj(request)
        val intent = input["intent"] ?: JsonPrimitive("edit_entry")
        val base = buildJsonObject {
            put("intent", intent); put("transaction_id", "11"); put("expected_revision", 1)
            if (intent == JsonPrimitive("edit_entry")) {
                put("entry_id", "effect-entry"); put("expected_entry_revision", 1)
                put("patch", obj("""{"note":""}"""))
            }
        }
        val result = obj("""{"operations":[],"relation_groups":[],"relation_failures":[],"type_effects":{},"invalidated_choices":[]}""")
        return MutationPlanEffects.additionalEffects(MutationPlanRequest.parse(JsonObject(base + input)), JsonObject(result + obj(changes)), baseline)
    }
    private fun kinds(value: JsonArray) = value.map { it.jsonObject.getValue("kind").jsonPrimitive.content }.toSet()

    @Test fun commonClearAndExistingAttentionDoNotRequestAnotherConfirmation() {
        assertEquals(JsonArray(emptyList()), effects("""{"operations":[{"transaction_id":"11","field_diffs":[{"field":"note","incoming":"","effective":""}],"issues":[{"code":"account_missing","field_path":"account_id"}],"expected_issues":[{"code":"account_missing","field_path":"account_id"}]}],"relation_groups":[{"suggestion_id":"sg_keep","origin":"current","changed":false,"action":"accept"}]}"""))
    }
    @Test fun effectiveValueDeviatingFromUserChoiceIsReportedWithoutValues() {
        val result = effects("""{"operations":[{"transaction_id":"11","field_diffs":[{"field":"note","incoming":"private request","effective":"other private"}]}]}""")
        assertEquals(Json.parseToJsonElement("""[{"kind":"field_value_changed","reference":"11","field":"note"}]"""), result)
        assertFalse(result.toString().contains("private"))
    }
    @Test fun newRelationAndAttentionAreNotHiddenByEmptyFirstPage() {
        assertEquals(setOf("relation_changed", "new_attention"), kinds(effects("""{"items":[],"pages":{"items":"next"},"relation_groups":[{"suggestion_id":"sg_new","action":"accept","changed":true}],"operations":[{"transaction_id":"11","issues":[{"code":"amount_unreadable","field_path":"amount"}]}]}""")))
    }
    @Test fun explicitGroupRemovalIsNotConfirmedTwiceButUnrequestedFundingRemovalIs() {
        val result = effects("""{"relation_groups":[{"suggestion_id":"sg_one","action":"remove","changed":true,"expected_record_edges":[{"id":31}]}],"type_effects":{"removed_relation_ids":[31],"removed_funding_ids":[45]}}""",
            """{"intent":"maintain_relations","relation_choices":[{"suggestion_id":"sg_one","action":"remove"}]}""")
        assertEquals(Json.parseToJsonElement("""[{"kind":"funding_relation_removed","reference":"45"}]"""), result)
    }
    @Test fun unrequestedRelationRemovalAndInvalidatedChoiceAreReported() {
        assertEquals(setOf("relation_changed", "choice_changed"), kinds(effects("""{"type_effects":{"removed_relation_ids":[31]},"invalidated_choices":[{"kind":"relation","reference":"sg_old","code":"relation_context_changed"}]}""")))
    }
    @Test fun explicitAcceptBecomingDeferredIsAnAdditionalEffect() {
        assertEquals(Json.parseToJsonElement("""[{"kind":"choice_changed","reference":"sg_one"}]"""),
            effects("""{"relation_groups":[{"suggestion_id":"sg_one","action":"defer","changed":true}]}""",
                """{"relation_choices":[{"suggestion_id":"sg_one","action":"accept"}]}"""))
    }
    @Test fun transferDerivedFieldsAndExplicitRelationDecisionsAreNotExtra() {
        assertEquals(JsonArray(emptyList()), effects("""{"operations":[{"transaction_id":"11","field_diffs":[{"field":"components","incoming":[],"effective":[]}]}],"relation_groups":[{"suggestion_id":"sg_one","action":"accept","changed":true}]}""",
            """{"intent":"edit_entry","member_patches":[{"transaction_id":"11","patch":{"amount":"10.00"}}],"relation_choices":[{"suggestion_id":"sg_one","action":"accept"}]}"""))
    }
    @Test fun collateralMemberExistingDiscoveryIssueIsNotNewButAnotherFailureIs() {
        val key = "relation:a3b8183ff9aa5015984034bdfa1cbaed1a7f3ddf47f9e03d7a219062637b1e3c"
        val baseline = listOf(Triple("11", key, "relation_discovery_failed"), Triple("12", key, "relation_discovery_failed"))
        assertEquals(JsonArray(emptyList()), effects("""{"relation_failures":[{"code":"relation_discovery_failed","parent_ids":["11","12"]}]}""", baseline = baseline))
        assertTrue(effects("""{"relation_failures":[{"code":"relation_discovery_failed","parent_ids":["12","13"]}]}""", baseline = baseline).isNotEmpty())
    }
    @Test fun skippedCurrentGroupHasOneWholeGroupEffectWithoutParentIssueCopies() {
        assertEquals(Json.parseToJsonElement("""[{"kind":"relation_changed","reference":"sg_one"}]"""),
            effects("""{"relation_groups":[{"suggestion_id":"sg_one","origin":"current","changed":false,"action":"skip"}],"relation_failures":[{"suggestion_id":"sg_one","code":"some_failure","parent_ids":["11"]}]}"""))
    }
}
