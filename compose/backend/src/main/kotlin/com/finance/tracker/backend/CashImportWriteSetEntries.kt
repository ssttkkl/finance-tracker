package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.normalizeCashWriteSetEntryValues
import com.finance.tracker.business.domain.validateCashWriteSetEntryResults

/** C and B, authorization and old dependencies must already be checked in this transaction. */
internal fun finishCashImportWriteSetEntries(context:CashSqlContext,changes:CashWriteSetDto,
    actualProjections:List<CashProjection>,resolve:(CashWriteReferenceDto)->Long) {
    context.requireTransactionStamp()
    val expected=normalizeCashWriteSetEntryValues(CashWriteSetDto(entries=changes.entries))
    val identifiers=linkedMapOf<CashWriteReferenceDto,Long>()
    fun invalid():Nothing=throw CashPolicyError("entry_result_invalid",listOf("entries"))
    fun identifier(ref:CashWriteReferenceDto):Long=identifiers.getOrPut(ref) {resolve(ref).also {if(it<=0) invalid()}}
    val declared=linkedMapOf<Long,Set<Long>>()
    val claimed=mutableSetOf<Long>()
    for(row in expected.entries) {
        val root=identifier(row.rootTransaction.reference)
        val members=row.members.map {identifier(it.transaction.reference)}
        val group=members.toSet()
        if(group.isEmpty() || group.size!=members.size || root !in group || declared.put(root,group)!=null) invalid()
        if(group.any {!claimed.add(it)}) invalid()
    }
    val actual=linkedMapOf<Long,Set<Long>>()
    claimed.clear()
    for(projection in actualProjections) {
        val root=projection.primaryRecord.id.storedCashId()
        val members=projection.storedMemberIds();val group=members.toSet()
        if(group.isEmpty() || group.size!=members.size || root !in group || actual.put(root,group)!=null) invalid()
        if(group.any {!claimed.add(it)}) invalid()
    }
    if(declared!=actual) invalid()
    if(expected.entries.isEmpty()) return
    context.entries.reconcile(actualProjections)
    for(row in expected.entries) {
        if(row.editedFields.isEmpty()) continue
        val root=identifiers.getValue(row.rootTransaction.reference)
        val values=linkedMapOf<String,Any?>("category_id" to row.categoryId,"note" to row.note,
            "counterparty" to row.counterparty,"counterparty_account" to row.counterpartyAccount,
            "counterparty_account_attrs" to cashSourceValue(row.counterpartyAccountAttrs))
        val entry=checkNotNull(context.entries.current(root))
        context.entries.patch(root,values.filterKeys {it in row.editedFields},entry.revision)
    }
    context.entries.flush()
    val facts=LedgerSnapshotRepository(context.connection,context.workspace,context.sqlite).facts()
    validateCashWriteSetEntryResults(expected,facts) {identifiers.getValue(it).toString()}
}
