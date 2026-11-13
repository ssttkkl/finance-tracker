package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Verify publication effects from complete operation observations; caller still proves representative-source provenance. */
fun prepareCashImportWriteSetMappings(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    try { validateCashWriteSetReferences(changes, facts) }
    catch (failure: CashWriteSetReferenceFailure) { throw CashPolicyError(failure.code, listOf(failure.fieldPath)) }
    fun invalid(path: String): Nothing = throw CashPolicyError("funding_mapping_invalid", listOf(path))
    val groups = linkedMapOf<String, CashMappingGroupDto>()
    for ((index, group) in changes.accountMappingGroups.withIndex()) {
        val path = "account_mapping_groups[$index]"
        val kinds = when (group.sourceType) {
            "alipay", "wechat" -> setOf("payment_method")
            "icbc_credit", "icbc_debit", "icbc_asia", "ccb_debit" -> setOf("file_account", "file_local")
            else -> invalid(path)
        }
        if (group.identityKind !in kinds || group.originKey.isEmpty() || group.identityKey.isEmpty()) invalid(path)
        val digest = try { localSourceStringDigest(listOf("funding-group-v1", group.sourceType, group.identityKind, group.originKey, group.identityKey)) }
        catch (_: IllegalArgumentException) { invalid(path) }
        if (group.sourceAccountRef != digest || groups.put(group.sourceAccountRef, group) != null) invalid(path)
    }
    val parents = facts.cashTransactions.associateBy { it.id }
    val accounts = facts.accounts.associateBy { it.id }
    val transactions = changes.transactions.associateBy { it.target.reference }
    val bindings = changes.sourceBindings.associateBy { it.target.reference }
    val oldBindings = facts.cashSourceBindings.associateBy { it.id }
    val observed = mutableSetOf<CashWriteReferenceDto>()
    val occurrences = mutableSetOf<CashWriteReferenceDto>()
    val selectedAccounts = mutableMapOf<String, String>()
    val expected = linkedMapOf<Pair<String, String>, String>()
    for ((index, proof) in changes.accountMappingRows.withIndex()) {
        val path = "account_mapping_rows[$index]"
        val row = transactions[proof.transaction.reference] ?: invalid("$path.transaction")
        if (row.target != proof.transaction || !observed.add(proof.transaction.reference)) invalid("$path.transaction")
        if (!occurrences.add(proof.sourceBinding.reference)) invalid("$path.source_binding")
        val source = bindings[proof.sourceBinding.reference]
        val previous = (proof.sourceBinding.reference as? ExistingCashWriteReferenceDto)?.let { oldBindings.getValue(it.id) }
        val owner = source?.transaction ?: previous?.transactionId?.let(::ExistingCashWriteReferenceDto)
        val sourceType = source?.sourceType ?: previous?.sourceType
        val identityKind = source?.identityKind ?: previous?.identityKind
        if (owner != row.target.reference || sourceType != row.values.sourceType || identityKind != "file_occurrence") invalid("$path.source_binding")
        val old = (row.target.reference as? ExistingCashWriteReferenceDto)?.let { parents.getValue(it.id) }
        if (proof.decision !in setOf("apply", "defer", "keep_existing")) invalid("$path.decision")
        if (proof.decision == "keep_existing" && (old == null || row.editedFields.isNotEmpty() || row.entryEdit != null)) invalid("$path.decision")
        val selections = linkedMapOf<String, String>()
        for ((choiceIndex, selection) in proof.selections.withIndex()) {
            val choicePath = "$path.selections[$choiceIndex]"
            val group = groups[selection.groupRef] ?: invalid("$choicePath.group_ref")
            if (group.sourceType != sourceType || selections.put(selection.groupRef, selection.accountId) != null) invalid(choicePath)
            val selected = selectedAccounts.put(selection.groupRef, selection.accountId)
            if (selected != null && selected != selection.accountId) invalid(choicePath)
            val account = accounts.getValue(selection.accountId)
            if (!account.active || account.type !in setOf("cash", "loan", "lend")) invalid("$choicePath.account_id")
        }
        if (proof.decision == "keep_existing" || old?.state == "posted" && proof.decision == "defer" ||
            row.editedFields.any { it in setOf("account_id", "components", "cash_granularity") }) continue
        if (old?.state == "posted") {
            fun comparable(parts: List<CashMappingComponentDto>): Any? {
                val values = parts.sortedBy { it.ordinal.toIntOrNull() ?: invalid("$path.funding_components") }.map { part ->
                    val ordinal = part.ordinal.toIntOrNull()
                    if (ordinal == null || ordinal < 0 || ordinal.toString() != part.ordinal) invalid("$path.funding_components")
                    mapOf("ordinal" to SourceJsonInteger(ordinal), "account_id" to part.accountId, "amount" to part.amount, "currency" to part.currency)
                }
                return LocalCashRecordValues.prepare(mapOf("state" to "draft"), mapOf("components" to values), emptySet()).values["components"]
            }
            val final = row.components.map { CashMappingComponentDto(it.ordinal, it.accountId, it.amount, it.currency) }
            if (!LocalCashRecordValues.valuesEqual(comparable(proof.fundingComponents), comparable(final))) continue
        }
        val required = linkedMapOf<String, MutableSet<String>>()
        fun currency(id: String?, value: String?) { if (id != null && value != null) required.getOrPut(id) { linkedSetOf() } += value }
        currency(row.values.accountId, row.values.currency)
        row.components.forEach { currency(it.accountId, it.currency) }
        for ((groupRef, accountId) in selections) {
            val group = groups.getValue(groupRef)
            if (group.sourceType == "wechat") continue
            val supported = (accounts.getValue(accountId).currencies as? JsonArray).orEmpty()
            if (required[accountId].orEmpty().any { code -> supported.none { it is JsonPrimitive && it.isString && it.content == code } }) continue
            val key = group.sourceType to groupRef
            if (key in expected && expected.getValue(key) != accountId) invalid("$path.selections")
            expected[key] = accountId
        }
    }
    if (observed != transactions.keys) invalid("account_mapping_rows")
    val oldMappings = facts.statementAccountMappings.associateBy { it.id }
    val actual = linkedMapOf<Pair<String, String>, String>()
    for ((index, mapping) in changes.statementAccountMappings.withIndex()) {
        val path = "statement_account_mappings[$index]"
        val accountId = mapping.accountId ?: invalid(path)
        if (mapping.identityKind != "funding_group_v1" || mapping.sourceType == "wechat") invalid(path)
        val previous = (mapping.target.reference as? ExistingCashWriteReferenceDto)?.let { oldMappings.getValue(it.id) }
        if (previous != null && (previous.sourceType != mapping.sourceType || previous.identityKind != mapping.identityKind ||
                previous.sourceAccountKey != mapping.sourceAccountKey)) invalid("$path.target")
        val key = mapping.sourceType to mapping.sourceAccountKey
        if (actual.put(key, accountId) != null) invalid(path)
    }
    if (actual != expected) invalid("statement_account_mappings")
    return changes
}
