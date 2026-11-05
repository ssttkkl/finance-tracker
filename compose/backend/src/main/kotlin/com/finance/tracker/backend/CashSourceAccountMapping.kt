package com.finance.tracker.backend

import java.math.BigDecimal

internal class CashAccountMappingFailure(message: String) : RuntimeException(message)

internal data class SourceAccountGroup(
    val groupId: String,
    val sourceType: String,
    val identityKind: String,
    val sourceAccountKey: String,
    val displayName: String,
    val maskedEvidence: String,
    val currencies: List<String>,
    val rowCount: Int,
    val legacySourceAccountKeys: List<String> = emptyList(),
)

internal data class SourceRowIssue(val rowIndex: Int, val code: String)

/** 来源账户扫描与支付宝支付组件；不查询账户、不保存映射。 */
internal object CashSourceAccountMapping {
    private val cashSources = setOf("alipay", "wechat", "icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia")
    private val nonFundingMarkers = listOf("红包", "立减", "优惠", "抵扣", "福利金", "政府补贴", "券", "骑行卡",
        "天天减", "每日必减")
    private val amountToken = Regex("(?U)(?:¥|￥)\\s*(\\d+(?:\\.\\d+)?)|\\b(\\d+(?:\\.\\d+)?)\\s*元")
    private val installment = Regex("(?U)分期\\s*[（(]\\s*\\d+\\s*期\\s*[）)]")
    private val remainingInstallment = Regex("(?U)\\s*[（(]?\\d+\\s*期[）)]?")

    fun parseAlipayPaymentComponents(row: Map<String, Any?>): List<LinkedHashMap<String, Any?>> {
        if (sourceTypeOf(row) != "alipay") return emptyList()
        val raw = text(row["payment_method"]).ifEmpty { "支付宝余额" }
        val result = mutableListOf<LinkedHashMap<String, Any?>>()
        for (token in raw.split('&')) {
            val sourceLabel = text(token)
            val normalized = normalizeAlipayComponent(sourceLabel)
            if (normalized.isEmpty()) continue
            val explicit = amountToken.find(sourceLabel)?.let { match ->
                BigDecimal(pythonDecimalText(match.groupValues[1].ifEmpty { match.groupValues[2] })).toPlainString()
            }
            result += linkedMapOf(
                "ordinal" to result.size,
                "source_label" to sourceLabel,
                "account_key" to normalized,
                "amount" to explicit,
                "amount_required" to (explicit == null),
            )
        }
        if (result.isEmpty()) result += linkedMapOf(
            "ordinal" to 0, "source_label" to raw, "account_key" to "支付宝余额",
            "amount" to null, "amount_required" to true)
        val kind = if (result.size > 1) "aggregate" else "atomic"
        for (component in result) component["kind"] = kind
        return result
    }

    fun alipayComponentAllocationStatus(row: Map<String, Any?>): String {
        val components = parseAlipayPaymentComponents(row)
        if (components.size == 1) return "atomic"
        return if (components.all { it["amount_required"] == false }) "ready" else "requires_allocation"
    }

