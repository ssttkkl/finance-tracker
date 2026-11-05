package com.finance.tracker.backend

import kotlin.test.*
import kotlinx.serialization.json.*

class CashSourceIssueInputOriginalTest {
    private fun choice(identifier: JsonElement = JsonPrimitive("2"),
        revision: JsonElement = JsonPrimitive(1), resolution: JsonElement = JsonPrimitive("keep_existing")) =
        buildJsonObject {
            put("issue_id", identifier)
            put("expected_issue_revision", revision)
            put("resolution", resolution)
        }

    private fun value(changes: JsonObject = JsonObject(emptyMap())) = JsonObject(buildJsonObject {
        put("intent", "resolve_issue")
        put("transaction_id", "1")
        put("expected_revision", 1)
        put("issue_resolutions", JsonArray(listOf(choice())))
    } + changes)

    @Test fun sourceChoicesCanShareEachOriginalRecordIntent() {
        for (intent in listOf("edit_record", "resolve_issue", "finalize_draft")) {
            val parsed = MutationPlanRequest.parse(value(buildJsonObject { put("intent", intent) }))
            assertEquals(listOf(MutationIssueResolution("2", 1, "keep_existing")), parsed.issueResolutions, intent)
        }
    }

    @Test fun invalidSourceChoiceShapesAreRejectedWithoutDatabaseAccess() {
        fun choices(vararg values: JsonElement) = buildJsonObject { put("issue_resolutions", JsonArray(values.toList())) }
        val invalid = listOf(
            buildJsonObject { put("issue_resolutions", JsonNull) },
            buildJsonObject { put("issue_resolutions", JsonObject(emptyMap())) },
            choices(),
            choices(JsonPrimitive(true)),
            choices(choice(revision = JsonPrimitive(true))),
            choices(choice(revision = JsonPrimitive(1.0))),
            choices(choice(identifier = JsonPrimitive(true))),
            choices(choice(revision = JsonPrimitive(0))),
            choices(choice(revision = Json.parseToJsonElement("9223372036854775808"))),
            choices(choice(resolution = JsonPrimitive("merge_into"))),
            choices(choice(resolution = JsonArray(emptyList()))),
            choices(JsonObject(choice() + ("target_transaction_id" to JsonNull))),
            choices(JsonObject(choice() + ("pending_fields" to JsonObject(emptyMap())))),
            buildJsonObject { put("issue_resolutions", JsonArray(List(101) { choice() })) },
            buildJsonObject { put("intent", "maintain_relations") },
            choice(),
        )
        assertEquals(16, invalid.size)
        for ((index, changes) in invalid.withIndex()) {
            assertEquals("plan_input_invalid", assertFailsWith<MutationPlanFailure>("case $index") {
                MutationPlanRequest.parse(value(changes))
            }.code)
        }
    }

    @Test fun normalizedDuplicateIssueIdsAreNotOrderedDecisions() {
        val choices = JsonArray(listOf("2", "02").map { choice(identifier = JsonPrimitive(it)) })
        assertFailsWith<MutationPlanFailure> {
            MutationPlanRequest.parse(value(buildJsonObject { put("issue_resolutions", choices) }))
        }
    }

    @Test fun mergeIsTerminalForEachOriginalConflict() {
        for ((intent, independent) in listOf("finalize_draft" to false, "resolve_issue" to true)) {
            val merge = JsonObject(choice(resolution = JsonPrimitive("merge_into")) + buildJsonObject {
                put("target_transaction_id", "3")
                put("expected_target_revision", 1)
            })
            val choices = buildList {
                add(merge)
                if (independent) add(choice(JsonPrimitive("4"), resolution = JsonPrimitive("create_independent")))
            }
            assertFailsWith<MutationPlanFailure>("$intent independent=$independent") {
                MutationPlanRequest.parse(value(buildJsonObject {
                    put("intent", intent)
                    put("issue_resolutions", JsonArray(choices))
                }))
            }
        }
    }
}

