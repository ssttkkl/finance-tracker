package com.finance.tracker.backend

import java.math.BigDecimal
import java.math.BigInteger

internal class CashRelationFailure(val code: String): RuntimeException(code)
internal data class CashDecimalLiteral(val text: String)
internal data class CashRelationEndpoint(val componentId: Any?,val transactionId: Any?,val amount: Any?,val currency: Any?,val accountId: Any?,
    val recordType: Any?,val recordSubtype: Any?,val state: Any?="posted",val active: Any?=true)
internal data class CashRelationEvidence(val primaryOriginalExpense: Any?=false,val secondaryOriginalExpense: Any?=false,
    val investmentRefundHardKey: Any?=false,val p2pReturnHardKey: Any?=false)
internal data class CashRelationEndpointValues(val componentId: String,val transactionId: String,val amount: CashAmount,val currency: String,
    val accountId: String,val recordType: String,val recordSubtype: String) {
    val number: BigDecimal get()=amount.arithmeticValue()
}
internal fun relationFailure(code: String): Nothing=throw CashRelationFailure(code)
internal fun cashRelationDecimal(value: Any?): CashAmount?=when(value) {
    is CashAmount ->value
    is CashDecimalLiteral ->CashAmount.parseOrNull(value.text)
    else ->null
}
internal fun validateCashRelationEndpoint(value: Any?): CashRelationEndpointValues {
    val endpoint=value as? CashRelationEndpoint ?: relationFailure("relation_endpoint_incomplete")
    fun reference(value: Any?)=value is String && value.isNotEmpty()
    if(endpoint.state!="posted" || (endpoint.active !is Boolean || !endpoint.active) || !listOf(endpoint.componentId,endpoint.transactionId,endpoint.accountId).all(::reference) ||
        endpoint.currency !is String || !endpoint.currency.matches(Regex("[A-Za-z]{3}")) || endpoint.amount !is CashAmount && endpoint.amount !is CashDecimalLiteral)
        relationFailure("relation_endpoint_incomplete")
    val amount=cashRelationDecimal(endpoint.amount) ?: relationFailure("relation_endpoint_incompatible")
    if(!validCashRecordPair(endpoint.recordType,endpoint.recordSubtype)) relationFailure("relation_endpoint_incompatible")
    return CashRelationEndpointValues(endpoint.componentId as String,endpoint.transactionId as String,amount,endpoint.currency,endpoint.accountId as String,
        endpoint.recordType as String,endpoint.recordSubtype as String)
}
internal fun cashFundingRoleCompatible(direction: Any?,amount: Any?,recordType: Any?): Boolean {
    val value=when(amount) {
        is CashAmount ->amount
        is CashDecimalLiteral ->cashRelationDecimal(amount)
        is Double ->CashAmount.parseOrNull(pythonFloatText(amount))
        is Float ->CashAmount.parseOrNull(pythonFloatText(amount.toDouble()))
        is String,is BigDecimal,is BigInteger,is Byte,is Short,is Int,is Long ->CashAmount.parseOrNull(amount.toString())
        else ->null
    } ?: return false
    return when(direction) {
        "cash_to_investment" ->recordType in setOf("investment_out","transfer_out") && BigDecimal(value.numericText()).signum()<0
        "investment_to_cash" ->recordType in setOf("investment_in","transfer_in") && BigDecimal(value.numericText()).signum()>0
        else ->false
    }
}
private fun expense(endpoint: CashRelationEndpointValues,investment: Boolean=false)=endpoint.number.signum()<0 && endpoint.recordType in
    if(investment) setOf("consumption","refund","investment_out") else setOf("consumption","refund")
