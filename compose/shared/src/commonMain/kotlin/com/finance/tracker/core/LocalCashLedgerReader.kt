package com.finance.tracker.core

import com.finance.tracker.business.contracts.CashCurrentRecordDto
import com.finance.tracker.business.contracts.CashSourceEvidenceDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import com.finance.tracker.business.contracts.CashFiltersDto
import com.finance.tracker.business.contracts.CashPageDto
import com.finance.tracker.business.contracts.CashMutationProjectionRefDto
import com.finance.tracker.business.contracts.LedgerSnapshotDto
import com.finance.tracker.business.domain.LocalCashLedgerQuery
import com.finance.tracker.business.domain.normalizeLocalCashLedgerQueryInput

/** A committed capture, using the same chosen zone for filtering and monthly totals. */
internal class LocalCashLedgerReader(snapshot: LedgerSnapshotDto) {
    private val query = LocalCashLedgerQuery(snapshot, ::localCashLedgerMonth)
    private val ledgerSeq = snapshot.ledgerSeq.toLong()
    private val decoder = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun currentGroup(projectionId: String) = query.currentGroup(projectionId)
    fun record(transactionId: String, canWrite: Boolean): CashCurrentRecordDto = decoder.decodeFromJsonElement(query.record(transactionId, canWrite))
    fun sourceRecords(projectionId: String, canWrite: Boolean): LocalCashSourceRecords {
        val evidence = decoder.decodeFromJsonElement<CashSourceEvidenceDto>(query.evidence(projectionId))
        return LocalCashSourceRecords(evidence, evidence.members.associate { it.id to record(it.id, canWrite) })
    }

    fun projectionRefs(transactionIds: List<String>) = LocalCashProjectionReferences(ledgerSeq, query.projectionRefs(transactionIds))

    fun page(filters: CashFiltersDto = CashFiltersDto(), timeZoneId: String? = null,
        cursor: String? = null, startIndex: Int? = null, expectedLedgerSeq: Long? = null, limit: Int = 20): CashPageDto =
        query.page(normalizeLocalCashLedgerQueryInput(filters, timeZoneId, ::localCashLedgerDateBounds),
            cursor, startIndex, expectedLedgerSeq, limit)
}

internal data class LocalCashProjectionReferences(val projectionVersion: Long, val refs: List<CashMutationProjectionRefDto>)

/** One immutable capture for the evidence and every source member's complete current record. */
internal data class LocalCashSourceRecords(val evidence: CashSourceEvidenceDto, val records: Map<String, CashCurrentRecordDto>)
