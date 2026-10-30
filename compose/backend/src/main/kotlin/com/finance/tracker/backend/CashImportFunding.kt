package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.json.*

internal class CashFundingInputError : IllegalArgumentException("funding_selection_invalid")
internal data class CashFundingPart(val groupRef: String?, val amount: String?) {
    fun json() = buildJsonObject { put("group_ref", groupRef?.let(::JsonPrimitive) ?: JsonNull)
        put("amount", amount?.let(::JsonPrimitive) ?: JsonNull) }
}
internal data class CashFundingRow(val rowRef: String, val fields: Map<String, Any?>, val parts: List<CashFundingPart>,
    val issueCodes: List<String> = emptyList(), val errorCode: String? = null) {
    val groupRefs get() = parts.mapNotNull { it.groupRef }.distinct()
    fun json() = buildJsonObject { put("row_ref", rowRef); put("fields", sourceJson(fields))
        put("parts", JsonArray(parts.map { it.json() })); put("issue_codes", sourceJson(issueCodes))
        put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull) }
}
internal data class CashFundingScan(val groups: List<CashFundingGroup>, val rows: List<CashFundingRow>) {
    fun json() = buildJsonObject { put("groups", JsonArray(groups.map { g -> buildJsonObject {
        put("source_account_ref", g.sourceAccountRef); put("source_type", g.sourceType); put("identity_kind", g.identityKind)
        put("origin_key", g.originKey); put("identity_key", g.identityKey); put("display_name", g.displayName)
        put("currencies", sourceJson(g.currencies)); put("row_refs", sourceJson(g.rowRefs))
    } })); put("rows", JsonArray(rows.map { it.json() })) }
}
internal data class CashFundingResolution(val rowRef: String, val fields: Map<String, Any?>, val groupRefs: List<String>,
    val issueCodes: List<String>, val ready: Boolean, val errorCode: String?, val selectedGroups: List<Pair<String, String>>) {
    fun json() = buildJsonObject { put("row_ref", rowRef); put("fields", sourceJson(fields)); put("group_refs", sourceJson(groupRefs))
        put("issue_codes", sourceJson(issueCodes)); put("ready", ready); put("error_code", errorCode?.let(::JsonPrimitive) ?: JsonNull)
        put("selected_groups", JsonArray(selectedGroups.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })) }
}

