package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.prepareCashImportWriteSetBindings

/** Same source transaction only; the outer command still owns identity replay, authorization, baseline and receipt. */
internal fun saveCashImportWriteSetBindings(context: CashSqlContext, sources: CashImportWriteSetSourceResult): Map<CashWriteReferenceDto, Long> {
    sources.requireOwner(context)
    val changes = prepareCashImportWriteSetBindings(sources.changes, sources.before, sources.aliases)
    val writer = CashSourceBindingValueWriter(context)
    return changes.sourceBindings.associate { row ->
        val parent = sources.transactions[row.transaction] ?: when (val ref = row.transaction) {
            is ExistingCashWriteReferenceDto -> ref.id.toLong()
            is NewCashWriteReferenceDto -> error("write_set.missing_source_identity")
        }
        val key = SourceBindingKey(row.sourceType, row.sourceScopeKind, row.sourceScopeKey, row.identityKind, row.identityKey)
        val saved = writer.save(parent, key, row.strategyVersion, row.coverage, row.stableEvidenceKey,
            sources.identities.bindings[row.target.reference] ?: sources.identities.deletedBindings[row.target.reference] ?:
                (row.target.reference as? ExistingCashWriteReferenceDto)?.id?.toLong())
        row.target.reference to saved.first
    }
}
