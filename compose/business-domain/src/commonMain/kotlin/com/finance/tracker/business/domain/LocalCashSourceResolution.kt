package com.finance.tracker.business.domain

import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Resolve normalized source rows against records selected from one authoritative local snapshot. */
fun resolveLocalCashSourceRows(rows: List<SourceOccurrence>, records: List<SourceRecord>,
    targetsByOccurrence: Map<String, Set<String>> = emptyMap()): List<SourceResolution> =
    localCashSourceResolver.resolveSourceRows(rows, records, targetsByOccurrence)

fun resolveLocalCashSourceFile(rows: List<SourceOccurrence>, records: List<SourceRecord>): List<SourceResolution> =
    localCashSourceResolver.resolveSourceFile(rows, records)

/** Batch deduplication only; selecting eligible cached records is the caller's read-port responsibility. */
fun resolveLocalCashSourceBatch(input: SourceBatchRows, records: List<SourceRecord>,
    knownDocumentEvidence: List<SourceDocumentEvidence> = emptyList()): SourceBatchPlan =
    localCashSourceBatch.resolve(input, records, knownDocumentEvidence)

/** Allocate once per new intent; persist an unbound row's random identity before retrying a submission. */
@OptIn(ExperimentalUuidApi::class)
fun allocateLocalCashSourceRecordId(row: SourceOccurrence): String =
    row.providerBindingKey?.let { "provider-v1:$it" } ?: "import-v1:${Uuid.random()}"

internal val localCashSourceResolver by lazy {
    CashSourceResolver(
        prepareFields = LocalCashRecordValues::prepare,
        indexValue = ::localCashSourceIndex,
        digestValues = ::localCashSourceDigest,
    )
}

private val localCashSourceBatch by lazy {
    CashSourceBatch(
        resolver = localCashSourceResolver,
        normalizePatch = { patch -> LocalCashRecordValues.prepare(mapOf("state" to "draft"), patch).normalizedValues },
        contentValue = ::localCashSourceContent,
        digestValues = ::localCashSourceDigest,
    )
}

internal fun localCashSourceDigest(values: List<Any?>): String =
    sha256Hex(encodeLocalCashValue(values).toString().encodeToByteArray(throwOnInvalidSequence = true))

internal data class LocalCashSourceIndex(val occurredAt: Instant, val amount: CashAmount, val currency: String)
private fun localCashSourceIndex(fields: Map<String, Any?>): LocalCashSourceIndex? {
    val amount = fields["amount"] ?: return null
    val currency = fields["currency"] as? String ?: return null
    val occurred = fields["occurred_at"] ?: return null
    val cash = CashAmount.parseOrNull(amount.toString()) ?: return null
    val time = try { LocalCashRecordValues.normalize("occurred_at", occurred) as Instant }
        catch (_: Exception) { return null }
    return LocalCashSourceIndex(time, cash, currency)
}

private fun localCashSourceContent(value: Any?): Any? = when (value) {
    is CashAmount -> {
        val decimal = value.representation
        val digits = decimal.coefficient.trimEnd('0')
        mapOf("decimal" to listOf(SourceJsonInteger(if (digits.isEmpty() || !decimal.negative) 0 else 1),
            digits.map { SourceJsonInteger(it.digitToInt()) },
            if (digits.isEmpty()) 0L else decimal.exponent + decimal.coefficient.length - digits.length))
    }
    is Instant -> {
        val text = localCashInstantText(value).removeSuffix("Z")
        // OffsetDateTime omits seconds only at an exact minute, unlike Instant.
        (if (value.nanosecondsOfSecond == 0 && value.epochSeconds % 60 == 0L) text.dropLast(3) else text) + "+00:00"
    }
    is Map<*, *> -> value.mapValues { localCashSourceContent(it.value) }
    is List<*> -> value.map(::localCashSourceContent)
    else -> value
}
