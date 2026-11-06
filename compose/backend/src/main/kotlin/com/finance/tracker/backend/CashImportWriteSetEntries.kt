package com.finance.tracker.backend

import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.normalizeCashWriteSetEntryValues
import com.finance.tracker.business.domain.prepareCashImportWriteSetEntryEdits
import com.finance.tracker.business.domain.prepareCashImportWriteSetCommonChoices
import com.finance.tracker.business.domain.validateCashWriteSetEntryResults

/** Only first-write joint batches: the caller must consume exact duplicates without executing B again. */
internal fun applyCashImportWriteSetFirstCommonChoices(context:CashSqlContext,changes:CashWriteSetDto,
    before:LedgerFactsDto,resolve:(CashWriteReferenceDto)->Long) {
    context.requireTransactionStamp()
    val relations=changes.relations.associateBy {it.target.reference}
    val groups=prepareCashImportWriteSetCommonChoices(changes,before).flatMap {it}
        .filter {relations.getValue(it.relations.first()).state=="confirmed"}
    val parents=groups.map {group ->group.relations.flatMap {reference ->val row=relations.getValue(reference)
        listOfNotNull(row.primaryTransaction.reference,row.secondaryTransaction?.reference)
    }.distinct()}
    val identifiers=linkedMapOf<CashWriteReferenceDto,Long>()
    val claimed=mutableSetOf<Long>()
    for(reference in parents.flatten().distinct()) {
        val id=resolve(reference)
        if(id<=0 || !claimed.add(id)) throw CashPolicyError("entry_result_invalid",listOf("relation_groups"))
        identifiers[reference]=id
    }
    for((index,group) in groups.withIndex()) context.entries.chooseCommon(
        parents[index].map(identifiers::getValue).sortedBy(Long::toString),group.commonFields.mapValues {cashSourceValue(it.value)})
}

/** Source writes must not have applied C; B and reconciliation must not have changed the observed entries yet. */
internal fun applyCashImportWriteSetEntryEdits(context:CashSqlContext,changes:CashWriteSetDto,
    before:LedgerFactsDto,resolve:(CashWriteReferenceDto)->Long) {
    context.requireTransactionStamp()
    val rows=prepareCashImportWriteSetEntryEdits(changes,before).transactions.filter {it.entryEdit!=null}
    val identifiers=linkedMapOf<CashWriteReferenceDto,Long>()
    val claimed=mutableSetOf<Long>()
    for(row in rows) {
        val id=resolve(row.target.reference)
        if(id<=0 || !claimed.add(id)) throw CashPolicyError("entry_result_invalid",listOf("transactions"))
        identifiers[row.target.reference]=id
    }
    for(row in rows) {
        val edit=checkNotNull(row.entryEdit)
        context.entries.applyEdit(identifiers.getValue(row.target.reference),edit.patch.mapValues {cashSourceValue(it.value)},
            edit.entry?.let {checkNotNull(it.expectedRevision).toLong()})
    }
}

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
