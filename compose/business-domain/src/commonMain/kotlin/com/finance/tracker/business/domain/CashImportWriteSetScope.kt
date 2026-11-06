package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** File-command scope only; trusted facts, source legitimacy, effects and the transaction remain caller-owned. */
internal fun validateCashImportWriteSetScope(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    validateCashWriteSetFixedRecords(changes, facts)
    fun denied(path: String): Nothing = throw CashPolicyError("field_not_editable", listOf(path))
    if (changes.deletedTransactions.isNotEmpty()) denied("deleted_transactions")
    if (changes.issueResolutions.isNotEmpty()) denied("issue_resolutions")
    if (changes.entrySplitChoices.isNotEmpty()) denied("entry_split_choices")

    val parents = facts.cashTransactions.associateBy { it.id }
    for ((index, row) in changes.transactions.withIndex()) {
        val target = row.target.reference as? ExistingCashWriteReferenceDto ?: continue
        val old = parents.getValue(target.id)
        if (old.deletedAt != null || old.recordOrigin !in setOf("import", "legacy")) denied("transactions[$index].target")
    }
    for ((index, row) in changes.relations.withIndex()) {
        if (row.target.reference is ExistingCashWriteReferenceDto) denied("relations[$index].target")
    }
    val bindings = facts.cashSourceBindings.associateBy { it.id }
    for ((index, binding) in changes.sourceBindings.withIndex()) {
        val target = binding.target.reference as? ExistingCashWriteReferenceDto ?: continue
        val old = bindings.getValue(target.id)
        if (binding.transaction != ExistingCashWriteReferenceDto(old.transactionId) && parents.getValue(old.transactionId).deletedAt == null) {
            denied("source_bindings[$index].transaction")
        }
    }
    validateCashImportRelationGroups(changes, facts)
}

private fun validateCashImportRelationGroups(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    fun invalid(path: String): Nothing = throw CashWriteSetReferenceFailure("invalid_reference", path)
    val relations = changes.relations.associateBy { it.target.reference }
    val used = mutableSetOf<CashWriteReferenceDto>()
    val categories = facts.cashCategories.map { it.id }.toSet()
    for ((index, group) in changes.relationGroups.withIndex()) {
        val path = "relation_groups[$index]"
        if (group.relations.isEmpty()) invalid("$path.relations")
        val rows = group.relations.mapIndexed { memberIndex, reference ->
            val memberPath = "$path.relations[$memberIndex]"
            if (reference !is NewCashWriteReferenceDto || reference !in relations) invalid(memberPath)
            if (!used.add(reference)) throw CashWriteSetReferenceFailure("duplicate_reference", memberPath)
            relations.getValue(reference)
        }
        val states = rows.map { it.state }.toSet()
        if (states.size != 1 || states.single() !in setOf("confirmed", "draft", "ignored") || rows.any { !it.active }) {
            throw CashPolicyError("invalid_field", listOf(path))
        }
        if (group.commonFields.isNotEmpty() && states.single() != "confirmed") {
            throw CashPolicyError("field_not_editable", listOf("$path.common_fields"))
        }
        val category = group.commonFields["category_id"] as? kotlinx.serialization.json.JsonPrimitive
        if (category?.isString == true && category.content !in categories) {
            throw CashWriteSetReferenceFailure("reference_changed", "$path.common_fields.category_id")
        }
    }
    if (used != relations.keys) invalid("relation_groups")
}
