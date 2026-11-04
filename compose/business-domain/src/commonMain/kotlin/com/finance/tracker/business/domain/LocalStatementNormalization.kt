package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.CashStatementDocument

/** Normalize a decoded source document locally with production shared scalar rules. */
fun normalizeLocalCashStatement(document: CashStatementDocument): ParsedCashDocument =
    localCashStatementRules.normalizeDocument(document)

internal val localCashStatementRules by lazy {
    val types = CashRecordTypeRules(::statementTruthyText, ::statementTruthy)
    val accounts = CounterpartyAccountRules(::statementTruthyText, ::statementPythonText, ::statementStringPayloadDigest)
    CashStatementRowNormalizationRules(
        amounts = CashStatementAmountValues<LedgerArithmeticValue>(
            // All zero arithmetic is the same; the source's signed/scale text is kept separately by Amount.
            fromCash = { if (it.representation.coefficient == "0") LedgerArithmeticValue.ZERO else LedgerArithmeticValue.fromCashAmount(it) },
            sign = { it.signum }, absolute = { it.abs() }, negate = { it.negate() },
            plain = { it.toPlainString() }, zeroPlain = ::statementZeroPlain,
        ),
        timestamp = ::normalizeStatementTimestamp,
        basicDate = { value ->
            require(Regex("[0-9]{8}").matches(value))
            shiftCashCandidateDay("${value.take(4)}-${value.substring(4, 6)}-${value.substring(6, 8)}", 0)
        },
        classify = types::classify,
        account = accounts::normalizeAccount,
        textValue = { value -> when (value) {
            null -> ""
            is LedgerArithmeticValue -> value.toPlainString()
            else -> statementPythonText(value)
        } },
        uppercase = ::pythonCashUpper,
        sourcePattern = ::localStatementPattern,
        freezeNativeRow = { row -> buildMap {
            putAll(row)
            val payload = row["_source_payload"] as? Map<*, *>
            if (payload != null) put("_source_payload", buildMap { putAll(payload) })
        } },
    )
}

private fun statementPythonText(value: Any?): String = when (value) {
    null -> "None"
    is Boolean -> if (value) "True" else "False"
    is LedgerArithmeticValue -> value.toString()
    else -> value.toString()
}
private fun statementTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is LedgerArithmeticValue -> value.signum != 0
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    is Collection<*> -> value.isNotEmpty()
    is Map<*, *> -> value.isNotEmpty()
    else -> true
}
private fun statementTruthyText(value: Any?): String = when (value) {
    null -> ""
    is Boolean -> if (value) "True" else ""
    is LedgerArithmeticValue -> if (value.signum == 0) "" else value.toString()
    is Number -> if (value.toDouble() == 0.0) "" else statementPythonText(value)
    else -> statementPythonText(value)
}

/** Only decoded statement payloads: both keys and cells are strings, never arbitrary JSON. */
internal fun statementStringPayloadDigest(value: Any?): String {
    if (value !is Map<*, *> || value.isEmpty() || value.any { (key, cell) -> key !is String || cell !is String }) return ""
    return try {
        val json = sourceJson(value, { null }, ::pythonFloatText).toString()
        sha256Hex(json.encodeToByteArray(throwOnInvalidSequence = true))
    } catch (_: IllegalArgumentException) {
        ""
    } catch (_: CharacterCodingException) {
        ""
    }
}

private val statementDecimalLexical = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
/** Match BigDecimal's accepted exponent/scale and plain zero text after source character normalization. */
internal fun statementZeroPlain(text: String): String {
    if (!statementDecimalLexical.matches(text)) return "0"
    val marker = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (marker < 0) text else text.take(marker)
    val exponent = if (marker < 0) 0L else text.substring(marker + 1).toLongOrNull() ?: return "0"
    if (exponent !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return "0"
    val point = mantissa.indexOf('.')
    val scale = (if (point < 0) 0 else mantissa.length - point - 1).toLong() - exponent
    if (scale !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) return "0"
    return if (scale <= 0) "0" else "0." + "0".repeat(scale.toInt())
}

private val statementNativeDigitClass = pythonIntegerDigitStarts.joinToString("", "[", "]") {
    "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}"
}
private val statementNativeSpaces = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
/** Compile only the row normalizer's existing finite JVM pattern vocabulary on all targets. */
internal fun localStatementPattern(pattern: String): Regex {
    // Bare JVM \d is ASCII. Explicit source Nd and whitespace sets are frozen Python data.
    val portable = pattern.replace("\\d", "[0-9]")
        .replace(statementNativeDigitClass, "\\d")
        .replace(statementNativeSpaces, "\\s")
    check("\\x{" !in portable)
    return cashSourceEffectRegex(portable, unixLines = false)
}
