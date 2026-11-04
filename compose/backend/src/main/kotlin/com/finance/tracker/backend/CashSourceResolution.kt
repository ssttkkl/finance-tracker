package com.finance.tracker.backend

import java.time.Instant
import java.util.UUID

internal typealias SourceRecord = com.finance.tracker.business.domain.SourceRecord
internal typealias SourceResolution = com.finance.tracker.business.domain.SourceResolution
internal typealias DocumentPageProof = com.finance.tracker.business.domain.DocumentPageProof

/** JVM value adapters; all source matching decisions are shared. */
internal object CashSourceResolver {
    private val rules = com.finance.tracker.business.domain.CashSourceResolver(
        prepareFields = { current, patch, protected -> CashRecordPolicy.prepare(current, patch, protected) },
        indexValue = ::indexOf,
        digestValues = { values -> sourceDigest(*values.toTypedArray()) },
        coverageComparable = { first, second, value -> first.comparable(second, value) },
    )
    fun resolveSourceFile(rows: List<SourceOccurrence>, records: List<SourceRecord>): List<SourceResolution> =
        rules.resolveSourceFile(rows, records)
    fun resolveSourceRows(rows: List<SourceOccurrence>, records: List<SourceRecord>,
        targetsByOccurrence: Map<String, Set<String>> = emptyMap()): List<SourceResolution> =
        rules.resolveSourceRows(rows, records, targetsByOccurrence)
    fun conflictingDocumentPages(coverages: List<CashSourceCoverage>): Set<Pair<String, String>> =
        rules.conflictingDocumentPages(coverages)
    fun conflictingSourceDocumentPages(rows: List<SourceOccurrence>, records: List<SourceRecord> = emptyList(),
        evidence: List<SourceDocumentEvidence> = emptyList()): Set<DocumentPageProof> =
        rules.conflictingSourceDocumentPages(rows, records, evidence)
    internal fun patch(row: SourceOccurrence): Map<String, Any?> = rules.patch(row)
    internal fun recordBindingKeys(record: SourceRecord): Set<String> = rules.recordBindingKeys(record)
    internal fun compatible(row: SourceOccurrence, old: SourceOccurrence): Boolean = rules.compatible(row, old)

    fun allocateSourceRecordId(row: SourceOccurrence): String =
        row.providerBindingKey?.let { "provider-v1:$it" } ?: "import-v1:${UUID.randomUUID()}"

    internal fun indexOf(fields: Map<String, Any?>): Index? {
        val amount = fields["amount"] ?: return null
        val currency = fields["currency"] as? String ?: return null
        val occurred = fields["occurred_at"] ?: return null
        val cash = CashAmount.parseOrNull(amount.toString()) ?: return null
        val time = try { pythonCashTimestamp(occurred) } catch (_: Exception) { return null }
        val value = java.math.BigDecimal(cash.representation.coefficient.ifEmpty { "0" })
            .scaleByPowerOfTen(cash.representation.exponent.toInt()).stripTrailingZeros().toPlainString()
        return Index(time, if (cash.representation.negative && value != "0") "-$value" else value, currency)
    }

    internal data class Index(val occurredAt: Instant, val amount: String, val currency: String)
}
