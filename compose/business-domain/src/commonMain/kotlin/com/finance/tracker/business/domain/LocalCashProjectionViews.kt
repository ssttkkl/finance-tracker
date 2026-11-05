package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*

/** The existing ledger read model, derived entirely from one committed fact snapshot. */
fun buildLocalCashProjectionViews(snapshot:LedgerSnapshotDto):List<CashProjectionDto> = LocalCashProjectionViews(snapshot).build()

private class LocalCashProjectionViews(private val snapshot:LedgerSnapshotDto) {
    private val facts=snapshot.facts
    private val accounts=facts.accounts.associateBy {it.id}
    private val categories=facts.cashCategories.associateBy {it.id}
    private val categoryCache=mutableMapOf<String,CashCategoryDto?>()
    private val parts=facts.cashTransactionComponents.associateBy {it.id}
    private val relations=facts.transactionRelations.associateBy {it.id}
    private val funding=facts.cashInvestmentFundingRelations.associateBy {it.id}
    private val issues=facts.cashTransactionIssues.groupBy {it.transactionId}
    private val pending=facts.transactionRelations.filter {it.active && it.state=="draft"}
        .flatMap {listOfNotNull(it.primaryTransactionId,it.secondaryTransactionId)}.toSet()

    private fun account(id:String?):AccountReferenceDto?=accounts[id]?.let {AccountReferenceDto(it.id.toLong(),it.name,it.type,it.active)}
    private fun category(id:String?):CashCategoryDto? {
        if(id.isNullOrEmpty())return null
        return categoryCache.getOrPut(id) {
            val selected=categories[id] ?: return@getOrPut null
            val path=mutableListOf<CashCategoryPathItemDto>()
            val visited=mutableSetOf<String>()
            var current:String?=id
            while(current!=null && current in categories) {
                check(visited.add(current)) {"category cycle in current projection"}
                val item=categories.getValue(current)
                path+=CashCategoryPathItemDto(current,item.name)
                current=item.parentId?.takeIf(String::isNotEmpty)
            }
            CashCategoryDto(id,name=selected.name,path=path.reversed())
        }
    }
    private fun money(amount:CashAmount?):String? {
        if(amount==null)return null
        if(amount.representation.coefficient=="0")return "0"
        val text=LedgerArithmeticValue.fromCashAmount(amount).toPlainString()
        return if('.' in text)text.trimEnd('0').trimEnd('.') else text
    }
    private fun money(text:String?):String?=text?.let {money(CashAmount.parse(it))}

    private fun transfer(row:LocalCashLedgerEntry):CashTransferDto? {
        val projection=row.projection
        val paired=projection.relations.firstOrNull {it.kind=="transfer_pair"}?.let {relations[it.id.toString()]}
        if(paired!=null) {
            val from=parts[paired.primaryComponentId];val to=parts[paired.secondaryComponentId]
            val fromAccount=account(from?.accountId);val toAccount=account(to?.accountId)
            if(from!=null && to!=null && fromAccount!=null && toAccount!=null)return CashTransferDto(
                fromAccount,requireNotNull(money(from.amount)),requireNotNull(from.currency),
                toAccount,requireNotNull(money(to.amount)),requireNotNull(to.currency))
        }
        if(projection.economicType!=CashEconomicType.INTERNAL_TRANSFER || projection.businessLabel!="bank_security_transfer")return null
        val link=projection.fundingRelationId?.toString()?.let(funding::get) ?: return null
        if(link.status!="accepted" || link.activeSlot!="active")return null
        val event=link.investmentEvent ?: return null
        val part=parts[link.cashTransactionComponentId] ?: return null
        val cashAccount=account(part.accountId) ?: return null
        val investmentAccount=account(event.accountId) ?: return null
        val incoming=link.direction=="cash_to_investment"
        if(!incoming && link.direction!="investment_to_cash")return null
        val investmentAmount=money(if(incoming)event.toAmount else event.fromAmount) ?: return null
        val cashAmount=requireNotNull(money(part.amount));val cashCurrency=requireNotNull(part.currency)
        return if(incoming)CashTransferDto(cashAccount,cashAmount,cashCurrency,investmentAccount,investmentAmount,event.currency)
            else CashTransferDto(investmentAccount,investmentAmount,event.currency,cashAccount,cashAmount,cashCurrency)
    }
    fun build():List<CashProjectionDto> {
        val version=snapshot.ledgerSeq.toLong()
        return buildLocalCashLedgerEntries(snapshot).map {row ->
            val projection=row.projection;val primary=projection.primaryRecord
            val kinds=projection.relations.map {it.kind}
            val codes=projection.memberIds.flatMap {id ->
                issues[id.toString()].orEmpty().map {it.code}+if(id.toString() in pending)listOf("relation_pending") else emptyList()
            }.distinct().sortedWith(::pythonStringOrder)
            CashProjectionDto(row.projectionId,primary.occurredAt?.let(::formatPythonCashTimestamp),
                account=account(primary.accountId?.toString()),counterparty=row.entry.counterparty,category=category(row.entry.categoryId),note=row.entry.note,
                amount=money(projection.netAmount),currency=primary.currency,economicType=projection.economicType?.value,businessLabel=projection.businessLabel,
                composition=kinds.distinct().sortedWith(::pythonStringOrder),memberCount=projection.members.size,
                acceptedRelationSummary=kinds.groupingBy {it}.eachCount().entries.sortedWith {a,b->pythonStringOrder(a.key,b.key)}
                    .map {(kind,count)->AcceptedRelationSummaryDto(kind,count)},
                sourceType=primary.sourceType,sourceTypes=projection.members.mapNotNull {it.first.sourceType?.takeIf(String::isNotEmpty)}.distinct(),
                recordId=primary.recordId,visible=projection.visible,hiddenReason=projection.hiddenReason,transfer=transfer(row),state=projection.state,
                statisticsEligible=projection.statisticsEligible,attentionCount=projection.attentionCount.toLong(),confirmationStatus=projection.confirmationStatus,
                projectionVersion=version,entryId=row.entry.id,transactionId=row.transactionId,recordType=primary.recordType,recordSubtype=primary.recordSubtype,
                attentionCodes=codes)
        }
    }
}
