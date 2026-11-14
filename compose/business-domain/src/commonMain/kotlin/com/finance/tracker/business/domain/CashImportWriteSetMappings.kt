package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** Validate final mappings only; publication choices come from the shared client operation rules. */
fun prepareCashImportWriteSetMappings(changes: CashWriteSetDto, facts: LedgerFactsDto): CashWriteSetDto {
    try { validateCashWriteSetReferences(changes, facts) }
    catch (failure: CashWriteSetReferenceFailure) { throw CashPolicyError(failure.code, listOf(failure.fieldPath)) }
    fun invalid(path: String): Nothing = throw CashPolicyError("funding_mapping_invalid", listOf(path))
    val accounts = facts.accounts.associateBy { it.id }
    val oldMappings = facts.statementAccountMappings.associateBy { it.id }
    for ((index, mapping) in changes.statementAccountMappings.withIndex()) {
        val path = "statement_account_mappings[$index]"
        if (mapping.sourceType !in setOf("alipay", "icbc_credit", "icbc_debit", "icbc_asia", "ccb_debit") ||
            mapping.identityKind != "funding_group_v1" || mapping.sourceAccountKey.isEmpty() ||
            mapping.sourceAccountKey.pythonCodePointCount() > 255) invalid(path)
        val account = mapping.accountId?.let(accounts::get) ?: invalid("$path.account_id")
        if (!account.active || account.type !in setOf("cash", "loan", "lend")) invalid("$path.account_id")
        val old = (mapping.target.reference as? ExistingCashWriteReferenceDto)?.let { oldMappings.getValue(it.id) }
        if (old != null && (old.sourceType != mapping.sourceType || old.identityKind != mapping.identityKind ||
                old.sourceAccountKey != mapping.sourceAccountKey)) invalid("$path.target")
    }
    return changes
}
