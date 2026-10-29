package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Record
import org.jooq.Field
import org.jooq.Select
import org.jooq.Record1
import org.jooq.impl.DSL

internal data class CashRelationSqlRow(val id:Long,val kind:String,val subtype:String,val primary:Long,val secondary:Long?,
    val primaryComponent:Long?,val secondaryComponent:Long?,val primaryRole:String?,val secondaryRole:String?,val applied:CashStoredDecimal,
    val state:String,val active:Boolean,val decisionGroup:String?,val revision:Long,val ruleId:String="") {
    fun edge()=CashRelationGraphEdge(id,kind,primary.toString(),secondary?.toString(),primaryComponent?.toString(),secondaryComponent?.toString(),primaryRole,secondaryRole,subtype,
        if(kind in setOf("payment_mirror","refund_offset") && applied.text.toBigDecimalOrNull()?.signum()==0) null else CashDecimalLiteral(applied.text),state,active)
}
internal data class CashRelationSqlContext(val seedIds:Set<Long>,val decisionGroupIds:List<String>,val transactionIds:Set<Long>,
    val facts:List<CashProjectionFact>,val components:List<CashRelationEndpoint>,val relations:List<CashRelationGraphEdge>,
    val jointMembers:List<CashJointRelationMember>,val transactionRevisions:List<Pair<Long,Long>>,val activeTransactionIds:Set<Long>,
    val componentVersions:List<CashComponentVersion>,val relationRevisions:List<Pair<Long,Long>>,val issueVersions:List<CashIssueVersion>,
    val entryVersions:List<CashEntrySnapshot>,val relationGroups:List<Pair<Long,String>>,val fundingVersions:List<CashFundingSqlRow>,val fundingComponentIds:Set<String>)