private fun refund(endpoint: CashRelationEndpointValues)=endpoint.number.signum()>0 && endpoint.recordType=="refund"
private fun transferTypes(subtype: Any?,a: CashRelationEndpointValues,b: CashRelationEndpointValues): Boolean {
    if(a.number.signum()>=0 || b.number.signum()<=0) return false
    val pair=a.recordType to b.recordType;val same=a.currency.uppercase()==b.currency.uppercase()
    return when(subtype) {
        "ordinary_transfer" ->same && pair in setOf("transfer_out" to "transfer_in","withdrawal_out" to "withdrawal_in")
        "credit_repayment" ->same && pair==("repayment" to "repayment")
        "currency_exchange" ->!same && (pair==("fx_out" to "fx_in") || pair==("transfer_out" to "transfer_in") && a.accountId==b.accountId)
        "cross_currency_remittance" ->!same && pair==("transfer_out" to "transfer_in") && a.recordSubtype=="cross_border_remittance"
        else ->false
    }
}
internal fun validateCashRelationRoles(kind: Any?,subtype: Any?,primary: Any?,secondary: Any?,primaryRole: Any?,secondaryRole: Any?) {
    val a=validateCashRelationEndpoint(primary);val b=validateCashRelationEndpoint(secondary)
    if(a.componentId==b.componentId) relationFailure("relation_endpoint_incompatible")
    val roles=primaryRole to secondaryRole
    val valid=when(kind) {
        "payment_mirror" ->subtype=="" && (roles==("expense" to "expense") && expense(a) && expense(b) || roles==("refund" to "refund") && refund(a) && refund(b))
        "refund_offset" -> {
            val compatible=when(subtype) {
                "" ->roles==("expense" to "refund") && (expense(a,true) && refund(b) || a.number.signum()==0 && b.number.signum()==0 && a.recordType in setOf("consumption","refund","investment_out") && b.recordType=="refund")
                "p2p_return" ->roles==("p2p_out" to "p2p_in") && a.recordType=="transfer_reversal" && b.recordType=="transfer_reversal" && a.number.signum()<0 && b.number.signum()>0
                else ->false
            }
            compatible && a.accountId==b.accountId && a.currency.uppercase()==b.currency.uppercase()
        }
        "transfer_pair" ->roles==("outgoing" to "incoming") && transferTypes(subtype,a,b)
        else ->false
    }
    if(!valid) relationFailure("relation_endpoint_incompatible")
}
internal fun discoverCashRelationRoles(kind: Any?,subtype: Any?,primary: Any?,secondary: Any?,evidence: Any?=CashRelationEvidence()): Pair<String,String> {
    val a=validateCashRelationEndpoint(primary);val b=validateCashRelationEndpoint(secondary)
    val proof=evidence as? CashRelationEvidence ?: relationFailure("relation_role_unproven")
    if(listOf(proof.primaryOriginalExpense,proof.secondaryOriginalExpense,proof.investmentRefundHardKey,proof.p2pReturnHardKey).any {it !is Boolean}) relationFailure("relation_role_unproven")
    val roles=when(kind) {
        "payment_mirror" ->if(refund(a) && refund(b)) "refund" to "refund" else "expense" to "expense"
        "refund_offset" ->if(subtype=="p2p_return") "p2p_out" to "p2p_in" else "expense" to "refund"
        else ->"outgoing" to "incoming"
    }
    validateCashRelationRoles(kind,subtype,primary,secondary,roles.first,roles.second)
    if(roles.first=="expense" && a.recordType=="refund" && proof.primaryOriginalExpense!=true ||
        roles.second=="expense" && b.recordType=="refund" && proof.secondaryOriginalExpense!=true ||
        kind=="refund_offset" && a.recordType=="investment_out" && proof.investmentRefundHardKey!=true ||
        kind=="refund_offset" && subtype=="p2p_return" && proof.p2pReturnHardKey!=true) relationFailure("relation_role_unproven")
    return roles
}
internal data class CashRelationLink(val id: Any?,val primaryTransactionId: Any?,val secondaryTransactionId: Any?,val state: Any?="draft",
    val active: Any?=true,val decisionGroupId: Any?=null)
