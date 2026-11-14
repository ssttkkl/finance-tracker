package com.finance.tracker.backend

import com.finance.tracker.business.contracts.CashWriteReferenceDto
import com.finance.tracker.business.domain.prepareCashImportWriteSetMappings

/** Same source transaction only; full service still owns source identity, authorization, baseline and receipt. */
internal fun saveCashImportWriteSetMappings(
    context: CashSqlContext,
    sources: CashImportWriteSetSourceResult,
    userId: String,
): Map<CashWriteReferenceDto, Long> {
    sources.requireOwner(context)
    val changes = prepareCashImportWriteSetMappings(sources.changes, sources.before)
    val mappings = changes.statementAccountMappings
    val repository = CashFundingMappingRepository(context.connection, context.workspace, context.sqlite, context.now)
    repository.publish(mappings.map { mapping -> mapOf(
        "source_type" to mapping.sourceType,
        "source_account_ref" to mapping.sourceAccountKey,
        "account_id" to requireNotNull(mapping.accountId),
    ) }, userId)
    val keys = mappings.associate { it.target.reference to CashFundingMappingKey(it.sourceType, it.identityKind, it.sourceAccountKey) }
    val saved = repository.lookup(keys.values)
    return keys.mapValues { (_, key) -> saved.getValue(key).id }
}
