package com.finance.tracker.backend

import kotlinx.serialization.json.*

internal object MutationPlanRevalidation {
    private val operationFields = setOf("occurrence_key", "transaction_id", "expected_revision", "planned_action",
        "current", "values", "manual_fields", "field_diffs", "pending_fields", "source_updates", "issues", "expected_issues",
        "retained_issue_ids", "expected_accounts", "source_explanation", "source_scope_key", "candidate_ids", "candidate_set_ref",
        "entry_edit", "entry_edit_group", "entry_snapshot", "type_changed", "preview_transaction_id", "preview_components",
        "issue_resolutions", "resolved_issue_ids", "expected_source_bindings", "merge_draft")
    private val relationFields = listOf("category_member_ids", "mutation_units", "manual_transfer_relations", "dependencies",
        "type_effects", "relation_groups", "relation_failures", "additional_effects")
    private fun fingerprint(operation: JsonObject) = cashSourceDigest(JsonObject(operation.filterKeys { it in operationFields }))
    fun validate(plan: JsonObject, fresh: JsonObject) {
        val old = plan.getValue("operations").jsonArray
        val current = fresh.getValue("operations").jsonArray
        if (old.size != current.size || old.zip(current).any { (left, right) ->
                left.jsonObject["operation_ref"] != right.jsonObject["operation_ref"] ||
                    fingerprint(left.jsonObject) != fingerprint(right.jsonObject)
            }) throw MutationPlanFailure("record_changed")
        if (relationFields.any { plan[it] != fresh[it] }) throw MutationPlanFailure("relation_context_changed")
    }
}
