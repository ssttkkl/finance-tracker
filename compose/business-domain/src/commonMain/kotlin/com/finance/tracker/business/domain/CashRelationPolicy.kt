package com.finance.tracker.business.domain


class CashRelationFailure(val code: String): RuntimeException(code)
data class CashDecimalLiteral(val text: String)
data class CashRelationEndpoint(val componentId: Any?,val transactionId: Any?,val amount: Any?,val currency: Any?,val accountId: Any?,
    val recordType: Any?,val recordSubtype: Any?,val state: Any?="posted",val active: Any?=true)
data class CashRelationEvidence(val primaryOriginalExpense: Any?=false,val secondaryOriginalExpense: Any?=false,
    val investmentRefundHardKey: Any?=false,val p2pReturnHardKey: Any?=false)
data class CashRelationEndpointValues(val componentId: String,val transactionId: String,val amount: CashAmount,val currency: String,
    val accountId: String,val recordType: String,val recordSubtype: String)
fun relationFailure(code: String): Nothing=throw CashRelationFailure(code)
fun cashRelationDecimal(value: Any?): CashAmount?=when(value) {
    is CashAmount ->value
    is CashDecimalLiteral ->CashAmount.parseOrNull(value.text)
    else ->null
}
fun validateCashRelationEndpoint(value: Any?): CashRelationEndpointValues {
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
fun cashFundingRoleCompatible(direction: Any?,amount: Any?,recordType: Any?): Boolean {
    val value=when(amount) {
        is CashAmount ->amount
        is CashDecimalLiteral ->cashRelationDecimal(amount)
        is Double ->CashAmount.parseOrNull(pythonFloatText(amount))
        is Float ->CashAmount.parseOrNull(pythonFloatText(amount.toDouble()))
        is String,is Byte,is Short,is Int,is Long ->CashAmount.parseOrNull(amount.toString())
        else ->null
    } ?: return false
    return when(direction) {
        "cash_to_investment" ->recordType in setOf("investment_out","transfer_out") && value.signum()<0
        "investment_to_cash" ->recordType in setOf("investment_in","transfer_in") && value.signum()>0
        else ->false
    }
}
private fun CashAmount.signum(): Int = if(representation.coefficient=="0") 0 else if(representation.negative) -1 else 1
private fun sameMagnitude(left: CashAmount,right: CashAmount): Boolean =
    CashAmount.parse(left.numericText().removePrefix("-"))==CashAmount.parse(right.numericText().removePrefix("-"))

private fun expense(endpoint: CashRelationEndpointValues,investment: Boolean=false)=endpoint.amount.signum()<0 && endpoint.recordType in
    if(investment) setOf("consumption","refund","investment_out") else setOf("consumption","refund")
private fun refund(endpoint: CashRelationEndpointValues)=endpoint.amount.signum()>0 && endpoint.recordType=="refund"
private val transferOutTypes=setOf("transfer_out","withdrawal_out","repayment","fx_out")
private val transferInTypes=setOf("transfer_in","withdrawal_in","repayment","fx_in")

fun validateCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?,primaryRole: Any?,secondaryRole: Any?) {
    val a=validateCashRelationEndpoint(primary);val b=validateCashRelationEndpoint(secondary)
    if(a.componentId==b.componentId || a.transactionId==b.transactionId) relationFailure("relation_endpoint_incompatible")
    val roles=primaryRole to secondaryRole
    val sameCurrency=a.currency.uppercase()==b.currency.uppercase()
    var valid=false
    when(kind) {
        "payment_mirror" -> {
            valid=roles==("expense" to "expense") && expense(a) && expense(b) || roles==("refund" to "refund") && refund(a) && refund(b)
            if(valid && a.accountId!=b.accountId) relationFailure("relation_account_mismatch")
            if(valid && !sameCurrency) relationFailure("relation_currency_mismatch")
            if(valid && a.amount!=b.amount) relationFailure("relation_amount_invalid")
        }
        "refund_offset" -> {
            val zeroRefund=a.amount.signum()==0 && b.amount.signum()==0 && a.recordType in setOf("consumption","refund","investment_out") && b.recordType=="refund"
            valid=roles==("expense" to "refund") && (expense(a,true) && refund(b) || zeroRefund) ||
                roles==("p2p_out" to "p2p_in") && a.recordType=="transfer_reversal" && b.recordType=="transfer_reversal" &&
                    a.amount.signum()<0 && b.amount.signum()>0
            if(valid && a.accountId!=b.accountId) relationFailure("relation_account_mismatch")
            if(valid && !sameCurrency) relationFailure("relation_currency_mismatch")
        }
        "transfer_pair" -> {
            valid=roles==("outgoing" to "incoming") && a.recordType in transferOutTypes && b.recordType in transferInTypes &&
                a.amount.signum()<0 && b.amount.signum()>0
            if(valid && sameCurrency && !sameMagnitude(a.amount,b.amount)) relationFailure("relation_amount_invalid")
        }
    }
    if(!valid) relationFailure("relation_role_incompatible")
}
fun inferCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?): Pair<String,String> {
    val a=validateCashRelationEndpoint(primary);val b=validateCashRelationEndpoint(secondary)
    val roles=when(kind) {
        "payment_mirror" ->if(refund(a) && refund(b)) "refund" to "refund" else "expense" to "expense"
        "refund_offset" ->if(a.recordType=="transfer_reversal" && b.recordType=="transfer_reversal") "p2p_out" to "p2p_in" else "expense" to "refund"
        else ->"outgoing" to "incoming"
    }
    validateCashRelationRoles(kind,primary,secondary,roles.first,roles.second)
    return roles
}
fun discoverCashRelationRoles(kind: Any?,primary: Any?,secondary: Any?,evidence: Any?=CashRelationEvidence()): Pair<String,String> {
    val a=validateCashRelationEndpoint(primary);val b=validateCashRelationEndpoint(secondary)
    val proof=evidence as? CashRelationEvidence ?: relationFailure("relation_role_unproven")
    if(listOf(proof.primaryOriginalExpense,proof.secondaryOriginalExpense,proof.investmentRefundHardKey,proof.p2pReturnHardKey).any {it !is Boolean}) relationFailure("relation_role_unproven")
    val roles=inferCashRelationRoles(kind,primary,secondary)
    if(roles.first=="expense" && a.recordType=="refund" && proof.primaryOriginalExpense!=true ||
        roles.second=="expense" && b.recordType=="refund" && proof.secondaryOriginalExpense!=true ||
        kind=="refund_offset" && a.recordType=="investment_out" && proof.investmentRefundHardKey!=true ||
        roles==("p2p_out" to "p2p_in") && proof.p2pReturnHardKey!=true) relationFailure("relation_role_unproven")
    return roles
}
data class CashRelationLink(val id: Any?,val primaryTransactionId: Any?,val secondaryTransactionId: Any?,val state: Any?="draft",
    val active: Any?=true,val decisionGroupId: Any?=null)
