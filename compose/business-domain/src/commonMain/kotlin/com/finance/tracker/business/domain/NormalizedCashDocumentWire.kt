package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.NormalizedCashDocumentDto
import com.finance.tracker.business.contracts.NormalizedCashRowDto
import kotlinx.serialization.json.JsonObject

/** Serialize normalized rows using the caller's existing platform scalar adapter. */
fun normalizedCashDocumentWire(document: ParsedCashDocument,
    fields: (Map<String, Any?>) -> JsonObject): NormalizedCashDocumentDto {
    fun count(value: Number) = requireNotNull(value.toString().toLongOrNull()) { "invalid_normalized_count" }
    val observed = count(document.observedCount)
    require(observed in 0..Int.MAX_VALUE)
    return NormalizedCashDocumentDto(document.sourceType, document.tableKey, document.parserVersion,
        document.rows.map { row -> NormalizedCashRowDto(row.position, row.fields?.let(fields), row.paymentMethod,
            row.providerId, row.issueCodes, row.errorCode, row.disposition, row.sourceAccountIdentifier,
            row.documentPosition, fields(row.sourceExplanation)) }, document.sourceAccountIdentifier,
        document.sourceAccountLabel, document.coverage, document.declaredCount?.let(::count), observed.toInt(),
        document.sourceNickname)
}

/** Restore only normalized values; source matching, account selection and relations remain local. */
fun localNormalizedCashDocument(document: NormalizedCashDocumentDto): ParsedCashDocument = ParsedCashDocument(
    document.sourceType, document.tableKey, document.parserVersion,
    document.rows.map { row -> ParsedCashRow(row.position, row.fields?.mapValues { decodeLocalCashValue(it.value) },
        row.paymentMethod, row.providerId, row.issueCodes, row.errorCode, row.disposition, row.sourceAccountIdentifier,
        row.documentPosition, row.sourceExplanation.mapValues { decodeLocalCashValue(it.value) }) },
    document.sourceAccountIdentifier, document.sourceAccountLabel, document.coverage, document.declaredCount,
    document.observedCount, document.sourceNickname,
)
