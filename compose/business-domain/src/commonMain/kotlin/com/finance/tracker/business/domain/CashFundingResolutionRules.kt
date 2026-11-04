package com.finance.tracker.business.domain

/** Exact scalar operations; persisted amounts and intermediate sums keep distinct bounds. */
interface CashFundingAmountValues<A : Any> {
    fun parse(text: String): A
    fun absolute(value: A): A
    fun negate(value: A): A
    fun total(values: List<A>): A
    fun compare(left: A, right: A): Int
    fun sign(value: A): Int
    fun plain(value: A): String
}

/** Account choice, allocation and posting readiness; does not read or write persistence. */
class CashFundingResolutionRules<A : Any>(
    private val values: CashFundingAmountValues<A>,
    private val newPostingValidation: () -> ((Map<*, *>) -> Unit),
    private val copyFields: (Map<String, Any?>) -> Map<String, Any?>,
    private val validRevision: (Any?) -> Boolean,
    private val integerValue: (Int) -> Any = { it },
) {
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
                if (selected !is String || selected.isEmpty() || !validRevision(revision)) throw CashFundingInputError()
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
    private class Merged<A>(val account: String?) { val amounts = mutableListOf<A?>() }
    fun resolve(scan: CashFundingScan, selections: Any?, accounts: Any?): List<CashFundingResolution> {
        val validate = newPostingValidation()
        val selected = selections(scan, selections, accounts)
        return scan.rows.map { row ->
            val fields = copyFields(row.fields).toMutableMap()
            val issues = row.issueCodes.filter { it != "funding_unrecognized" || row.parts.any { it.groupRef == null } ||
                row.groupRefs.any { ref -> selected.getValue(ref).first == null } }.toMutableList()
            val merged = linkedMapOf<Pair<String, Any>, Merged<A>>(); val used = linkedSetOf<Pair<String, String>>()
            try {
                val parent = fields["amount"]?.let { values.parse(it.toString()) }
                for ((index, part) in row.parts.withIndex()) {
                    val (account, issue) = part.groupRef?.let { selected.getValue(it) } ?: (null to null)
                    if (issue != null) issues += issue
                    if (account != null && fields["currency"] != null && !hasCurrency(account, fields.getValue("currency")!!))
                        issues += "account_currency_missing"
                    val accountId = account?.let { (if ("id" in it) it["id"] else it["account_id"]).toString() }
                    if (accountId != null && part.groupRef != null) used += part.groupRef to accountId
                    val key = if (accountId != null) "account" to accountId else if (part.groupRef != null) "source" to part.groupRef else "unresolved" to index
                    merged.getOrPut(key) { Merged<A>(accountId) }.amounts += part.amount?.let { values.absolute(values.parse(it)) }
                }
                val magnitudes = mutableListOf<A?>()
                val components = merged.values.mapIndexed { index, value ->
                    val known = value.amounts.filterNotNull()
                    var magnitude = if (known.size == value.amounts.size) values.total(known) else null
                    if (merged.size == 1 && magnitude == null && parent != null) {
                        if (values.compare(values.total(known), values.absolute(parent)) > 0) issues += "component_total_mismatch" else magnitude = values.absolute(parent)
                    }
                    magnitudes += magnitude
                    val signed = if (parent == null) null else if (values.sign(parent) >= 0) magnitude else magnitude?.let(values::negate)
                    linkedMapOf<String, Any?>("ordinal" to integerValue(index), "account_id" to value.account, "amount" to signed?.let(values::plain), "currency" to fields["currency"])
                }
                val known = magnitudes.filterNotNull()
                if (parent != null && (values.compare(values.total(known), values.absolute(parent)) > 0 || known.size == magnitudes.size && values.compare(values.total(known), values.absolute(parent)) != 0))
                    issues += "component_total_mismatch"
                if (magnitudes.any { it == null }) issues += "allocation_missing"
                fields["cash_granularity"] = if (components.size > 1) "aggregate" else "atomic"
                fields["components"] = components
                fields["account_id"] = if (components.size == 1) components.single()["account_id"] else null
                try { validate(fields) } catch (failure: CashPolicyError) { issues += failure.code }
                CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), issues.isEmpty() && row.errorCode == null,
                    row.errorCode, used.sortedWith(compareBy<Pair<String, String>, String>(Comparator(::pythonStringOrder)) { it.first }.thenBy(Comparator(::pythonStringOrder)) { it.second }))
            } catch (_: IllegalArgumentException) { CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), false, "funding_unreadable", emptyList()) }
              catch (_: ArithmeticException) { CashFundingResolution(row.rowRef, fields, row.groupRefs, issues.distinct(), false, "funding_unreadable", emptyList()) }
        }
    }
}
