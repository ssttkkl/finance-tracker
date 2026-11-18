package com.finance.tracker.business.domain

const val CASH_PROJECTION_RULES_VERSION="cash-projection-v3"
open class CashProjectionFailure(val code: String): RuntimeException(code)
class CashProjectionDomainFailure(code:String):CashProjectionFailure(code)
enum class CashEconomicType(val value: String) { EXPENSE("expense"), INCOME("income"), INTERNAL_TRANSFER("internal_transfer") }
enum class CashProjectionComposition(val value: String) { PAYMENT_MIRROR("payment_mirror"), REFUND_OFFSET("refund_offset"), TRANSFER_PAIR("transfer_pair") }
private fun invalidProjectionRelation():Nothing=throw CashProjectionDomainFailure("projection.invalid_relation")

class CashProjectionComponents<I:Comparable<I>>(ids: Collection<I>) {
    private val parents=ids.associateWith {it}.toMutableMap()
    fun find(id: I): I {
        var current=id
        while(parents.getValue(current)!=current) {
            parents[current]=parents.getValue(parents.getValue(current));current=parents.getValue(current)
        }
        return current
    }
    fun union(left: I,right: I) {
        val a=find(left);val b=find(right)
        if(a!=b) parents[maxOf(a,b)]=minOf(a,b)
    }
    fun groups(): List<List<I>> = parents.keys.sorted().groupBy(::find).values.toList()
}
fun <I:Any> cashProjectionRoot(nodes: List<I>,edges: List<Pair<I,I>>): I {
    val incoming=nodes.associateWith {0}.toMutableMap();val adjacency=nodes.associateWith {mutableListOf<I>()}
    for((left,right) in edges) {incoming[right]=incoming.getValue(right)+1;adjacency.getValue(left).add(right)}
    val roots=nodes.filter {incoming[it]==0}
    if(roots.size!=1) invalidProjectionRelation()
    val reached=mutableSetOf<I>();val stack=mutableListOf(roots.single())
    while(stack.isNotEmpty()) {
        val item=stack.removeAt(stack.lastIndex)
        if(!reached.add(item)) invalidProjectionRelation()
        stack.addAll(adjacency.getValue(item))
    }
    if(reached!=nodes.toSet()) invalidProjectionRelation()
    return roots.single()
}
fun cashTransferBusinessLabel(types:Set<String?>,subtypes:List<String?>):String {
    if("repayment" in types) return "credit_repayment"
    if(types.any {it in setOf("fx_out","fx_in")}) return "currency_exchange"
    if(subtypes.any {it=="cross_border_remittance"}) return "cross_currency_remittance"
    return "ordinary_transfer"
}
fun cashSourceOrderKey(sourceType:String?,recordId:String,bindings:List<List<String>> = emptyList(),
    recordOrigin:String="legacy",manualTransferId:String?=null,amountSign:Int?=null):List<String> {
    if (bindings.isNotEmpty()) return bindings.minWith(Comparator { left,right ->
        val leftRank = listOf(left[3]!="provider_id",left[1]!="account")
        val rightRank = listOf(right[3]!="provider_id",right[1]!="account")
        for (index in 0..1) if (leftRank[index]!=rightRank[index]) return@Comparator if (leftRank[index]) 1 else -1
        for (index in 0 until minOf(left.size,right.size)) {
            val compared = cashRelationStringOrder.compare(left[index],right[index])
            if (compared!=0) return@Comparator compared
        }
        left.size.compareTo(right.size)
    })
    if (recordOrigin=="manual_transfer" && !manualTransferId.isNullOrEmpty() && amountSign!=null)
        return listOf("manual_transfer",manualTransferId,if(amountSign<0) "outgoing" else "incoming")
    if (recordOrigin in setOf("legacy","manual") && recordId.isNotEmpty() && !recordId.startsWith("import-v1:"))
        return listOf(sourceType?.takeIf {it.isNotEmpty()} ?: "manual","legacy_source_record",recordId)
    return emptyList()
}
