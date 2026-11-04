package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.Normalizer
import kotlinx.serialization.json.*

internal typealias CashFundingInputError = com.finance.tracker.business.domain.CashFundingInputError
internal typealias CashFundingPartsTooLarge = com.finance.tracker.business.domain.CashFundingPartsTooLarge
internal typealias CashFundingPart = com.finance.tracker.business.domain.CashFundingPart
internal typealias CashFundingRow = com.finance.tracker.business.domain.CashFundingRow
internal typealias CashFundingScan = com.finance.tracker.business.domain.CashFundingScan
internal typealias CashFundingResolution = com.finance.tracker.business.domain.CashFundingResolution
internal typealias CashFundingGroup = com.finance.tracker.business.domain.CashFundingGroup

internal fun CashFundingRow.json(): JsonObject = json(::sourceJson)
internal fun CashFundingScan.json(): JsonObject = json(::sourceJson)
internal fun CashFundingResolution.json(): JsonObject = json(::sourceJson)

internal object CashImportFunding : CashImportFundingEngine()

internal open class CashImportFundingEngine(private val validatePosting: (Map<*, *>) -> Unit = CashRecordPolicy::validateForPosting) {
    private val money = Regex("(?U)[¥￥]\\s*([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)(?![0-9A-Za-z.,+-])|(?<![0-9A-Za-z.,+-])([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*元")
    private val decimal = Regex("[+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,18})?")
    private val fullNumber = Regex("(?U)[0-9](?:[\\s\\-－—·]*[0-9]){7,}")
    private val pythonWhitespaceRanges = pythonWhitespace.joinToString("") { "\\x{${it.toString(16)}}" }
    private val emailPattern = Regex("([^@${pythonWhitespaceRanges}]{1,64})@([^@${pythonWhitespaceRanges}]{1,253})")
    private val maskedPattern = Regex("[*＊•●]")
    private val bankSources = setOf("icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia")
    private val math = MathContext(80, RoundingMode.HALF_EVEN)
    private fun amount(value: String) = exactCashAmount(value).arithmeticValue()
    protected open fun total(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { sum, value -> sum.add(value, math) }
    private fun label(value: String): String {
        val text = fullNumber.replace(value.replace('\n', ' ').trim { it.code in pythonWhitespace }) {
            "尾号 " + it.value.replace(Regex("[^0-9]"), "").takeLast(4)
        }
        return codePointTail(text, 128)
    }
    private fun codePointTail(value: String, limit: Int): String =
        if (value.codePointCount(0, value.length) <= limit) value else value.substring(0, value.offsetByCodePoints(0, limit))
    private fun collapsed(value: String): String {
        val text = StringBuilder(); var pending = false
        for (char in value) {
            if (char.code in pythonWhitespace) pending = true
            else { if (pending && text.isNotEmpty()) text.append(' '); pending = false; text.append(char) }
        }
        return text.toString()
    }
    private fun sessionAccountLabel(value: String?): String? {
        if (value == null) return null
        val text = codePointTail(collapsed(value.replace('\n', ' ')), 128)
        if (text.isEmpty()) return null
        emailPattern.matchEntire(text)?.let { return codePointTail(it.groupValues[1], 1) + "***@" + it.groupValues[2] }
        if (maskedPattern.containsMatchIn(text)) {
            val digits = text.filter { it in '0'..'9' }
            return if (digits.length >= 4) "尾号 " + digits.takeLast(4) else "账号已遮蔽"
        }
        if (fullNumber.containsMatchIn(text)) return label(text)
        val length = text.codePointCount(0, text.length)
        if (length >= 4) return text.substring(0, text.offsetByCodePoints(0, 1)) + "***" +
            text.substring(text.offsetByCodePoints(0, length - 2))
        if (length == 2) return text.substring(0, text.offsetByCodePoints(0, 1)) + "*"
        if (length == 3) return text.substring(0, text.offsetByCodePoints(0, 1)) + "*" +
            text.substring(text.offsetByCodePoints(0, 2))
        return "账号"
    }
    protected data class SourcePart(val kind: String, val origin: String, val key: String, val label: String, val amount: String?)
    private data class LocalPart(val kind: String, val key: String, val label: String, val amount: String?)
    protected open fun parseAlipayPaymentComponents(row: Map<String, Any?>): List<LinkedHashMap<String, Any?>> =
        CashSourceAccountMapping.parseAlipayPaymentComponents(row)

    private fun alipayParts(method: String, parentAmount: Any?, product: Any?): Pair<List<LocalPart>, List<String>> {
        if (method.count { it == '&' } >= 64) throw CashFundingPartsTooLarge()
        val parts = parseAlipayPaymentComponents(mapOf("source_type" to "alipay",
            "payment_method" to method, "amount" to parentAmount))
        val fallback = method.split('&').none { CashSourceAccountMapping.normalizeAlipayComponent(money.replace(it, "")).isNotEmpty() }
        if (fallback) {
            val wallet = if (product is String && "余额宝" in product) "余额宝" else "支付宝余额"
            return listOf(LocalPart("payment_method", wallet, wallet, null)) to emptyList()
        }
        val issues = mutableListOf<String>()
        val result = parts.mapIndexed { index, part ->
            val token = part.getValue("source_label") as String
            var value: String? = null
            if (token.any { it in "¥￥元" }) {
                try {
                    val matches = money.findAll(token).toList(); require(matches.size == 1)
                    val raw = matches.single().groupValues.drop(1).first { it.isNotEmpty() }
                    require(decimal.matches(raw))
                    value = amount(raw.replace(",", "")).toPlainString()
                } catch (_: IllegalArgumentException) { issues += "allocation_amount_invalid" }
                  catch (_: ArithmeticException) { issues += "allocation_amount_invalid" }
            }
            val key = CashSourceAccountMapping.normalizeAlipayComponent(money.replace(part.getValue("account_key") as String, ""))
            if (key.isNotEmpty()) LocalPart("payment_method", key, label(key), value)
            else { issues += "funding_unrecognized"; LocalPart("unresolved", "unknown:$index", "", value) }
        }
        return result to issues
    }
    protected data class Parts(val parts: List<SourcePart>, val issues: List<String>, val sessionLabel: String?)
    protected open fun parts(file: ParsedImportFile, row: ParsedCashRow): Parts {
        val document = requireNotNull(file.document); val source = document.sourceType
        val owner = row.sourceAccountIdentifier?.takeIf { it.isNotEmpty() } ?: document.sourceAccountIdentifier
        var origin = CashSourceScope.sourceAccountKey(source, owner)
        val local = cashSourceDigest(JsonPrimitive("funding-file-v1"), JsonPrimitive(source),
            JsonPrimitive(file.digest), JsonPrimitive(document.parserVersion))
        var sessionLabel: String? = null
        if (source == "wechat") {
            val nickname = document.sourceNickname?.let { Normalizer.normalize(it.trim { char -> char.code in pythonWhitespace },
                Normalizer.Form.NFC) }.orEmpty()
            if (nickname.isNotEmpty()) {
                origin = cashSourceDigest(JsonPrimitive("wechat-session-nickname-v1"), JsonPrimitive(nickname))
                sessionLabel = nickname
            } else {
                origin = cashSourceDigest(JsonPrimitive("wechat-session-file-v1"), JsonPrimitive(file.fileRef))
                sessionLabel = label(file.filename)
            }
        }
        if (source == "alipay") {
            val accountLabel = sessionAccountLabel(document.sourceAccountLabel?.takeIf { it.isNotEmpty() } ?: owner)
            sessionLabel = if (owner == null) {
                val fileLabel = label(file.filename)
                if (accountLabel != null) "$accountLabel · $fileLabel" else fileLabel
            } else accountLabel ?: label(file.filename)
        }
        val (parts, issues) = when (source) {
            "alipay" -> alipayParts(row.paymentMethod.trim { it.code in pythonWhitespace }, row.fields?.get("amount"),
                (row.fields?.get("source_payload") as? Map<*, *>)?.get("商品说明"))
            "wechat" -> {
                val key = CashSourceAccountMapping.normalizeWechatPaymentIdentity(row.paymentMethod)
                if (key.isEmpty()) listOf(LocalPart("unresolved", "unknown", "", null)) to listOf("funding_unrecognized")
                else listOf(LocalPart("payment_method", key, label(key), null)) to emptyList()
            }
            in bankSources -> {
                val masked = if (source == "icbc_credit" && owner == null) {
                    val fields = requireNotNull(row.fields)
                    val payload = (if ("source_payload" in fields) fields["source_payload"] else emptyMap<String, Any?>())
                        as? Map<*, *> ?: error("funding_source_payload_not_mapping")
                    val cells = payload.entries.associate {
                        val key = it.key as? String ?: error("funding_source_column_not_string")
                        key.replace(Regex("(?U)\\s+"), "") to it.value
                    }
                    val value = (if ("交易卡号" in cells) cells["交易卡号"] else "") as? String
                        ?: error("funding_source_card_not_string")
                    value.replace(Regex("(?U)\\s+"), "")
                        .takeIf { Regex("[0-9]{4,6}[*＊]{2,}[0-9]{4}").matches(it) }?.replace('＊', '*')
                } else null
                val display = row.sourceExplanation["source_display_name"] as? String
                require(!display.isNullOrEmpty()) { "funding_source_display_name_missing" }
                listOf(LocalPart(if (!owner.isNullOrEmpty()) "file_account" else "file_local", owner ?: masked ?: "unknown", display, null)) to emptyList()
            }
            else -> throw IllegalArgumentException("funding_source_unsupported")
        }
        return Parts(parts.map { SourcePart(it.kind, if (it.kind == "file_local") local else origin ?: local,
            it.key, it.label, it.amount) }, issues, sessionLabel)
    }
    private class Group(val ref: String, val source: String, val kind: String, val origin: String, val key: String,
        val label: String, val sessionLabel: String?) {
        val currencies = linkedSetOf<String>(); val rows = linkedSetOf<String>()
        fun value() = CashFundingGroup(ref, source, kind, origin, key, label, sessionLabel,
            currencies.sortedWith(cashRelationStringOrder), rows.toList())
    }
    fun scan(files: List<ParsedImportFile>): CashFundingScan {
        if (files.map { it.fileRef }.distinct().size != files.size) throw CashFundingInputError()
        val groups = linkedMapOf<String, Group>(); val rows = mutableListOf<CashFundingRow>()
        for (file in files) {
            val doc = file.document ?: continue
            if (file.status != "parsed") continue
            for (row in doc.rows) {
                if (row.disposition == "skipped" || row.fields == null) continue
                val ref = "${file.fileRef}:r${row.position}"
                val fields = CashRecordPolicy.copyValue(row.fields) as Map<String, Any?>
                try {
                    val currency = fields["currency"]
                    require(currency == null || currency is String && Regex("[A-Z]{3}").matches(currency)) { "funding_currency_invalid" }
                    val computed = parts(file, row)
                    val values = computed.parts.map { part ->
                        if (part.kind == "unresolved") CashFundingPart(null, part.amount)
                        else {
                            val groupRef = cashSourceDigest(JsonPrimitive("funding-group-v1"), JsonPrimitive(doc.sourceType),
                                JsonPrimitive(part.kind), JsonPrimitive(part.origin), JsonPrimitive(part.key))
                            val group = groups.getOrPut(groupRef) { Group(groupRef, doc.sourceType, part.kind, part.origin,
                                part.key, part.label, computed.sessionLabel) }
                            if (currency is String && currency.isNotEmpty()) group.currencies += currency
                            group.rows += ref
                            CashFundingPart(groupRef, part.amount)
                        }
                    }
                    rows += CashFundingRow(ref, fields, values, (row.issueCodes + computed.issues).distinct())
                } catch (_: CashFundingPartsTooLarge) {
                    rows += CashFundingRow(ref, fields, emptyList(), row.issueCodes, "funding_unreadable")
                }
            }
        }
        return CashFundingScan(groups.values.map { it.value() }, rows)
    }
    private val resolution by lazy {
        com.finance.tracker.business.domain.CashFundingResolutionRules(
            object : com.finance.tracker.business.domain.CashFundingAmountValues<BigDecimal> {
                override fun parse(text: String) = amount(text)
                override fun absolute(value: BigDecimal) = value.abs()
                override fun negate(value: BigDecimal) = value.negate()
                override fun total(values: List<BigDecimal>) = this@CashImportFundingEngine.total(values)
                override fun compare(left: BigDecimal, right: BigDecimal) = left.compareTo(right)
                override fun sign(value: BigDecimal) = value.signum()
                override fun plain(value: BigDecimal) = value.toPlainString()
            },
            { val cache = PostingValidationCache(validatePosting); { record -> cache(record) } },
            { fields -> CashRecordPolicy.copyValue(fields) as Map<String, Any?> },
            { revision -> when (revision) {
                is Byte, is Short, is Int, is Long, is java.math.BigInteger -> revision.toString().toBigInteger().signum() > 0
                else -> false
            } },
        )
    }
    fun resolve(scan: CashFundingScan, selections: Any?, accounts: Any?): List<CashFundingResolution> =
        resolution.resolve(scan, selections, accounts)
}
