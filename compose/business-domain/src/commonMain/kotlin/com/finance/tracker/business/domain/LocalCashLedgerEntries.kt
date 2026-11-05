package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerCashEntryFactDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlin.time.Instant

/** Public entry fields remain separate from the original source facts in the derived group. */
data class LocalCashLedgerEntry(val entry:LedgerCashEntryFactDto,val projection:CashProjection<Long,Instant>) {
    val projectionId:String get()="cash-entry:${entry.id}"
    val transactionId:String get()=projection.primaryRecord.id.toString()
}

/** Bind one complete committed snapshot to its existing stable entries, without allocating identities. */
fun buildLocalCashLedgerEntries(snapshot:LedgerSnapshotDto):List<LocalCashLedgerEntry> {
    val projections=buildLocalCashProjections(snapshot).projections
    val members=snapshot.facts.cashEntryMembers.associateBy {it.transactionId}
    val entries=snapshot.facts.cashEntries.associateBy {it.id}
    val used=mutableSetOf<String>()
    fun incomplete():Nothing=throw CashProjectionDomainFailure("projection.incomplete")
    return projections.map {projection ->
        val identities=projection.memberIds.map {members[it.toString()]?.entryId ?: incomplete()}.toSet()
        if(identities.size!=1)incomplete()
        val entry=entries[identities.single()] ?: incomplete()
        if(!used.add(entry.id))incomplete()
        LocalCashLedgerEntry(entry,projection)
    }
}
