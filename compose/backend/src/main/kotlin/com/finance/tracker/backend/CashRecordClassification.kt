package com.finance.tracker.backend

import java.math.BigDecimal

/** 来源行记录类型/子类型与对方账号的公共规则；只读来源字段，不跨行配对。 */
internal typealias CashRecordRuleFailure = com.finance.tracker.business.domain.CashRecordRuleFailure

internal class CounterpartyAccount(val value: String, val attrs: List<String>,
    val reconstructionProof: Any? = null) {
    override fun equals(other: Any?): Boolean = other is CounterpartyAccount && value == other.value && attrs == other.attrs
    override fun hashCode(): Int = 31 * value.hashCode() + attrs.hashCode()
    override fun toString(): String = "CounterpartyAccount(value=$value, attrs=$attrs)"
}

internal object CashRecordClassification {
    val validTypes = cashRecordTypes
    val validSubtypes = cashRecordSubtypes
    private val emptyAccountMarkers = setOf("", "/", "-", "--", "(空)", "（空）")
    private val accountAttrs = setOf("full", "tail", "masked", "reconstructed")
    private val accountCombinations = setOf(emptyList<String>(), listOf("full"), listOf("tail"), listOf("masked"),
        listOf("masked", "reconstructed"))
    private val proofToken = Any()
    private val pythonDigitRanges = pythonIntegerDigitStarts.joinToString("") {
        "\\x{${it.toString(16)}}-\\x{${(it + 9).toString(16)}}"
    }
    private val pythonDigitClass = "[$pythonDigitRanges]"
    private val pythonWhitespaceRanges = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val pythonWhitespaceClass = "[$pythonWhitespaceRanges]"
    private val pythonWhitespaceRegex = Regex("$pythonWhitespaceClass+")
    private val accountSeparators = Regex("[$pythonWhitespaceRanges\\-()（）]")
    private val maskedRegex = Regex("($pythonDigitClass+)\\*+($pythonDigitClass+)")
    private val tailRegex = Regex("(?<!$pythonDigitClass)($pythonDigitClass{4})(?!$pythonDigitClass)")
    private val fourDigits = Regex("$pythonDigitClass{4}")

    private val typeRules = com.finance.tracker.business.domain.CashRecordTypeRules(::pythonStrOrEmpty, ::pythonTruthy)
    fun defaultSubtype(recordType: Any?): String = typeRules.defaultSubtype(recordType)
    fun validateSubtype(recordType: Any?, recordSubtype: Any?) = typeRules.validateSubtype(recordType, recordSubtype)
    fun classify(source: String?, row: Map<String, Any?>): Pair<String, String> = typeRules.classify(source, row)
    fun classifyType(source: String?, row: Map<String, Any?>): String = typeRules.classifyType(source, row)

    fun normalizeAccount(value: Any?, source: String = "", sourceAccountIdentifier: String = "",
        sourcePayload: Any? = null): CounterpartyAccount {
        val text = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        if (text in emptyAccountMarkers) return CounterpartyAccount("", emptyList())
        if ("*" in text || "＊" in text) {
            var maskedText = text.replace(pythonWhitespaceRegex, "").replace('＊', '*')
            val compact = maskedText.replace(accountSeparators, "")
            val masked = maskedRegex.matchEntire(compact)
            if (masked != null) maskedText = compact
            if (source == "icbc_asia") {
                val reference = pythonStrOrEmpty(sourceAccountIdentifier).replace(accountSeparators, "")
                if (masked != null && pythonRegexDigit(reference) && reference.length >= 6 &&
                    masked.groupValues[1].length >= 4 && masked.groupValues[2].length >= 2 &&
                    masked.groupValues[1].length + masked.groupValues[2].length < reference.length &&
                    maskedText.length == reference.length && reference.startsWith(masked.groupValues[1])) {
                    val reconstructed = reference.dropLast(masked.groupValues[2].length) + masked.groupValues[2]
                    val digest = sourcePayloadDigest(sourcePayload)
                    val proof = if (digest.isNotEmpty())
                        ReconstructionProof(proofToken, source.trim { it.code in pythonWhitespace }, digest) else null
                    return CounterpartyAccount(reconstructed, listOf("masked", "reconstructed"), proof)
                }
            }
            return CounterpartyAccount(maskedText, listOf("masked"))
        }
        val digits = text.replace(accountSeparators, "")
        if (pythonIsDigit(digits)) {
            if (digits.length < 4) return CounterpartyAccount("", emptyList())
            if (digits.length == 4) return CounterpartyAccount(digits, listOf("tail"))
            return CounterpartyAccount(digits, listOf("full"))
        }
        if ("@" !in text) {
            val tail = tailRegex.find(text)
            if (tail != null) return CounterpartyAccount(tail.groupValues[1], listOf("tail"))
        }
        return CounterpartyAccount(text, listOf("full"))
    }

