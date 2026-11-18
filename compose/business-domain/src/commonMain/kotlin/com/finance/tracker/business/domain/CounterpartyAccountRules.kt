package com.finance.tracker.business.domain

class CounterpartyAccount(val value: String, val attrs: List<String>,
    val reconstructionProof: Any? = null) {
    override fun equals(other: Any?): Boolean = other is CounterpartyAccount && value == other.value && attrs == other.attrs
    override fun hashCode(): Int = 31 * value.hashCode() + attrs.hashCode()
    override fun toString(): String = "CounterpartyAccount(value=$value, attrs=$attrs)"
}

/** Counterparty account decisions over native text and source payload adapters. */
class CounterpartyAccountRules(
    private val textOrEmpty: (Any?) -> String,
    private val textValue: (Any?) -> String,
    private val sourcePayloadDigest: (Any?) -> String,
) {
    private val emptyAccountMarkers = setOf("", "/", "-", "--", "(空)", "（空）")
    private val accountAttrs = setOf("full", "tail", "masked", "reconstructed")
    private val accountCombinations = setOf(emptyList<String>(), listOf("full"), listOf("tail"), listOf("masked"),
        listOf("masked", "reconstructed"))
    private val proofToken = Any()
    private fun pythonStrOrEmpty(value: Any?): String = textOrEmpty(value)
    private fun pythonStr(value: Any?): String = textValue(value)
    private fun isDecimalDigit(code: Int): Boolean = decimalDigitStarts.any { code in it until it + 10 }
    private fun pythonRegexDigit(text: String): Boolean = text.isNotEmpty() && text.pythonCodePoints().all(::isDecimalDigit)
    private fun pythonIsDigit(text: String): Boolean = text.isNotEmpty() && text.pythonCodePoints().all { code ->
        code in nondecimalDigitCodePoints || isDecimalDigit(code)
    }
    private fun withoutWhitespace(text: String): String = text.filterNot { it.code in pythonWhitespace }
    private fun withoutAccountSeparators(text: String): String = text.filterNot { it.code in pythonWhitespace || it in "-()（）" }
    private fun maskedParts(text: String): Pair<String, String>? {
        val first = text.indexOf('*')
        val last = text.lastIndexOf('*')
        if (first < 1 || last == text.lastIndex) return null
        val prefix = text.substring(0, first)
        val suffix = text.substring(last + 1)
        return if (text.substring(first, last + 1).all { it == '*' } && pythonRegexDigit(prefix) && pythonRegexDigit(suffix))
            prefix to suffix else null
    }
    private fun fourDigitTail(text: String): String? {
        val points = text.pythonCodePoints()
        var offset = 0
        for (index in points.indices) {
            // The previous JVM regex's one-character negative lookbehind ignores a preceding
            // supplementary digit. Keep that existing boundary rather than changing identities.
            val before = index == 0 || points[index - 1] > 0xffff || !isDecimalDigit(points[index - 1])
            val end = index + 4
            if (before && end <= points.size && (end == points.size || !isDecimalDigit(points[end])) &&
                (index until end).all { isDecimalDigit(points[it]) }) {
                val width = (index until end).sumOf { if (points[it] > 0xffff) 2 else 1 }
                return text.substring(offset, offset + width)
            }
            offset += if (points[index] > 0xffff) 2 else 1
        }
        return null
    }

    fun normalizeAccount(value: Any?, source: String = "", sourceAccountIdentifier: String = "",
        sourcePayload: Any? = null): CounterpartyAccount {
        val text = pythonStrOrEmpty(value).trim { it.code in pythonWhitespace }
        if (text in emptyAccountMarkers) return CounterpartyAccount("", emptyList())
        if ("*" in text || "＊" in text) {
            var maskedText = withoutWhitespace(text).replace('＊', '*')
            val compact = withoutAccountSeparators(maskedText)
            val masked = maskedParts(compact)
            if (masked != null) maskedText = compact
            if (source == "icbc_asia") {
                val reference = withoutAccountSeparators(pythonStrOrEmpty(sourceAccountIdentifier))
                if (masked != null && pythonRegexDigit(reference) && reference.length >= 6 &&
                    masked.first.length >= 4 && masked.second.length >= 2 &&
                    masked.first.length + masked.second.length < reference.length &&
                    maskedText.length == reference.length && reference.startsWith(masked.first)) {
                    val reconstructed = reference.dropLast(masked.second.length) + masked.second
                    val digest = sourcePayloadDigest(sourcePayload)
                    val proof = if (digest.isNotEmpty())
                        ReconstructionProof(proofToken, source.trim { it.code in pythonWhitespace }, digest) else null
                    return CounterpartyAccount(reconstructed, listOf("masked", "reconstructed"), proof)
                }
            }
            return CounterpartyAccount(maskedText, listOf("masked"))
        }
        val digits = withoutAccountSeparators(text)
        if (pythonIsDigit(digits)) {
            if (digits.length < 4) return CounterpartyAccount("", emptyList())
            if (digits.length == 4) return CounterpartyAccount(digits, listOf("tail"))
            return CounterpartyAccount(digits, listOf("full"))
        }
        if ("@" !in text) {
            val tail = fourDigitTail(text)
            if (tail != null) return CounterpartyAccount(tail, listOf("tail"))
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
        if (normalized == listOf("tail") && !(account.pythonCodePointCount() == 4 && pythonRegexDigit(account)))
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
}
