package com.finance.tracker.backend

import com.finance.tracker.business.domain.CashStatementAmountValues
import com.finance.tracker.business.domain.CashStatementRowNormalizationRules
import java.math.BigDecimal

internal typealias ParsedCashRow = com.finance.tracker.business.domain.ParsedCashRow
internal typealias ParsedCashDocument = com.finance.tracker.business.domain.ParsedCashDocument

internal object CashStatementRowNormalization {
    private val rules = CashStatementRowNormalizationRules(
        amounts = CashStatementAmountValues<BigDecimal>(
            fromCash = { cash ->
                val representation = cash.representation
                val value = BigDecimal(representation.coefficient.ifEmpty { "0" }).scaleByPowerOfTen(representation.exponent.toInt())
                if (representation.negative) value.negate() else value
            }, sign = BigDecimal::signum, absolute = { it.abs() }, negate = { it.negate() }, plain = { it.toPlainString() },
            zeroPlain = { text -> try { BigDecimal(text).abs().toPlainString() } catch (_: NumberFormatException) { "0" } },
        ),
        timestamp = { value, source -> nativeDate { normalizeStatementTimestamp(value, source) } },
        basicDate = { value -> nativeDate { java.time.LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).toString() } },
        classify = CashRecordClassification::classify,
        account = CashRecordClassification::normalizeAccount,
        textValue = ::text,
        uppercase = String::uppercase,
        sourcePattern = ::Regex,
        freezeNativeRow = { record ->
            java.util.Collections.unmodifiableMap(LinkedHashMap(record).apply {
                val payload = record["_source_payload"] as? Map<*, *>
                if (payload != null) this["_source_payload"] = java.util.Collections.unmodifiableMap(LinkedHashMap(payload))
            })
        },
    )

    fun normalizeDocument(document: CashStatementDocument): ParsedCashDocument = rules.normalizeDocument(document)
    fun parseCashStatement(content: ByteArray, source: String? = null, password: String? = null): ParsedCashDocument =
        normalizeDocument(CashStatementDocuments.readDocument(content, source, password))
    fun normalizePlatformDocument(document: CashStatementDocument, observeNativeRow: ((Int, Map<String, Any?>) -> Unit)? = null) =
        rules.normalizePlatformDocument(document, observeNativeRow)
    fun normalizeBankDocument(document: CashStatementDocument) = rules.normalizeBankDocument(document)
    fun normalizeIcbcDocument(document: CashStatementDocument, observeNativeRow: ((Int, Map<String, Any?>) -> Unit)? = null) =
        rules.normalizeIcbcDocument(document, observeNativeRow)

    private fun nativeDate(block: () -> String): String = try { block() }
        catch (error: java.time.DateTimeException) { throw IllegalArgumentException(error.message, error) }
    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> if (value) "True" else "False"
        is BigDecimal -> value.toPlainString()
        else -> value.toString()
    }
}
