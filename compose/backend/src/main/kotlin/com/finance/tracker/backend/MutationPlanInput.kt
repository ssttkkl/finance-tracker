package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal class MutationPlanFailure(val code: String) : RuntimeException(code)

internal data class MutationIssueResolution(
    val issueId: String, val expectedIssueRevision: Long, val resolution: String,
    val targetTransactionId: String? = null, val expectedTargetRevision: Long? = null,
)
internal data class MutationMemberPatch(val transactionId: String, val patch: JsonObject)
internal data class MutationPlanMetadata(val editContextId: String?, val previousPlanId: String?, val expectedPlanRevision: Long?)

internal data class MutationPlanRequest(
    val intent: String, val transactionId: String, val expectedRevision: Long, val patch: JsonObject,
    val relationChoices: List<JsonObject>, val issueResolutions: List<MutationIssueResolution>,
    val entryId: String?, val expectedEntryRevision: Long?, val memberPatches: List<MutationMemberPatch>,
    val relationAdditions: List<JsonObject>, val metadata: MutationPlanMetadata,
) {
    companion object {
        val intents = setOf("save_draft", "edit_entry", "edit_record", "finalize_draft", "resolve_issue", "maintain_relations")
        private val commonKeys = setOf("intent", "transaction_id", "expected_revision", "patch", "relation_choices",
            "issue_resolutions", "entry_id", "expected_entry_revision", "member_patches", "relation_additions")
        private val metaKeys = setOf("edit_context_id", "previous_plan_id", "expected_plan_revision")
        private val issueKeys = setOf("issue_id", "expected_issue_revision", "resolution", "target_transaction_id",
            "expected_target_revision")
        private val choiceFields = setOf("suggestion_id", "action", "candidate_id", "common_fields", "inherit_common_fields")
        private val additionFields = setOf("target_transaction_id", "expected_target_revision", "anchor_component_id",
            "target_component_id", "kind", "subtype", "common_fields")
        private val commonCashFields = setOf("category_id", "note", "counterparty", "counterparty_account",
            "counterparty_account_attrs")

        fun fromStored(input: JsonObject): MutationPlanRequest {
            val normalized = if (input["intent"] != JsonPrimitive("maintain_relations") && "relation_additions" in input) {
                if (input["relation_additions"] != JsonArray(emptyList())) invalid()
                JsonObject(input - "relation_additions")
            } else input
            return parse(normalized)
        }

        fun parse(value: JsonElement?): MutationPlanRequest {
            val input = value as? JsonObject ?: invalid()
            if ((input.keys - commonKeys - metaKeys).isNotEmpty()) invalid()
            val intent = (input["intent"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it in intents } ?: invalid()
            val expectedRevision = input["expected_revision"].revision() ?: invalid()
            val patch = if ("patch" in input) input["patch"] as? JsonObject ?: invalid() else JsonObject(emptyMap())
            val transactionId = input["transaction_id"].identifier()
            val relationChoices = choices(input["relation_choices"] ?: JsonArray(emptyList()))
            val issueResolutions = resolutions(input["issue_resolutions"] ?: JsonArray(emptyList()))
            val merges = issueResolutions.filter { it.resolution == "merge_into" }
            if ((intent == "resolve_issue" && issueResolutions.isEmpty()) ||
                (intent == "maintain_relations" && issueResolutions.isNotEmpty()) ||
                merges.size > 1 || merges.isNotEmpty() && (intent == "finalize_draft" ||
                    issueResolutions.any { it.resolution == "create_independent" })) invalid()
            if (intent == "save_draft" && (issueResolutions.isNotEmpty() || relationChoices.isNotEmpty())) invalid()
            val entryId = input["entry_id"]?.reference(64)
            val expectedEntryRevision = if ("expected_entry_revision" in input) {
                input["expected_entry_revision"].revision() ?: invalid()
            } else null
            if (intent == "edit_entry" && (entryId == null || expectedEntryRevision == null)) invalid()
            val members = memberPatches(input["member_patches"] ?: JsonArray(emptyList()))
            if (members.isNotEmpty() && intent != "edit_entry") invalid()
            val additions = if ("relation_additions" in input) {
                if (intent != "maintain_relations") invalid()
                additions(input.getValue("relation_additions"))
            } else emptyList()
            val metadata = metadata(input)
            return MutationPlanRequest(intent, transactionId, expectedRevision, patch, relationChoices, issueResolutions,
                entryId, expectedEntryRevision, members, additions, metadata)
        }

        private fun metadata(input: JsonObject): MutationPlanMetadata {
            val context = input["edit_context_id"]?.takeIf { it != JsonNull }
                ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: return metadataWithoutContext(input)
            if (!context.matches(Regex("[A-Za-z0-9_-]{43}")) ||
                input["previous_plan_id"].reference(128) == null ||
                input["expected_plan_revision"].revision() == null) invalid()
            return MutationPlanMetadata(context, input["previous_plan_id"].reference(128),
                input["expected_plan_revision"].revision())
        }

        private fun metadataWithoutContext(input: JsonObject): MutationPlanMetadata {
            if (input["previous_plan_id"]?.takeIf { it != JsonNull } != null ||
                input["expected_plan_revision"]?.takeIf { it != JsonNull } != null) invalid()
            return MutationPlanMetadata(null, null, null)
        }

        private fun choices(value: JsonElement): List<JsonObject> {
            val array = value as? JsonArray ?: invalid()
            if (array.size > 100_000) invalid()
            val seen = mutableSetOf<String>()
            return array.map { raw ->
                val item = raw as? JsonObject ?: invalid()
                if (item.keys.any { it !in choiceFields }) invalid()
                val suggestion = item["suggestion_id"].reference(255) ?: invalid()
                if (suggestion in seen) invalid()
                seen += suggestion
                val action = (item["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (action !in setOf("accept", "defer", "ignore", "remove")) invalid()
                item["candidate_id"]?.takeIf { it != JsonNull }?.let { if (it.reference(128) == null) invalid() }
                if ("common_fields" in item) {
                    val common = item["common_fields"] as? JsonObject ?: invalid()
                    if (action != "accept" || common.keys.any { it !in commonCashFields }) invalid()
                    for ((field, fieldValue) in common) try {
                        CashRecordPolicy.normalize(field, cashSourceValue(fieldValue))
                    } catch (_: InvalidCashValue) { invalid() } catch (_: CashPolicyError) { invalid() }
                }
                item["inherit_common_fields"]?.let {
                    val primitive = it as? JsonPrimitive ?: invalid()
                    if (primitive.booleanOrNull == null || primitive.isString) invalid()
                }
                item
            }
        }

        private fun resolutions(value: JsonElement): List<MutationIssueResolution> {
            val array = value as? JsonArray ?: invalid()
            if (array.size > 100) invalid()
            val seen = mutableSetOf<String>()
            return array.map { raw ->
                val item = raw as? JsonObject ?: invalid()
                if ((item.keys - issueKeys).isNotEmpty()) invalid()
                val resolution = (item["resolution"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it in setOf("apply_source", "keep_existing", "merge_into", "create_independent") }
                    ?: invalid()
                val revision = item["expected_issue_revision"].revision() ?: invalid()
                val issueId = item["issue_id"].identifier()
                if (!seen.add(issueId)) invalid()
                if (resolution == "merge_into") {
                    val target = item["target_transaction_id"].identifier()
                    val targetRevision = item["expected_target_revision"].revision() ?: invalid()
                    MutationIssueResolution(issueId, revision, resolution, target, targetRevision)
                } else {
                    if ("target_transaction_id" in item || "expected_target_revision" in item) invalid()
                    MutationIssueResolution(issueId, revision, resolution)
                }
            }
        }

        private fun memberPatches(value: JsonElement): List<MutationMemberPatch> {
            val array = value as? JsonArray ?: invalid()
            if (array.size > 2) invalid()
            return array.map { raw ->
                val item = raw as? JsonObject ?: invalid()
                if (item.keys != setOf("transaction_id", "patch")) invalid()
                MutationMemberPatch(item["transaction_id"].identifier(), item["patch"] as? JsonObject ?: invalid())
            }
        }

        private fun additions(value: JsonElement): List<JsonObject> {
            val array = value as? JsonArray ?: invalid()
            if (array.size > 1) invalid()
            return array.map { raw ->
                val item = raw as? JsonObject ?: invalid()
                if (item.keys.any { it !in additionFields } || (additionFields - "common_fields" - item.keys).isNotEmpty()) invalid()
                for (field in listOf("target_transaction_id", "anchor_component_id", "target_component_id"))
                    item[field].identifier()
                item["expected_target_revision"].revision() ?: invalid()
                val kind = (item["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (kind !in setOf("payment_mirror", "refund_offset", "transfer_pair")) invalid()
                if (item["subtype"] !is JsonPrimitive || !(item["subtype"] as JsonPrimitive).isString) invalid()
                item["common_fields"]?.takeIf { it != JsonNull }?.let { if (it !is JsonObject) invalid() }
                JsonObject(item + mapOf(
                    "target_transaction_id" to JsonPrimitive(item["target_transaction_id"].identifier()),
                    "anchor_component_id" to JsonPrimitive(item["anchor_component_id"].identifier()),
                    "target_component_id" to JsonPrimitive(item["target_component_id"].identifier()),
                ))
            }
        }

        private fun JsonElement?.identifier(): String {
            val canonical = when {
                this is JsonPrimitive && !isString && booleanOrNull == null ->
                    content.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
                this is JsonPrimitive && isString ->
                    content.takeIf { it.length in 1..19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                else -> null
            }?.takeIf { it > 0 } ?: invalid()
            return canonical.toString()
        }

        private fun JsonElement?.revision(): Long? {
            if (this !is JsonPrimitive || isString || booleanOrNull != null) return null
            val value = content.toLongOrNull() ?: return null
            return value.takeIf { it in 1..Long.MAX_VALUE }
        }

        private fun JsonElement?.reference(maximum: Int): String? {
            if (this !is JsonPrimitive || !isString) return null
            return content.takeIf { it.matches(Regex("[A-Za-z0-9_:.~-]{1,$maximum}")) }
        }

        private fun invalid(): Nothing = throw MutationPlanFailure("plan_input_invalid")
    }
}
