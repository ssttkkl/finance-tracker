package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

class CashWriteSetReferenceFailure(val code: String, val fieldPath: String) : RuntimeException(code)

private fun invalidWriteReference(path: String): Nothing = throw CashWriteSetReferenceFailure("invalid_reference", path)
private fun changedWriteReference(path: String): Nothing = throw CashWriteSetReferenceFailure("reference_changed", path)
private fun duplicateWriteReference(path: String): Nothing = throw CashWriteSetReferenceFailure("duplicate_reference", path)
private fun canonicalWriteLong(value: String?, positive: Boolean): Boolean = value?.toLongOrNull()?.let {
    it >= (if (positive) 1L else 0L) && it.toString() == value
} == true

private fun <K> remainingWriteKeys(values: List<Pair<K, String>>, replaced: List<CashWriteVersionedReferenceDto>): MutableSet<K> {
    check(values.map { it.first }.toSet().size == values.size) { "write_set.duplicate_persisted_key" }
    val removedIds = replaced.mapNotNull { (it.reference as? ExistingCashWriteReferenceDto)?.id }.toSet()
    return values.filter { it.second !in removedIds }.mapTo(mutableSetOf()) { it.first }
}

private class WriteReferenceScope(revisions: List<Pair<String, String>>) {
    private val current = revisions.toMap()
    private val declared = mutableSetOf<CashWriteReferenceDto>()
    init {
        check(current.size == revisions.size) { "write_set.duplicate_persisted_identity" }
        check(current.all { (id, revision) -> id.isNotEmpty() && canonicalWriteLong(revision, positive = true) }) {
            "write_set.invalid_persisted_revision"
        }
    }
    fun target(value: CashWriteVersionedReferenceDto, path: String, allowCreate: Boolean = true) {
        when (val reference = value.reference) {
            is ExistingCashWriteReferenceDto -> dependency(value, path)
            is NewCashWriteReferenceDto -> {
                if (!allowCreate || reference.ref.isEmpty() || value.expectedRevision != null) invalidWriteReference(path)
            }
        }
        if (!declared.add(value.reference)) duplicateWriteReference(path)
    }
    fun reference(value: CashWriteReferenceDto, path: String) {
        when (value) {
            is ExistingCashWriteReferenceDto -> if (value.id !in current) changedWriteReference(path)
            is NewCashWriteReferenceDto -> if (value !in declared) invalidWriteReference(path)
        }
    }
    fun dependency(value: CashWriteVersionedReferenceDto, path: String) {
        when (val reference = value.reference) {
            is ExistingCashWriteReferenceDto -> {
                if (!canonicalWriteLong(value.expectedRevision, positive = true)) invalidWriteReference(path)
                if (current[reference.id] != value.expectedRevision) changedWriteReference(path)
            }
            is NewCashWriteReferenceDto -> {
                if (value.expectedRevision != null) invalidWriteReference(path)
                reference(reference, path)
            }
        }
    }
}