val cashRelationStringOrder: Comparator<String> = Comparator(::pythonStringOrder)
class CashRelationGroups(identifiers: Iterable<String> = emptyList()) {
    val parents=identifiers.associateWith {it}.toMutableMap()
    fun root(id: String): String {
        parents.getOrPut(id) {id};var current=id
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
fun affectedCashRelationClosure(seedIds: Iterable<*>,oldLinks: Iterable<*>,newLinks: Iterable<*>): Set<String> {
    val groups=CashRelationGroups();val seeds=seedIds.toSet()
    if(seeds.any {it !is String || it.isEmpty()}) relationFailure("relation_seed_invalid")
    for(links in listOf(oldLinks,newLinks)) for(link in relationLinks(links))
        if(link.active==true && link.state=="confirmed" && link.secondaryTransactionId!=null) groups.join(link.primaryTransactionId as String,link.secondaryTransactionId as String)
    val roots=seeds.map {groups.root(it as String)}.toSet()
    return RelationSet(groups.parents.keys.filter {groups.root(it) in roots})
}
fun cashRelationCommitGroups(oldLinks: Iterable<*>,changes: Iterable<*>): List<List<String>> {
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
            val decision=link.decisionGroupId as String;decisions.getOrPut(decision) {primary};groups.join(decisions.getValue(decision),primary)
        }
    }
    val grouped=incoming.groupBy {groups.root(it.primaryTransactionId as String)}.values.map {RelationList(it.map {it.id as String}.sortedWith(cashRelationStringOrder))}
    return RelationList(grouped.sortedWith {a,b ->
        var result=0
        for(index in 0 until minOf(a.size,b.size)) {result=cashRelationStringOrder.compare(a[index],b[index]);if(result!=0) break}
        if(result==0) a.size.compareTo(b.size) else result
    })
}
fun cashRelationCandidateTypes(recordTypes: Set<String>): Set<String> {
    val groups=listOf(setOf("consumption","refund","investment_out"),
        setOf("transfer_out","withdrawal_out","repayment","fx_out","transfer_in","withdrawal_in","fx_in"),
        setOf("transfer_reversal"))
    return RelationSet(groups.filter {group ->group.any {it in recordTypes}}.flatten())
}

private class RelationList<T>(values: Collection<T>): AbstractList<T>() {
    private val items=values.toList()
    override val size: Int get()=items.size
    override fun get(index: Int): T=items[index]
}
private class RelationSet<T>(values: Collection<T>): AbstractSet<T>() {
    private val items=values.toSet()
    override val size: Int get()=items.size
    override fun contains(element: T): Boolean=items.contains(element)
    override fun iterator(): Iterator<T> {
        val source=items.iterator()
        return object: Iterator<T> {
            override fun hasNext()=source.hasNext()
            override fun next(): T=source.next()
        }
    }
}