internal object CashImportFunding {
    private val money = Regex("(?U)[¥￥]\\s*([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)(?![0-9A-Za-z.,+-])|(?<![0-9A-Za-z.,+-])([+-]?[0-9][0-9,]*(?:\\.[0-9]+)?)\\s*元")
    private val decimal = Regex("[+]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,18})?")
    private val fullNumber = Regex("(?U)[0-9](?:[\\s\\-－—·]*[0-9]){7,}")
    private val bankSources = setOf("icbc_credit", "icbc_debit", "ccb_debit", "icbc_asia")
    private val math = MathContext(80, RoundingMode.HALF_EVEN)
    private fun amount(value: String) = exactCashAmount(value).arithmeticValue()
    private fun total(values: List<BigDecimal>) = values.fold(BigDecimal.ZERO) { sum, value -> sum.add(value, math) }
    private fun label(value: String): String {
        val text = fullNumber.replace(value.replace('\n', ' ').trim { it.code in pythonWhitespace }) {
            "尾号 " + it.value.replace(Regex("[^0-9]"), "").takeLast(4)
        }
        return text.substring(0, text.offsetByCodePoints(0, minOf(128, text.codePointCount(0, text.length))))
    }
    private data class SourcePart(val kind: String, val origin: String, val key: String, val label: String, val amount: String?)
    private data class LocalPart(val kind: String, val key: String, val label: String, val amount: String?)
    private fun alipayParts(method: String, parentAmount: Any?, product: Any?): Pair<List<LocalPart>, List<String>> {
        require(method.count { it == '&' } < 64) { "funding_parts_too_large" }
        val parts = CashSourceAccountMapping.parseAlipayPaymentComponents(mapOf("source_type" to "alipay",
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
    private fun parts(file: ParsedImportFile, row: ParsedCashRow): Pair<List<SourcePart>, List<String>> {
        val document = requireNotNull(file.document); val source = document.sourceType
        val owner = row.sourceAccountIdentifier?.takeIf { it.isNotEmpty() } ?: document.sourceAccountIdentifier
        val origin = CashSourceScope.sourceAccountKey(source, owner, file.sourceScopeLabel)
        val local = cashSourceDigest(JsonPrimitive("funding-file-v1"), JsonPrimitive(source),
            JsonPrimitive(file.digest), JsonPrimitive(document.parserVersion))
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
        return parts.map { SourcePart(it.kind, if (it.kind == "file_local") local else origin ?: local, it.key, it.label, it.amount) } to issues
    }
    private class Group(val ref: String, val source: String, val kind: String, val origin: String, val key: String, val label: String) {
        val currencies = linkedSetOf<String>(); val rows = linkedSetOf<String>()
        fun value() = CashFundingGroup(ref, source, kind, origin, key, label, currencies.sortedWith(cashRelationStringOrder), rows.toList())
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
                    val (parts, issues) = parts(file, row)
                    val values = parts.map { part ->
                        if (part.kind == "unresolved") CashFundingPart(null, part.amount)
                        else {
                            val groupRef = cashSourceDigest(JsonPrimitive("funding-group-v1"), JsonPrimitive(doc.sourceType),
                                JsonPrimitive(part.kind), JsonPrimitive(part.origin), JsonPrimitive(part.key))
                            val group = groups.getOrPut(groupRef) { Group(groupRef, doc.sourceType, part.kind, part.origin, part.key, part.label) }
                            if (currency is String && currency.isNotEmpty()) group.currencies += currency
                            group.rows += ref
                            CashFundingPart(groupRef, part.amount)
                        }
                    }
                    rows += CashFundingRow(ref, fields, values, (row.issueCodes + issues).distinct())
                } catch (_: IllegalArgumentException) { rows += CashFundingRow(ref, fields, emptyList(), row.issueCodes, "funding_unreadable") }
                  catch (_: ArithmeticException) { rows += CashFundingRow(ref, fields, emptyList(), row.issueCodes, "funding_unreadable") }
                  catch (_: CashSourceScopeError) { rows += CashFundingRow(ref, fields, emptyList(), row.issueCodes, "funding_unreadable") }
            }
        }
        return CashFundingScan(groups.values.map { it.value() }, rows)
    }
    private fun selections(scan: CashFundingScan, raw: Any?, accountValues: Any?): Map<String, Pair<Map<*, *>?, String?>> {
        val selections = raw as? Map<*, *> ?: throw CashFundingInputError()
        val accounts = accountValues as? Map<*, *> ?: throw CashFundingInputError()
        val refs = scan.groups.map { it.sourceAccountRef }.toSet()
        if (selections.keys.any { it !in refs }) throw CashFundingInputError()
        return refs.associateWith { ref ->
            val choice = if (ref in selections) selections[ref] else emptyMap<String, Any?>()
            if (choice !is Map<*, *> || choice.keys.any { it !in setOf("account_id", "expected_account_revision") }) throw CashFundingInputError()
            val selected = choice["account_id"]; val revision = choice["expected_account_revision"]
            if (selected == null) { if (revision != null) throw CashFundingInputError(); null to "account_missing" }
            else {
                val number = when (revision) { is Byte, is Short, is Int, is Long, is java.math.BigInteger -> revision.toString().toBigInteger(); else -> null }
                if (selected !is String || selected.isEmpty() || number == null || number.signum() < 1) throw CashFundingInputError()
                val account = accounts[selected]
                if (account !is Map<*, *> || (if ("id" in account) account["id"] else account["account_id"]).toString() != selected || account["active"] != true ||
                    account["type"] !in setOf("cash", "loan", "lend")) null to "account_unavailable"
                else account to null
            }
        }
    }
    private fun hasCurrency(account: Map<*, *>, currency: Any): Boolean {
        val currencies = if ("currencies" in account) account["currencies"] else emptyList<Any>()
        return when (currencies) {
            is Collection<*> -> currency in currencies
            is Map<*, *> -> currency in currencies
            is String -> currency is String && currency in currencies
            else -> error("funding_account_currencies_not_iterable")
        }
    }
    private class Merged(val account: String?) { val amounts = mutableListOf<BigDecimal?>() }
    fun resolve(scan: CashFundingScan, selections: Any?, accounts: Any?): List<CashFundingResolution> {
        val selected = selections(scan, selections, accounts)
        return scan.rows.map { row ->
            val fields = (CashRecordPolicy.copyValue(row.fields) as Map<String, Any?>).toMutableMap()
            val issues = row.issueCodes.filter { it != "funding_unrecognized" || row.parts.any { it.groupRef == null } ||
                row.groupRefs.any { ref -> selected.getValue(ref).first == null } }.toMutableList()
            val merged = linkedMapOf<Pair<String, Any>, Merged>(); val used = linkedSetOf<Pair<String, String>>()
            try {
                val parent = fields["amount"]?.let { amount(it.toString()) }
                for ((index, part) in row.parts.withIndex()) {
                    val (account, issue) = part.groupRef?.let { selected.getValue(it) } ?: (null to null)
                    if (issue != null) issues += issue
                    if (account != null && fields["currency"] != null && !hasCurrency(account, fields.getValue("currency")!!))
                        issues += "account_currency_missing"
                    val accountId = account?.let { (if ("id" in it) it["id"] else it["account_id"]).toString() }
                    if (accountId != null && part.groupRef != null) used += part.groupRef to accountId
                    val key = if (accountId != null) "account" to accountId else if (part.groupRef != null) "source" to part.groupRef else "unresolved" to index
                    merged.getOrPut(key) { Merged(accountId) }.amounts += part.amount?.let { amount(it).abs() }
                }
                val magnitudes = mutableListOf<BigDecimal?>()
                val components = merged.values.mapIndexed { index, value ->
                    val known = value.amounts.filterNotNull()
                    var magnitude = if (known.size == value.amounts.size) total(known) else null
                    if (merged.size == 1 && magnitude == null && parent != null) {
                        if (total(known) > parent.abs()) issues += "component_total_mismatch" else magnitude = parent.abs()
                    }
                    magnitudes += magnitude
                    val signed = if (parent == null) null else if (parent.signum() >= 0) magnitude else magnitude?.negate()
                    linkedMapOf<String, Any?>("ordinal" to index, "account_id" to value.account, "amount" to signed?.toPlainString(), "currency" to fields["currency"])
                }
                val known = magnitudes.filterNotNull()
                if (parent != null && (total(known) > parent.abs() || known.size == magnitudes.size && total(known).compareTo(parent.abs()) != 0))
                    issues += "component_total_mismatch"
                if (magnitudes.any { it == null }) issues += "allocation_missing"
                fields["cash_granularity"] = if (components.size > 1) "aggregate" else "atomic"
                fields["components"] = components
                fields["account_id"] = if (components.size == 1) components.single()["account_id"] else null
                try { CashRecordPolicy.validateForPosting(fields) } catch (failure: CashPolicyError) { issues += failure.code }
                CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), issues.isEmpty() && row.errorCode == null,
                    row.errorCode, used.sortedWith(compareBy<Pair<String, String>, String>(cashRelationStringOrder) { it.first }.thenBy(cashRelationStringOrder) { it.second }))
            } catch (_: IllegalArgumentException) { CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), false, "funding_unreadable", emptyList()) }
              catch (_: ArithmeticException) { CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), false, "funding_unreadable", emptyList()) }
        }
    }
}