    fun buildComponentAllocationDraft(row: Map<String, Any?>, allocations: Any? = null): Map<String, Any?> {
        val components = parseAlipayPaymentComponents(row)
        val total = try { BigDecimal(text(row["amount"]).ifEmpty { "0" }) }
        catch (_: NumberFormatException) { throw CashAccountMappingFailure("import_component_amount_invalid") }
        if (components.size == 1 && components[0]["amount"] == null) {
            components[0]["amount"] = total.toPlainString(); components[0]["amount_required"] = false
        }
        val supplied = when (allocations) {
            is Map<*, *> -> allocations["components"].takeIf { truthy(it) } ?: allocations["allocations"]
            is List<*> -> allocations
            else -> null
        } as? List<*>
        if (supplied != null) {
            if (supplied.size != components.size) throw CashAccountMappingFailure("import_component_allocation_incomplete")
            for ((component, value) in components.zip(supplied)) {
                val rawAmount = if (value is Map<*, *>) value["amount"] else value
                val amount = try { BigDecimal(pythonDecimalText(text(rawAmount))) }
                catch (_: NumberFormatException) { throw CashAccountMappingFailure("import_component_amount_invalid") }
                if (amount.signum() < 0) throw CashAccountMappingFailure("import_component_amount_invalid")
                component["amount"] = (if (total.signum() >= 0) amount else amount.negate()).toPlainString()
                component["amount_required"] = false
                if (value is Map<*, *>) {
                    val accountId = value["account_id"]
                    if (accountId != null && pythonStr(accountId).isNotEmpty()) component["account_id"] = pythonInt(accountId)
                }
            }
        } else if (total.signum() < 0 && components.all { item ->
                item["amount"] == null || BigDecimal(item["amount"].toString()).signum() >= 0
            }) {
            for (component in components) component["amount"]?.let {
                component["amount"] = BigDecimal(it.toString()).negate().toPlainString()
            }
        }
        val amounts = components.mapNotNull { it["amount"]?.let { value -> BigDecimal(value.toString()) } }
        val complete = amounts.size == components.size
        val conserved = complete && amounts.fold(BigDecimal.ZERO) { sum, value -> sum + value }.compareTo(total) == 0
        return linkedMapOf(
            "record_id" to pythonStr(firstTruthy(row["record_id"], row["_fact_id"]) ?: ""),
            "cash_granularity" to if (components.size > 1) "aggregate" else "atomic",
            "status" to if (conserved) "ready" else if (components.size > 1) "requires_allocation" else "ready",
            "total_amount" to total.toPlainString(),
            "components" to components,
            "conserved" to conserved,
        )
    }

    fun sourceComponentIdentityKeys(row: Map<String, Any?>): List<List<String>> {
        val sourceType = sourceTypeOf(row)
        if (sourceType == "alipay") {
            if (text(row["payment_method"]).isEmpty() && !alipayAmountIsZero(row)) return emptyList()
            return parseAlipayPaymentComponents(row).mapNotNull { component ->
                text(component["account_key"]).takeIf { it.isNotEmpty() }?.let { listOf(sourceType, "payment_method", it) }
            }.distinct()
        }
        if (sourceType == "wechat") {
            val key = normalizeWechatPaymentIdentity(row["payment_method"])
            return if (key.isEmpty()) emptyList() else listOf(listOf(sourceType, "payment_method", key))
        }
        return try {
            val identity = identityForRow(row)
            listOf(listOf(identity.first, identity.second, identity.third))
        } catch (_: CashAccountMappingFailure) {
            emptyList()
        }
    }

    fun sourceIdentityKey(row: Map<String, Any?>): Triple<String, String, String> = identityForRow(row).let {
        Triple(it.first, it.second, it.third)
    }

    fun scanSourceRowsWithIssues(rows: List<Map<String, Any?>>): Pair<List<SourceAccountGroup>, List<SourceRowIssue>> {
        val groups = linkedMapOf<Triple<String, String, String>, GroupBuilder>()
        val issues = mutableListOf<SourceRowIssue>()
        for ((rowIndex, row) in rows.withIndex()) {
            val identities = try {
                sourceComponentIdentityKeys(row).ifEmpty { throw CashAccountMappingFailure("业务行无法识别来源账户") }
            } catch (failure: CashAccountMappingFailure) {
                val code = failure.message
                if (code == "import_composite_payment_unresolved") {
                    issues += SourceRowIssue(rowIndex, code)
                    continue
                }
                throw failure
            }
            for (identity in identities) {
                val sourceType = identity[0]; val identityKind = identity[1]; val sourceKey = identity[2]
                val displayEvidence = if (sourceType == "alipay") sourceKey to sourceKey
                else if (sourceType == "wechat")
                    identityForRow(row + ("payment_method" to sourceKey)).let { it.fourth to it.fifth }
                else identityForRow(row).let { it.fourth to it.fifth }
                val entry = groups.getOrPut(Triple(sourceType, identityKind, sourceKey)) {
                    GroupBuilder().apply {
                        displayName = displayEvidence.first
                        evidence = displayEvidence.second
                    }
                }
                entry.currencies += text(if (truthy(row["currency"])) row["currency"] else "CNY").uppercase()
                entry.rowCount++
                entry.legacy += legacySourceAccountKeys(row, sourceKey)
            }
        }
        return groups.map { (key, value) ->
            SourceAccountGroup(groupId(key.first, key.second, key.third), key.first, key.second, key.third,
                value.displayName, value.evidence, value.currencies.sorted(), value.rowCount,
                value.legacy.sorted())
        } to issues
    }