internal val cashRelationStringOrder=Comparator<String> {left,right ->
    val a=left.codePoints().toArray();val b=right.codePoints().toArray()
    var result=0
    for(index in 0 until minOf(a.size,b.size)) if(a[index]!=b[index]) {result=a[index].compareTo(b[index]);break}
    if(result==0) a.size.compareTo(b.size) else result
}
internal class CashRelationGroups(identifiers: Iterable<String> = emptyList()) {
    val parents=identifiers.associateWith {it}.toMutableMap()
    fun root(id: String): String {
        parents.putIfAbsent(id,id);var current=id
        while(parents.getValue(current)!=current) {parents[current]=parents.getValue(parents.getValue(current));current=parents.getValue(current)}
        return current
    }
    fun join(left: String,right: String) {
        val a=root(left);val b=root(right)
        if(a!=b) if(cashRelationStringOrder.compare(a,b)<0) parents[b]=a else parents[a]=b
    }
}
private fun relationLinks(links: Iterable<*>): List<CashRelationLink> = links.map {raw ->
    val link=raw as? CashRelationLink ?: relationFailure("relation_link_invalid")
    fun reference(value: Any?)=value is String && value.isNotEmpty()
    if(!reference(link.id) || !reference(link.primaryTransactionId) || link.secondaryTransactionId!=null && !reference(link.secondaryTransactionId) ||
        link.decisionGroupId!=null && !reference(link.decisionGroupId) || link.state !in setOf("draft","confirmed","ignored") || link.active !is Boolean)
        relationFailure("relation_link_invalid")
    link
}
internal fun affectedCashRelationClosure(seedIds: Iterable<*>,oldLinks: Iterable<*>,newLinks: Iterable<*>): Set<String> {
    val groups=CashRelationGroups();val seeds=seedIds.toSet()
    if(seeds.any {it !is String || it.isEmpty()}) relationFailure("relation_seed_invalid")
    for(links in listOf(oldLinks,newLinks)) for(link in relationLinks(links))
        if(link.active==true && link.state=="confirmed" && link.secondaryTransactionId!=null) groups.join(link.primaryTransactionId as String,link.secondaryTransactionId as String)
    val roots=seeds.map {groups.root(it as String)}.toSet()
    return java.util.Set.copyOf(groups.parents.keys.filter {groups.root(it) in roots})
}
internal fun cashRelationCommitGroups(oldLinks: Iterable<*>,changes: Iterable<*>): List<List<String>> {
    val groups=CashRelationGroups()
    for(link in relationLinks(oldLinks)) if(link.active==true && link.state=="confirmed" && link.secondaryTransactionId!=null)
        groups.join(link.primaryTransactionId as String,link.secondaryTransactionId as String)
    val incoming=relationLinks(changes)
    if(incoming.map {it.id}.toSet().size!=incoming.size) relationFailure("relation_change_duplicate")
    val decisions=mutableMapOf<String,String>()
    for(link in incoming) {
        val primary=link.primaryTransactionId as String;groups.root(primary)
        if(link.secondaryTransactionId!=null) groups.join(primary,link.secondaryTransactionId as String)
        if(link.decisionGroupId!=null) {
            val decision=link.decisionGroupId as String;decisions.putIfAbsent(decision,primary);groups.join(decisions.getValue(decision),primary)
        }
    }
    val grouped=incoming.groupBy {groups.root(it.primaryTransactionId as String)}.values.map {java.util.List.copyOf(it.map {it.id as String}.sortedWith(cashRelationStringOrder))}
    return java.util.List.copyOf(grouped.sortedWith {a,b ->
        var result=0
        for(index in 0 until minOf(a.size,b.size)) {result=cashRelationStringOrder.compare(a[index],b[index]);if(result!=0) break}
        if(result==0) a.size.compareTo(b.size) else result
    })
}
internal fun cashRelationCandidateTypes(recordTypes: Set<String>): Set<String> {
    val groups=listOf(setOf("consumption","refund","investment_out"),setOf("transfer_out","transfer_in"),setOf("withdrawal_out","withdrawal_in"),setOf("fx_out","fx_in"),setOf("repayment"),setOf("transfer_reversal"))
    return java.util.Set.copyOf(groups.filter {group ->group.any {it in recordTypes}}.flatten())
}