/** Reference preflight only. The caller must supply trusted facts under its authorization and base-version lock. */
fun validateCashWriteSetReferences(changes: CashWriteSetDto, facts: LedgerFactsDto) {
    val transactions = WriteReferenceScope(facts.cashTransactions.map { it.id to it.rowRevision })
    val relations = WriteReferenceScope(facts.transactionRelations.map { it.id to it.rowRevision })
    val issues = WriteReferenceScope(facts.cashTransactionIssues.map { it.id to it.revision })
    val entries = WriteReferenceScope(facts.cashEntries.map { it.id to it.revision })
    val bindings = WriteReferenceScope(facts.cashSourceBindings.map { it.id to it.revision })
    val mappings = WriteReferenceScope(facts.statementAccountMappings.map { it.id to it.revision })
    changes.transactions.forEachIndexed { i, value -> transactions.target(value.target, "transactions[$i].target") }
    changes.deletedTransactions.forEachIndexed { i, value -> transactions.target(value.target, "deleted_transactions[$i].target", allowCreate = false) }
    changes.relations.forEachIndexed { i, value -> relations.target(value.target, "relations[$i].target") }
    changes.issues.forEachIndexed { i, value -> issues.target(value.target, "issues[$i].target") }
    changes.resolvedIssues.forEachIndexed { i, value -> issues.target(value, "resolved_issues[$i]", allowCreate = false) }
    changes.entries.forEachIndexed { i, value -> entries.target(value.target, "entries[$i].target") }
    changes.deletedEntries.forEachIndexed { i, value -> entries.target(value, "deleted_entries[$i]", allowCreate = false) }
    changes.sourceBindings.forEachIndexed { i, value -> bindings.target(value.target, "source_bindings[$i].target") }
    changes.statementAccountMappings.forEachIndexed { i, value -> mappings.target(value.target, "statement_account_mappings[$i].target") }

    val splitEntries = mutableSetOf<CashWriteReferenceDto>()
    changes.entrySplitChoices.forEachIndexed { i, choice ->
        val path = "entry_split_choices[$i].entry"
        if (choice.entry.reference !is ExistingCashWriteReferenceDto) invalidWriteReference(path)
        entries.dependency(choice.entry, path)
        if (!splitEntries.add(choice.entry.reference)) duplicateWriteReference(path)
    }

    val decidedIssues = mutableSetOf<CashWriteReferenceDto>()
    changes.issueResolutions.forEachIndexed { i, resolution ->
        val path = "issue_resolutions[$i]"
        if (resolution.issue.reference !is ExistingCashWriteReferenceDto) invalidWriteReference("$path.issue")
        issues.dependency(resolution.issue, "$path.issue")
        if (!decidedIssues.add(resolution.issue.reference)) duplicateWriteReference("$path.issue")
        if (resolution is MergeIntoIssueResolutionDto) {
            if (resolution.targetTransaction.reference !is ExistingCashWriteReferenceDto) invalidWriteReference("$path.target_transaction")
            transactions.dependency(resolution.targetTransaction, "$path.target_transaction")
        }
    }

    val accounts = facts.accounts.map { it.id }.toSet()
    val categories = facts.cashCategories.map { it.id }.toSet()
    fun account(id: String?, path: String) { if (id != null && id !in accounts) changedWriteReference(path) }
    fun category(id: String?, path: String) { if (id != null && id !in categories) changedWriteReference(path) }
    val originalComponents = facts.cashTransactionComponents.associateBy { it.id }
    check(originalComponents.size == facts.cashTransactionComponents.size) { "write_set.duplicate_persisted_component" }
    val replacedParents = changes.transactions.mapNotNull { (it.target.reference as? ExistingCashWriteReferenceDto)?.id }.toSet()
    val components = facts.cashTransactionComponents.filter { it.cashTransactionId !in replacedParents }
        .associate { existingComponent ->
            (ExistingCashWriteReferenceDto(existingComponent.id) as CashWriteReferenceDto) to
                (ExistingCashWriteReferenceDto(existingComponent.cashTransactionId) as CashWriteReferenceDto)
        }.toMutableMap()
    val declaredComponents = mutableSetOf<CashWriteReferenceDto>()
    changes.transactions.forEachIndexed { i, value ->
        val path = "transactions[$i]"
        account(value.values.accountId, "$path.values.account_id")
        category(value.values.categoryId, "$path.values.category_id")
        val ordinals = mutableSetOf<String>()
        value.components.forEachIndexed { j, component ->
            val partPath = "$path.components[$j]"
            if (!declaredComponents.add(component.reference)) duplicateWriteReference("$partPath.reference")
            when (val reference = component.reference) {
                is ExistingCashWriteReferenceDto -> {
                    val old = originalComponents[reference.id] ?: changedWriteReference("$partPath.reference")
                    if (value.target.reference != ExistingCashWriteReferenceDto(old.cashTransactionId)) invalidWriteReference("$partPath.reference")
                }
                is NewCashWriteReferenceDto -> if (reference.ref.isEmpty()) invalidWriteReference("$partPath.reference")
            }
            if (!canonicalWriteLong(component.ordinal, positive = false) || component.ordinal.toIntOrNull() == null) invalidWriteReference("$partPath.ordinal")
            if (!ordinals.add(component.ordinal)) duplicateWriteReference("$partPath.ordinal")
            account(component.accountId, "$partPath.account_id")
            components[component.reference] = value.target.reference
        }
    }
    fun component(reference: CashWriteReferenceDto?, parent: CashWriteReferenceDto, path: String) {
        if (reference == null) return
        val owner = components[reference] ?: run {
            if (reference is ExistingCashWriteReferenceDto && reference.id !in originalComponents) changedWriteReference(path)
            invalidWriteReference(path)
        }
        if (owner != parent) invalidWriteReference(path)
    }
    changes.relations.forEachIndexed { i, value ->
        val path = "relations[$i]"
        transactions.dependency(value.primaryTransaction, "$path.primary_transaction")
        value.secondaryTransaction?.let { transactions.dependency(it, "$path.secondary_transaction") }
        transactions.dependency(value.anchorTransaction, "$path.anchor_transaction")
        component(value.primaryComponent, value.primaryTransaction.reference, "$path.primary_component")
        if (value.secondaryTransaction == null && value.secondaryComponent != null) invalidWriteReference("$path.secondary_component")
        value.secondaryTransaction?.let { component(value.secondaryComponent, it.reference, "$path.secondary_component") }
        component(value.anchorComponent, value.anchorTransaction.reference, "$path.anchor_component")
        val candidates = mutableSetOf<Pair<CashWriteReferenceDto, CashWriteReferenceDto?>>()
        value.candidates.forEachIndexed { j, candidate ->
            val candidatePath = "$path.candidates[$j]"
            transactions.dependency(candidate.transaction, "$candidatePath.transaction")
            component(candidate.component, candidate.transaction.reference, "$candidatePath.component")
            if (!candidates.add(candidate.transaction.reference to candidate.component)) duplicateWriteReference(candidatePath)
        }
    }
    val issueKeys = remainingWriteKeys<Pair<CashWriteReferenceDto, String>>(
        facts.cashTransactionIssues.map { (ExistingCashWriteReferenceDto(it.transactionId) to it.issueKey) to it.id },
        changes.issues.map { it.target } + changes.resolvedIssues)
    changes.issues.forEachIndexed { i, value ->
        val path = "issues[$i]"
        transactions.dependency(value.transaction, "$path.transaction")
        if (!issueKeys.add(value.transaction.reference to value.issueKey)) duplicateWriteReference("$path.issue_key")
        val candidates = mutableSetOf<CashWriteReferenceDto>()
        value.candidates.forEachIndexed { j, candidate ->
            transactions.dependency(candidate, "$path.candidates[$j]")
            if (!candidates.add(candidate.reference)) duplicateWriteReference("$path.candidates[$j]")
        }
    }
    val entryMembers = remainingWriteKeys<CashWriteReferenceDto>(
        facts.cashEntryMembers.map { ExistingCashWriteReferenceDto(it.transactionId) to it.entryId },
        changes.entries.map { it.target } + changes.deletedEntries)
    changes.entries.forEachIndexed { i, value ->
        val path = "entries[$i]"
        transactions.dependency(value.rootTransaction, "$path.root_transaction")
        transactions.dependency(value.identityTransaction, "$path.identity_transaction")
        category(value.categoryId, "$path.category_id")
        value.members.forEachIndexed { j, member ->
            transactions.dependency(member.transaction, "$path.members[$j].transaction")
            if (!entryMembers.add(member.transaction.reference)) duplicateWriteReference("$path.members[$j].transaction")
        }
    }
    val bindingKeys = remainingWriteKeys(facts.cashSourceBindings.map {
        listOf(it.sourceType, it.sourceScopeKind, it.sourceScopeKey, it.identityKind, it.identityKey) to it.id
    }, changes.sourceBindings.map { it.target })
    changes.sourceBindings.forEachIndexed { i, value ->
        val path = "source_bindings[$i]"
        transactions.reference(value.transaction, "$path.transaction")
        if (!bindingKeys.add(listOf(value.sourceType, value.sourceScopeKind, value.sourceScopeKey, value.identityKind, value.identityKey)))
            duplicateWriteReference(path)
    }
    val mappingKeys = remainingWriteKeys(facts.statementAccountMappings.map {
        listOf(it.sourceType, it.identityKind, it.sourceAccountKey) to it.id
    }, changes.statementAccountMappings.map { it.target })
    changes.statementAccountMappings.forEachIndexed { i, value ->
        val path = "statement_account_mappings[$i]"
        account(value.accountId, "$path.account_id")
        if (!mappingKeys.add(listOf(value.sourceType, value.identityKind, value.sourceAccountKey))) duplicateWriteReference(path)
    }
    val pageKeys = mutableSetOf<List<String>>()
    changes.sourceEmptyPages.forEachIndexed { i, page ->
        if (!pageKeys.add(listOf(page.sourceType, page.parserVersion, page.documentKey, page.pageNumber.toString())))
            duplicateWriteReference("source_empty_pages[$i]")
    }
}