    fun scanSourceRows(rows: List<Map<String, Any?>>): List<SourceAccountGroup> {
        val (groups, issues) = scanSourceRowsWithIssues(rows)
        if (issues.isNotEmpty()) throw CashAccountMappingFailure(issues.first().code)
        return groups
    }

    private fun identityForRow(row: Map<String, Any?>): Identity {
        val sourceType = sourceTypeOf(row)
        if (sourceType !in cashSources) throw CashAccountMappingFailure("账单记录缺少受支持的来源账户身份")
        var displayName = text(row["source_display_name"])
        val sourceKey: String
        val identityKind: String
        val evidence: String
        when (sourceType) {
            "alipay", "wechat" -> {
                val keys = sourceComponentIdentityKeys(row)
                if (keys.size != 1) throw CashAccountMappingFailure("import_composite_payment_unresolved")
                sourceKey = keys[0][2]
                identityKind = "payment_method"
                if (sourceKey.isEmpty()) throw CashAccountMappingFailure("业务行无法识别来源账户")
                if (displayName.isEmpty()) displayName = sourceKey
                evidence = displayName
            }
            "icbc_credit" -> {
                sourceKey = normalizeIcbcIdentifier(pythonStr(firstTruthy(row["_source_account_identifier"],
                    row["file_account_key"]) ?: ""))
                identityKind = "file_account"
                if (sourceKey.isEmpty()) throw CashAccountMappingFailure("业务行无法识别来源账户")
                if (displayName.isEmpty()) displayName = "信用卡"
                evidence = "$displayName（尾号 ${cardTail(sourceKey)}）"
            }
            "ccb_debit" -> {
                sourceKey = cardTail(row["card_number"])
                identityKind = "card_tail"
                if (sourceKey.isEmpty()) throw CashAccountMappingFailure("业务行无法识别来源账户")
                if (displayName.isEmpty()) displayName = "建设银行"
                evidence = "$displayName（尾号 $sourceKey）"
            }
            "icbc_asia" -> {
                sourceKey = text(firstTruthy(row["_source_account_identifier"]))
                identityKind = "account_identifier"
                if (sourceKey.isEmpty()) throw CashAccountMappingFailure("业务行无法识别来源账户")
                if (displayName.isEmpty()) displayName = "工银亚洲活期账户"
                evidence = "$displayName（尾号 ${cardTail(row["card_number"]).ifEmpty { "未知" }}）"
            }
            else -> {
                sourceKey = normalizeIcbcIdentifier(pythonStr(firstTruthy(row["_source_account_identifier"],
                    row["file_account_key"]) ?: ""))
                identityKind = "file_account"
                if (sourceKey.isEmpty()) throw CashAccountMappingFailure("业务行无法识别来源账户")
                if (displayName.isEmpty()) displayName = "工商银行借记卡"
                evidence = "$displayName（尾号 ${cardTail(sourceKey).ifEmpty { "未知" }}）"
            }
        }
        return Identity(sourceType, identityKind, sourceKey, displayName, evidence)
    }

