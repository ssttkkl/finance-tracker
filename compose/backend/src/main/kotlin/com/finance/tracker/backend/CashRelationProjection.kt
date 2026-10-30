package com.finance.tracker.backend

import java.math.BigDecimal

internal data class RelationProjectionResult(
    val balances: Map<Pair<String, String>, BigDecimal>, val expenses: Map<String, BigDecimal>,
    val income: Map<String, BigDecimal>, val excludedTransferFactIds: Set<String>,
    val mirrorGroups: List<Set<String>>, val netExpenseByGroup: Map<String, BigDecimal>)

/** 余额 = 全部活动事实；损益依次排除镜像重复、转账与退款冲销。 */
internal object CashRelationProjection {
    fun project(facts: List<RelationFact>, acceptedRelations: List<Map<String, Any?>>): RelationProjectionResult {
        val active = facts.filter { !it.deleted }
        val balances = linkedMapOf<Pair<String, String>, BigDecimal>()
        for (fact in active) {
            val key = (fact.accountName.ifEmpty { fact.accountId }) to fact.currency.uppercase()
            balances[key] = (balances[key] ?: BigDecimal.ZERO) + fact.signedAmount
        }
        fun bilateral(relation: Map<String, Any?>): Boolean {
            if (CashRelationCore.isOpenLegRelation(relation)) return false
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
        val components = CashRelationGeometry.buildMirrorComponents(factById.keys, mirrorPairs)
        val groupSets = components.map { it.toSet() }
        val componentOf = linkedMapOf<String, Set<String>>()
        for (group in groupSets) for (id in group) componentOf[id] = group
        val expenses = linkedMapOf<String, BigDecimal>()
        val income = linkedMapOf<String, BigDecimal>()
        val netExpense = linkedMapOf<String, BigDecimal>()
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
            val canonical = CashRelationGeometry.canonicalMirrorFact(members) ?: members.first()
            val currency = canonical.currency.uppercase()
            val amount = canonical.signedAmount
            var refundTotal = BigDecimal.ZERO
            for (relation in refunds) {
                if (relation["primary_fact_id"]?.toString() !in group) continue
                val refund = factById[relation["secondary_fact_id"]?.toString()]
                if (refund != null) refundTotal += refund.signedAmount.abs()
            }
            when {
                amount.signum() < 0 -> {
                    var net = amount.abs() - refundTotal
                    if (net.signum() < 0) net = BigDecimal.ZERO
                    expenses[currency] = (expenses[currency] ?: BigDecimal.ZERO) + net
                    netExpense[canonical.id] = net
                }
                amount.signum() > 0 && canonical.id !in refundSecondary -> {
                    if (!CashRelationCore.isRefundIn(canonical))
                        income[currency] = (income[currency] ?: BigDecimal.ZERO) + amount
                }
            }
        }
        return RelationProjectionResult(balances, expenses, income, transferIds, groupSets, netExpense)
    }
}
