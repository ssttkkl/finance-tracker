package com.finance.tracker.business.domain


data class RelationProjectionResult<A>(
    val balances: Map<Pair<String, String>, A>, val expenses: Map<String, A>,
    val income: Map<String, A>, val excludedTransferFactIds: Set<String>,
    val mirrorGroups: List<Set<String>>, val netExpenseByGroup: Map<String, A>) {
    fun <B> mapAmounts(transform:(A)->B)=RelationProjectionResult(balances.mapValues {transform(it.value)},
        expenses.mapValues {transform(it.value)},income.mapValues {transform(it.value)},excludedTransferFactIds,
        mirrorGroups,netExpenseByGroup.mapValues {transform(it.value)})
}

/** 余额 = 全部活动事实；损益依次排除镜像重复、转账与退款冲销。 */
class CashRelationProjectionRules<F,A>(private val core:CashRelationCoreRules<F>,
    private val geometry:CashRelationGeometryRules<F>,private val computation:(F)->A,
    private val sign:(A)->Int,private val absolute:(A)->A,private val add:(A,A)->A,
    private val subtract:(A,A)->A,private val zero:A) {
    fun project(facts: List<RelationFact<F>>, acceptedRelations: List<Map<String, Any?>>): RelationProjectionResult<A> {
        val active = facts.filter { !it.deleted }
        val balances = linkedMapOf<Pair<String, String>, A>()
        for (fact in active) {
            val key = (fact.accountName.ifEmpty { fact.accountId }) to fact.currency.uppercase()
            balances[key] = add(balances[key] ?: zero,computation(fact.signedAmount))
        }
        fun bilateral(relation: Map<String, Any?>): Boolean {
            if (core.isOpenLegRelation(relation)) return false
            return !relation["primary_fact_id"].toString().isEmpty() && relation["secondary_fact_id"].toString().isNotEmpty() &&
                relation["primary_fact_id"] != null && relation["secondary_fact_id"] != null
        }
        val accepted = acceptedRelations.filter {
            it["status"] == "accepted" && bilateral(it)
        }
        val mirrorPairs = accepted.filter { it["kind"] == "payment_mirror" }
            .map { it["primary_fact_id"].toString() to it["secondary_fact_id"].toString() }
        val transferIds = linkedSetOf<String>()
        for (relation in accepted) if (relation["kind"] == "transfer_pair") {
            transferIds += relation["primary_fact_id"].toString()
            transferIds += relation["secondary_fact_id"].toString()
        }
        val refunds = accepted.filter { it["kind"] == "refund_offset" }
        val factById = active.associateBy { it.id }
        val components = geometry.buildMirrorComponents(factById.keys, mirrorPairs)
        val groupSets = components.map { it.toSet() }
        val componentOf = linkedMapOf<String, Set<String>>()
        for (group in groupSets) for (id in group) componentOf[id] = group
        val expenses = linkedMapOf<String, A>()
        val income = linkedMapOf<String, A>()
        val netExpense = linkedMapOf<String, A>()
        val seen = linkedSetOf<Set<String>>()
        val refundSecondary = refunds.mapNotNull { it["secondary_fact_id"]?.toString() }.toSet()
        for (fact in active) {
            if (fact.id in transferIds) continue
            val group = componentOf[fact.id] ?: setOf(fact.id)
            if (group in seen) continue
            seen += group
            val members = group.mapNotNull { factById[it] }
            if (members.isEmpty()) continue
            if (members.any { it.id in transferIds }) continue
            if (group.all { it in refundSecondary }) continue
            val canonical = geometry.canonicalMirrorFact(members) ?: members.first()
            val currency = canonical.currency.uppercase()
            val amount = computation(canonical.signedAmount)
            var refundTotal = zero
            for (relation in refunds) {
                if (relation["primary_fact_id"]?.toString() !in group) continue
                val refund = factById[relation["secondary_fact_id"]?.toString()]
                if (refund != null) refundTotal = add(refundTotal,absolute(computation(refund.signedAmount)))
            }
            when {
                sign(amount) < 0 -> {
                    var net = subtract(absolute(amount),refundTotal)
                    if (sign(net) < 0) net = zero
                    expenses[currency] = add(expenses[currency] ?: zero,net)
                    netExpense[canonical.id] = net
                }
                sign(amount) > 0 && canonical.id !in refundSecondary -> {
                    if (!core.isRefundIn(canonical))
                        income[currency] = add(income[currency] ?: zero,amount)
                }
            }
        }
        return RelationProjectionResult(balances, expenses, income, transferIds, groupSets, netExpense)
    }
}

private val cashRelationProjectionRules=CashRelationProjectionRules(cashRelationCore,cashRelationGeometry,
    LedgerArithmeticValue::fromCashAmount,{it.signum},{it.abs()},
    {a,b ->a.add(b)},{a,b ->a.subtract(b)},LedgerArithmeticValue.ZERO)

/** Derives exact totals from cached facts; totals have no single-fact column range restriction. */
fun projectCashRelationFacts(facts:List<RelationFact<CashAmount>>,acceptedRelations:List<Map<String,Any?>>):RelationProjectionResult<String> =
    cashRelationProjectionRules.project(facts,acceptedRelations).mapAmounts {it.toPlainString()}
