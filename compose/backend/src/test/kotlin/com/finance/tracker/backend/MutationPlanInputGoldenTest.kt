package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class MutationPlanInputGoldenTest {
    @Test fun validationMatchesEveryPythonCase() {
        val cases = Json.parseToJsonElement(requireNotNull(javaClass.getResourceAsStream("/python-mutation-plan-inputs.json"))
            .bufferedReader().use { it.readText() }).jsonArray
        assertTrue(cases.size >= 30)
        for ((index, raw) in cases.withIndex()) {
            val case = raw.jsonObject
            val input = case.getValue("input")
            val result = runCatching { MutationPlanRequest.parse(input) }
            if (!case.getValue("ok").jsonPrimitive.boolean) {
                val failure = result.exceptionOrNull()
                assertIs<MutationPlanFailure>(failure, "case $index")
                assertEquals(case.getValue("error").jsonPrimitive.content, failure.code, "case $index $input")
                continue
            }
            val parsed = result.getOrThrow()
            val data = case.getValue("data").jsonObject
            assertEquals(data.getValue("intent").jsonPrimitive.content, parsed.intent, "case $index")
            assertEquals(data.getValue("transaction_id").jsonPrimitive.content, parsed.transactionId, "case $index")
            assertEquals(data.getValue("expected_revision").jsonPrimitive.long, parsed.expectedRevision, "case $index")
            assertEquals(data.getValue("patch").jsonObject, parsed.patch, "case $index")
            assertEquals(data.getValue("relation_choices").jsonArray, JsonArray(parsed.relationChoices), "case $index")
            assertEquals(data.getValue("issue_resolutions").jsonArray, issueJson(parsed), "case $index")
            assertEquals(data["entry_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content, parsed.entryId, "case $index")
            assertEquals(data["expected_entry_revision"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long,
                parsed.expectedEntryRevision, "case $index")
            assertEquals(data["relation_additions"]?.jsonArray ?: JsonArray(emptyList()),
                JsonArray(parsed.relationAdditions), "case $index")
            val members = data["member_patches"]?.jsonArray ?: JsonArray(emptyList())
            assertEquals(members.map { it.jsonObject.getValue("transaction_id").jsonPrimitive.content },
                parsed.memberPatches.map { it.transactionId }, "case $index")
            assertEquals(members.map { it.jsonObject.getValue("patch").jsonObject },
                parsed.memberPatches.map { it.patch }, "case $index")
            val metadata = case.getValue("metadata").jsonObject
            assertEquals(metadata["edit_context_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                parsed.metadata.editContextId, "case $index")
            assertEquals(metadata["previous_plan_id"]?.takeIf { it != JsonNull }?.jsonPrimitive?.content,
                parsed.metadata.previousPlanId, "case $index")
            assertEquals(metadata["expected_plan_revision"]?.takeIf { it != JsonNull }?.jsonPrimitive?.long,
                parsed.metadata.expectedPlanRevision, "case $index")
        }
    }

    private fun issueJson(parsed: MutationPlanRequest) = JsonArray(parsed.issueResolutions.map { resolution ->
        buildJsonObject {
            put("issue_id", resolution.issueId)
            put("expected_issue_revision", resolution.expectedIssueRevision)
            put("resolution", resolution.resolution)
            resolution.targetTransactionId?.let { put("target_transaction_id", it) }
            resolution.expectedTargetRevision?.let { put("expected_target_revision", it) }
        }
    })
}