    fun validateAccount(value: Any?, attrs: Any?) {
        val account = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        if (attrs !is List<*>) throw CashRecordRuleFailure("counterparty_account_attrs must be a JSON array")
        val normalized = attrs.map(::pythonStr)
        if (normalized.any { it !in accountAttrs } || normalized !in accountCombinations)
            throw CashRecordRuleFailure("counterparty_account_attrs has an invalid combination")
        if (account.isEmpty()) {
            if (normalized.isNotEmpty())
                throw CashRecordRuleFailure("counterparty_account_attrs must be empty when account is empty")
            return
        }
        if (normalized.isEmpty())
            throw CashRecordRuleFailure("counterparty_account_attrs is required for a non-empty account")
        if (normalized == listOf("tail") && !fourDigits.matches(account))
            throw CashRecordRuleFailure("counterparty_account_attrs tail requires exactly four digits")
        if (normalized == listOf("full") &&
            ("*" in account || "＊" in account || pythonIsDigit(account) && account.length <= 4))
            throw CashRecordRuleFailure("counterparty_account_attrs full conflicts with account value")
        if (normalized == listOf("masked") && "*" !in account && "＊" !in account)
            throw CashRecordRuleFailure("counterparty_account_attrs masked requires an explicit mask")
        if (normalized == listOf("masked", "reconstructed") && (!pythonIsDigit(account) || account.length <= 4))
            throw CashRecordRuleFailure("counterparty_account_attrs reconstructed requires a full numeric value")
    }

    fun validateAccountForWrite(value: Any?, attrs: Any?, reconstructionProof: Any? = null, sourceType: Any? = "",
        sourcePayload: Any? = null) {
        validateAccount(value, attrs)
        val normalized = (attrs as List<*>).map(::pythonStr)
        if (normalized != listOf("masked", "reconstructed")) return
        val account = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        val accountProof = reconstructionProof as? CounterpartyAccount
        val proof = accountProof?.reconstructionProof as? ReconstructionProof
        val digest = sourcePayloadDigest(sourcePayload)
        if (!(proof != null && proof.token === proofToken && accountProof.value == account &&
            accountProof.attrs == normalized &&
            proof.sourceType == pythonStrOrEmpty(sourceType).trim { it.code in pythonWhitespace } && digest.isNotEmpty() && proof.digest == digest))
            throw CashRecordRuleFailure("counterparty_account_attrs reconstructed requires verified source reconstruction " +
                "from the same source row")
    }

    private data class ReconstructionProof(val token: Any, val sourceType: String, val digest: String)

    internal fun sourcePayloadDigest(value: Any?): String {
        if (value !is Map<*, *> || value.isEmpty()) return ""
        return try {
            tokenDigest(canonicalCashJson(value))
        } catch (_: Exception) {
            ""
        }
    }

    private fun pythonRegexDigit(text: String): Boolean = text.isNotEmpty() &&
        text.codePoints().allMatch { code -> pythonIntegerDigitStarts.any { code in it until it + 10 } }

    private fun pythonIsDigit(text: String): Boolean = text.isNotEmpty() &&
        text.codePoints().allMatch { code -> code in pythonNondecimalDigits ||
            pythonIntegerDigitStarts.any { code in it until it + 10 } }

    private fun pythonTruthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is BigDecimal -> value.signum() != 0
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    internal fun pythonStr(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is String -> value
        is BigDecimal -> value.toString()
        is Double -> value.toString()
        else -> value.toString()
    }

    private fun pythonStrOrEmpty(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "True" else ""
        is String -> value
        is BigDecimal -> if (value.signum() == 0) "" else value.toString()
        is Number -> if (value.toDouble() == 0.0) "" else pythonStr(value)
        else -> value.toString()
    }
}