internal class CashRelationReadRepository(private val context:CashSqlContext) {
    private val sql get()=context.sql
    private val workspace get()=context.workspace
    private val relation = TRANSACTION_RELATIONS
    internal val graphFields = listOf(relation.ID, relation.KIND, relation.SUBTYPE, relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID,
        relation.PRIMARY_COMPONENT_ID, relation.SECONDARY_COMPONENT_ID, relation.PRIMARY_ROLE, relation.SECONDARY_ROLE, relation.APPLIED_AMOUNT,
        relation.STATE, relation.ACTIVE, relation.DECISION_GROUP_ID, relation.ROW_REVISION)
    private fun checked(ids:Set<Long>):List<Long> {if(ids.any {it<=0}) relationFailure("relation_data_invalid");return ids.sorted()}
    internal fun row(row: Record, rule: Boolean = false) = CashRelationSqlRow(row[relation.ID]!!, row[relation.KIND]!!, row[relation.SUBTYPE]!!,
        row[relation.PRIMARY_TRANSACTION_ID]!!, row[relation.SECONDARY_TRANSACTION_ID], row[relation.PRIMARY_COMPONENT_ID], row[relation.SECONDARY_COMPONENT_ID],
        row[relation.PRIMARY_ROLE], row[relation.SECONDARY_ROLE], row[relation.APPLIED_AMOUNT]!!, row[relation.STATE]!!, row[relation.ACTIVE]!!,
        row[relation.DECISION_GROUP_ID], row[relation.ROW_REVISION]!!, if (rule) row[relation.RULE_ID]!! else "")
    fun graphRowsForTransactions(ids: Set<Long>): List<CashRelationSqlRow> {
        val result = sortedMapOf<Long, CashRelationSqlRow>()
        for (batch in checked(ids).chunked(500)) {
            val endpoints = listOf(relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID, relation.ANCHOR_TRANSACTION_ID)
            val predicate = relation.WORKSPACE_ID.eq(workspace).and(relation.ACTIVE.isTrue)
            val rows = if (context.sqlite) {
                val branches = endpoints.map { DSL.select(relation.ID).from(relation).where(predicate).and(it.`in`(batch)) }
                sql.select(graphFields).from(relation).where(relation.WORKSPACE_ID.eq(workspace))
                    .and(relation.ID.`in`(branches[0].union(branches[1]).union(branches[2]))).fetch()
            } else {
                val branches = endpoints.map { sql.select(graphFields).from(relation).where(predicate).and(it.`in`(batch)) }
                branches[0].unionAll(branches[1]).unionAll(branches[2]).fetch()
            }
            for (record in rows) { val edge = row(record); if (edge.active) result[edge.id] = edge }
        }
        return result.values.toList()
    }
    fun graphRowsForGroups(groups: List<String>): List<CashRelationSqlRow> {
        val result = sortedMapOf<Long, CashRelationSqlRow>()
        for (batch in groups.distinct().sortedWith(cashRelationStringOrder).chunked(500)) {
            sql.select(graphFields).from(relation).where(relation.WORKSPACE_ID.eq(workspace)).and(relation.DECISION_GROUP_ID.`in`(batch))
                .fetch().forEach { val edge = row(it); result[edge.id] = edge }
        }
        return result.values.toList()
    }
    fun confirmedClosure(ids:Set<Long>):Set<Long> {
        val result=sortedSetOf<Long>()
        for(batch in checked(ids).chunked(500)) {
            val seeds=batch.filter {it !in result};if(seeds.isEmpty()) continue
            val reached = DSL.table(DSL.name("current_relation_closure")).`as`("reached")
            val node = DSL.field(DSL.name("reached", "node"), Long::class.java)
            val initial = DSL.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace)).and(CASH_TRANSACTIONS.ID.`in`(seeds))
            val cteName = DSL.name("current_relation_closure").fields("node")
            if (context.sqlite) {
                val directions = DSL.name("relation_directions").fields("direction").`as`(DSL.select(DSL.inline(0)).unionAll(DSL.select(DSL.inline(1))))
                val d = directions.`as`("d")
                val direction = d.field("direction", Int::class.java)!!
                val outgoing = relation.`as`("p")
                val incoming = relation.`as`("s")
                val next = DSL.`when`(direction.eq(DSL.inline(0)), outgoing.SECONDARY_TRANSACTION_ID).otherwise(incoming.PRIMARY_TRANSACTION_ID)
                val recursive = DSL.select(next).from(reached).crossJoin(d)
                    .leftJoin(outgoing).on(direction.eq(DSL.inline(0))).and(outgoing.WORKSPACE_ID.eq(workspace)).and(outgoing.PRIMARY_TRANSACTION_ID.eq(node))
                    .and(outgoing.ACTIVE.isTrue).and(outgoing.STATE.eq(DSL.inline("confirmed")))
                    .leftJoin(incoming).on(direction.eq(DSL.inline(1))).and(incoming.WORKSPACE_ID.eq(workspace)).and(incoming.SECONDARY_TRANSACTION_ID.eq(node))
                    .and(incoming.ACTIVE.isTrue).and(incoming.STATE.eq(DSL.inline("confirmed"))).where(next.isNotNull)
                val closure = cteName.`as`(initial.union(recursive))
                sql.withRecursive(directions, closure).select(closure.field("node", Long::class.java)).from(closure).fetch().forEach { result += it.value1()!! }
            } else {
                val neighbours = DSL.lateral(DSL.select(relation.SECONDARY_TRANSACTION_ID.`as`("node")).from(relation)
                    .where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ACTIVE.isTrue).and(relation.STATE.eq(DSL.inline("confirmed")))
                    .and(relation.PRIMARY_TRANSACTION_ID.eq(node)).and(relation.SECONDARY_TRANSACTION_ID.isNotNull)
                    .unionAll(DSL.select(relation.PRIMARY_TRANSACTION_ID.`as`("node")).from(relation)
                        .where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ACTIVE.isTrue).and(relation.STATE.eq(DSL.inline("confirmed")))
                        .and(relation.SECONDARY_TRANSACTION_ID.eq(node)).and(relation.PRIMARY_TRANSACTION_ID.isNotNull)).offset(0)).`as`("neighbours")
                val recursive = DSL.select(neighbours.field("node", Long::class.java)).from(reached).join(neighbours).on(DSL.trueCondition())
                val closure = cteName.`as`(initial.union(recursive))
                sql.withRecursive(closure).select(closure.field("node", Long::class.java)).from(closure).fetch().forEach { result += it.value1()!! }
            }
        };return result
    }
    fun issues(ids:Set<Long>):List<CashIssueVersion> {
        val result=sortedMapOf<Long,CashIssueVersion>()
        val issue = CASH_TRANSACTION_ISSUES
        for (batch in ids.sorted().chunked(500)) {
            sql.select(issue.ID, issue.TRANSACTION_ID, issue.REVISION).from(issue).where(issue.WORKSPACE_ID.eq(workspace))
                .and(issue.TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                    val version = CashIssueVersion(row[issue.ID]!!, row[issue.TRANSACTION_ID]!!, row[issue.REVISION]!!)
                    result[version.id] = version
                }
        };return result.values.toList()
    }
    fun attention(ids:Set<Long>,relations:List<CashRelationSqlRow>):Set<Long> = issues(ids).map {it.transactionId}.toMutableSet().also {pending ->
        for(row in relations) if(row.active && row.state=="draft") {if(row.primary in ids) pending+=row.primary;row.secondary?.takeIf {it in ids}?.let(pending::add)}
    }
    fun readContext(ids:Set<Long>,decisionGroups:List<String> = emptyList()):CashRelationSqlContext {
        context.requireActive();checked(ids)
        val groups=decisionGroups.distinct().sortedWith(cashRelationStringOrder)
        if(groups.any {it.codePointCount(0,it.length) !in 1..128}) relationFailure("relation_data_invalid")
        try {groups.forEach(::strictUtf8)} catch(_:java.nio.charset.CharacterCodingException) {relationFailure("relation_data_invalid")}
        context.entries.flush()
        val groupMembers=graphRowsForGroups(groups)
        if(groups.toSet()!=groupMembers.mapNotNull {it.decisionGroup}.toSet()) relationFailure("relation_group_changed")
        val wanted=ids+groupMembers.flatMap {listOfNotNull(it.primary,it.secondary)}
        fun parentsFor(ids:Set<Long>)=ids.sorted().chunked(500).flatMap {readCashFactRows(context,it.toSet(),liveOnly=false,forContext=true)}.associateBy {it.id}
        val parents=parentsFor(wanted).toMutableMap()
        if(wanted!=parents.keys) relationFailure("relation_endpoint_incomplete")
        val closure=confirmedClosure(wanted)
        parents.putAll(parentsFor(closure-parents.keys))
        val rows=(graphRowsForTransactions(closure)+groupMembers).associateBy {it.id}.values.sortedBy {it.id}
        val issues=issues(parents.keys);val pending=issues.map {it.transactionId}.toMutableSet()
        rows.filter {it.active && it.state=="draft"}.forEach {r ->listOfNotNull(r.primary,r.secondary).filter {it in parents}.forEach(pending::add)}
        val formal=parents.filterValues {it.state=="posted" && it.deletedAt==null}
        val parts=sortedMapOf<Long,CashComponentSqlRow>();val funding=sortedMapOf<Long,CashFundingSqlRow>()
        val part = CASH_TRANSACTION_COMPONENTS.`as`("p")
        val fund = CASH_INVESTMENT_FUNDING_RELATIONS.`as`("f")
        for (batch in parents.keys.sorted().chunked(500)) {
            sql.select(part.ID, part.CASH_TRANSACTION_ID, part.ACCOUNT_ID, part.AMOUNT, part.CURRENCY, part.ORDINAL,
                    fund.ID, fund.CASH_TRANSACTION_COMPONENT_ID, fund.INVESTMENT_EVENT_ID, fund.DIRECTION)
                .from(part).leftJoin(fund).on(fund.WORKSPACE_ID.eq(workspace)).and(fund.CASH_TRANSACTION_COMPONENT_ID.eq(part.ID))
                .and(fund.STATUS.eq(DSL.inline("accepted"))).and(fund.ACTIVE_SLOT.eq(DSL.inline("active")))
                .where(part.WORKSPACE_ID.eq(workspace)).and(part.CASH_TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                    val component = CashComponentSqlRow(row[part.ID]!!, row[part.CASH_TRANSACTION_ID]!!, row[part.ACCOUNT_ID], row[part.AMOUNT], row[part.CURRENCY], row[part.ORDINAL]!!)
                    parts[component.id] = component
                    row[fund.ID]?.let { id -> funding[id] = CashFundingSqlRow(id, row[fund.CASH_TRANSACTION_COMPONENT_ID]!!, row[fund.INVESTMENT_EVENT_ID]!!, row[fund.DIRECTION]!!) }
                }
        }
        val fundingByParent=linkedMapOf<Long,Long>()
        funding.values.forEach {row ->fundingByParent.putIfAbsent(parts.getValue(row.componentId).transactionId,row.id)}
        val facts=try {formal.values.sortedBy {it.id}.map {it.projection(it.id in pending,fundingByParent[it.id],contextOnly=true)}} catch(_:CashProjectionFailure) {relationFailure("relation_endpoint_incompatible")}
        val components=parts.values.filter {it.transactionId in formal}.map {p ->val parent=formal.getValue(p.transactionId)
            CashRelationEndpoint(p.id.toString(),p.transactionId.toString(),p.amount?.let {CashDecimalLiteral(it.text)},p.currency,p.accountId?.toString(),parent.recordType,parent.recordSubtype)
        }
        return CashRelationSqlContext(ids.toSet(),groups,closure,facts,components,rows.map {it.edge()},
            groupMembers.map {CashJointRelationMember(it.id.toString(),it.decisionGroup,it.revision,it.state,it.active)},parents.values.sortedBy {it.id}.map {it.id to it.rowRevision},
            parents.values.filter {it.deletedAt==null}.map {it.id}.toSet(),parts.values.map {CashComponentVersion(it.id,it.transactionId,it.accountId,it.amount?.let(::fixedCashDecimal),it.currency,it.ordinal)},
            rows.map {it.id to it.revision},issues,context.entries.snapshot(closure),rows.mapNotNull {row ->row.decisionGroup?.let {row.id to it}},funding.values.toList(),
            funding.values.filter {parts.getValue(it.componentId).transactionId in formal}.map {it.componentId.toString()}.toSet())
    }
}
