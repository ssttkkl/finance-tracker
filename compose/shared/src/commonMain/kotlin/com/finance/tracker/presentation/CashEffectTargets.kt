package com.finance.tracker.presentation

import com.finance.tracker.domain.*

internal fun cashEffectTargets(effect: CashAdditionalEffect, details: List<ImportItemDetail>, input: CashMaintenanceRequest?): Set<String> {
    val members = details.flatMap { it.members }.mapNotNull { it.transactionId }.toSet()
    return when (effect.kind) {
        "field_value_changed", "new_attention" -> setOf(effect.reference).intersect(members)
        "relation_changed", "choice_changed" -> details.flatMap { detail ->
            detail.relationChoices.filter { it.suggestionId == effect.reference }.flatMap { it.members } +
                detail.relationEffects.filter { it.relationId == effect.reference }.flatMap { it.members }
        }.mapNotNull { it.transactionId }.toSet()
        "funding_relation_removed" -> if (input == null) emptySet() else (input.memberPatches.filter {
            it.patch.fields.keys.any { field -> field == "record_type" || field == "record_subtype" }
        }.map { it.transactionId }.toSet().ifEmpty { setOf(input.transactionId) }).intersect(members)
        else -> emptySet()
    }
}
