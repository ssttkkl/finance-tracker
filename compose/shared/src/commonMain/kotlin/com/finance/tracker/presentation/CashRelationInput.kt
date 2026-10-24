package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal fun cashRelationRequest(record: CashCurrentRecord, choices: List<CashRelationDecision>, options: List<ImportPairOption>,
    patch: StructuredObject = StructuredObject(emptyMap())): CashMaintenanceRequest {
    fun invalid(): Nothing = throw DomainFailure("choice_invalid", 0, FailureCategory.RECOVERABLE)
    if (record.recordOrigin in setOf("manual", "manual_transfer") || choices.isEmpty() || choices.map { it.suggestionId }.distinct().size != choices.size) invalid()
    if (patch.fields.keys.any { it !in record.editableFields }) invalid()
    val groups = options.groupBy { it.suggestionId }.mapValues { (_, values) -> values.distinct().singleOrNull() ?: invalid() }
    for (choice in choices) {
        val option = groups[choice.suggestionId] ?: invalid()
        if (choice.action !in option.allowedActions) invalid()
        if (choice.action == "accept") {
            if (option.candidates.isNotEmpty() && option.candidates.none { it.candidateId == choice.candidateId }) invalid()
            if (option.candidates.isEmpty() && choice.candidateId != null && choice.candidateId != option.candidateId) invalid()
            if (option.commonFieldConflicts.any { (field, values) -> choice.commonFields?.fields?.get(field) !in values }) invalid()
            if (choice.commonFields?.fields?.keys.orEmpty().any { it !in option.commonFieldConflicts }) invalid()
        } else if (choice.candidateId != null || choice.commonFields != null) invalid()
        if (choice.action != "remove" && choice.inheritCommonFields != null) invalid()
    }
    return CashMaintenanceRequest("maintain_relations", record.transactionId, record.rowRevision, patch, relationChoices = choices)
}