    private fun legacySourceAccountKeys(row: Map<String, Any?>, sourceKey: String): List<String> {
        val sourceType = sourceTypeOf(row)
        if (sourceType !in setOf("alipay", "wechat")) return emptyList()
        val raw = text(row["payment_method"])
        if (raw.isEmpty()) return emptyList()
        if (sourceType == "alipay") {
            if (raw.split('&').none { normalizeAlipayComponent(text(it)).isNotEmpty() }) return emptyList()
        } else if (normalizeWechatPaymentIdentity(raw) == raw) return emptyList()
        return if (raw != sourceKey) listOf(raw) else emptyList()
    }

    private fun groupId(sourceType: String, identityKind: String, sourceKey: String): String =
        "group_" + sourceDigest(sourceType, identityKind, sourceKey).take(24)

    internal fun normalizeAlipayComponent(value: String): String {
        var component = text(value)
        if (component.isEmpty() || nonFundingMarkers.any { it in component }) return ""
        component = installment.replace(component, "")
        component = component.replace("分期", "")
        component = remainingInstallment.replace(component, "").trim()
        return if (component in setOf("账户余额", "余额")) "支付宝余额" else component
    }

    internal fun normalizeWechatPaymentIdentity(value: Any?): String {
        val method = text(value)
        return if (method in setOf("/", "零钱", "微信零钱")) "微信零钱" else method
    }

    private fun alipayAmountIsZero(row: Map<String, Any?>): Boolean = try {
        BigDecimal(text(row["amount"]).ifEmpty { "0" }).signum() == 0
    } catch (_: NumberFormatException) { false }

    private fun cardTail(value: Any?): String {
        val digits = text(value).replace(Regex("[^0-9]"), "")
        return if (digits.length < 4) "" else digits.takeLast(4)
    }

    private fun normalizeIcbcIdentifier(value: String): String {
        val text = value.replace(Regex("[\\s\\-－—()（）]"), "")
        return when {
            Regex("(?U)\\d{12,19}").matches(text) -> text
            Regex("(?U)\\d{4,6}[*＊]{2,}\\d{4}").matches(text) -> text.replace('＊', '*')
            else -> ""
        }
    }

    private fun sourceTypeOf(row: Map<String, Any?>): String =
        text(firstTruthy(row["bill_source"], row["source_type"]))

    private fun firstTruthy(vararg values: Any?): Any? = values.firstOrNull(::truthy)

    private fun pythonStr(value: Any?): String = when (value) {
        null -> "None"
        is Boolean -> if (value) "True" else "False"
        is String -> value
        is BigDecimal -> value.toString()
        else -> value.toString()
    }

    private fun pythonInt(value: Any?): Long = when (value) {
        is Boolean -> if (value) 1 else 0
        is Number -> value.toDouble().toLong()
        else -> pythonStr(value).trim { it.code in pythonWhitespace }.toLong()
    }

    private fun text(value: Any?): String = when (value) {
        null -> ""
        is String -> value.trim { it.code in pythonWhitespace }
        is Boolean -> if (value) "True" else "False"
        is BigDecimal -> value.toString()
        else -> value.toString().trim { it.code in pythonWhitespace }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Number -> value.toDouble() != 0.0
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> value.isNotEmpty()
        else -> true
    }

    private fun pythonDecimalText(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            val width = Character.charCount(code)
            val start = pythonIntegerDigitStarts.firstOrNull { code in it until it + 10 }
            when {
                code == '_'.code || code in pythonWhitespace -> Unit
                start != null -> result.append(('0'.code + code - start).toChar())
                else -> result.appendCodePoint(code)
            }
            index += width
        }
        return result.toString()
    }

    private data class Identity(val first: String, val second: String, val third: String,
        val fourth: String, val fifth: String)

    private class GroupBuilder {
        var displayName: String = ""
        var evidence: String = ""
        val currencies = mutableSetOf<String>()
        var rowCount = 0
        val legacy = mutableSetOf<String>()
    }
}
