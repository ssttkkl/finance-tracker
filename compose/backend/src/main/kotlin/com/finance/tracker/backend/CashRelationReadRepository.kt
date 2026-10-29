package com.finance.tracker.backend

import java.sql.ResultSet

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
    private val c get()=context.connection
    private val workspace get()=context.workspace
    internal val graphColumns="id,kind,subtype,primary_transaction_id,secondary_transaction_id,primary_component_id,secondary_component_id,primary_role,secondary_role,applied_amount,state,active,decision_group_id,row_revision"
    private fun checked(ids:Set<Long>):List<Long> {if(ids.any {it<=0}) relationFailure("relation_data_invalid");return ids.sorted()}
    internal fun row(r:ResultSet,rule:Boolean=false)=CashRelationSqlRow(r.getLong("id"),r.getString("kind"),r.getString("subtype"),r.getLong("primary_transaction_id"),r.nullableLong("secondary_transaction_id"),
        r.nullableLong("primary_component_id"),r.nullableLong("secondary_component_id"),r.getString("primary_role"),r.getString("secondary_role"),r.cashDecimal("applied_amount",context.sqlite)!!,
        r.getString("state"),r.getBoolean("active"),r.getString("decision_group_id"),r.getLong("row_revision"),if(rule) r.getString("rule_id") else "")
    fun graphRowsForTransactions(ids:Set<Long>):List<CashRelationSqlRow> {
        val result=sortedMapOf<Long,CashRelationSqlRow>()
        for(batch in checked(ids).chunked(500)) {
            val columns=if(context.sqlite) "id" else graphColumns
            val branches=listOf("primary_transaction_id","secondary_transaction_id","anchor_transaction_id").map {endpoint ->
                "SELECT $columns FROM transaction_relations WHERE workspace_id=? AND active=true AND $endpoint IN (${batch.joinToString(",") {"?"}})"
            }
            val sql=if(context.sqlite) "SELECT $graphColumns FROM transaction_relations WHERE workspace_id=? AND id IN (${branches.joinToString(" UNION ")})" else branches.joinToString(" UNION ALL ")
            c.prepareStatement(sql).use {
                var index=1;if(context.sqlite) it.setString(index++,workspace)
                repeat(3) {_ ->it.setString(index++,workspace);batch.forEach {id ->it.setLong(index++,id)}}
                it.executeQuery().use {r ->while(r.next()) {val relation=row(r);if(relation.active) result[relation.id]=relation}}
            }
        };return result.values.toList()
    }
    fun graphRowsForGroups(groups:List<String>):List<CashRelationSqlRow> {
        val result=sortedMapOf<Long,CashRelationSqlRow>()
        for(batch in groups.distinct().sortedWith(cashRelationStringOrder).chunked(500)) c.prepareStatement("SELECT $graphColumns FROM transaction_relations WHERE workspace_id=? AND decision_group_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,group ->it.setString(index+2,group)};it.executeQuery().use {r ->while(r.next()) {val relation=row(r);result[relation.id]=relation}}
        };return result.values.toList()
    }
    fun confirmedClosure(ids:Set<Long>):Set<Long> {
        val result=sortedSetOf<Long>()
        for(batch in checked(ids).chunked(500)) {
            val seeds=batch.filter {it !in result};if(seeds.isEmpty()) continue
            val marks=seeds.joinToString(",") {"?"}
            val sql=if(context.sqlite) """WITH RECURSIVE relation_directions(direction) AS (SELECT 0 UNION ALL SELECT 1), current_relation_closure(node) AS (
                SELECT id FROM cash_transactions WHERE workspace_id=? AND id IN ($marks)
                UNION SELECT CASE WHEN d.direction=0 THEN p.secondary_transaction_id ELSE s.primary_transaction_id END
                FROM current_relation_closure reached CROSS JOIN relation_directions d
                LEFT JOIN transaction_relations p ON d.direction=0 AND p.workspace_id=? AND p.primary_transaction_id=reached.node AND p.active=true AND p.state='confirmed'
                LEFT JOIN transaction_relations s ON d.direction=1 AND s.workspace_id=? AND s.secondary_transaction_id=reached.node AND s.active=true AND s.state='confirmed'
                WHERE CASE WHEN d.direction=0 THEN p.secondary_transaction_id ELSE s.primary_transaction_id END IS NOT NULL) SELECT node FROM current_relation_closure"""
            else """WITH RECURSIVE current_relation_closure(node) AS (SELECT id FROM cash_transactions WHERE workspace_id=? AND id IN ($marks)
                UNION SELECT neighbours.node FROM current_relation_closure reached JOIN LATERAL (
                    SELECT secondary_transaction_id AS node FROM transaction_relations WHERE workspace_id=? AND active=true AND state='confirmed' AND primary_transaction_id=reached.node AND secondary_transaction_id IS NOT NULL
                    UNION ALL SELECT primary_transaction_id AS node FROM transaction_relations WHERE workspace_id=? AND active=true AND state='confirmed' AND secondary_transaction_id=reached.node AND primary_transaction_id IS NOT NULL OFFSET 0
                ) neighbours ON true) SELECT node FROM current_relation_closure"""
            c.prepareStatement(sql).use {
                var index=1;it.setString(index++,workspace);seeds.forEach {id ->it.setLong(index++,id)};it.setString(index++,workspace);it.setString(index,workspace)
                it.executeQuery().use {r ->while(r.next()) result+=r.getLong(1)}
            }
        };return result
    }
    fun issues(ids:Set<Long>):List<CashIssueVersion> {
        val result=sortedMapOf<Long,CashIssueVersion>()
        for(batch in ids.sorted().chunked(500)) c.prepareStatement("SELECT id,transaction_id,revision FROM cash_transaction_issues WHERE workspace_id=? AND transaction_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};it.executeQuery().use {r ->while(r.next()) {val issue=CashIssueVersion(r.getLong(1),r.getLong(2),r.getLong(3));result[issue.id]=issue}}
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
        for(batch in parents.keys.sorted().chunked(500)) c.prepareStatement("""SELECT p.id,p.cash_transaction_id,p.account_id,p.amount,p.currency,p.ordinal,
            f.id AS funding_id,f.cash_transaction_component_id AS funding_component,f.investment_event_id AS funding_event,f.direction AS funding_direction
            FROM cash_transaction_components p LEFT OUTER JOIN cash_investment_funding_relations f ON f.workspace_id=? AND f.cash_transaction_component_id=p.id AND f.status='accepted' AND f.active_slot='active'
            WHERE p.workspace_id=? AND p.cash_transaction_id IN (${batch.joinToString(",") {"?"}})""").use {
            it.setString(1,workspace);it.setString(2,workspace);batch.forEachIndexed {index,id ->it.setLong(index+3,id)};it.executeQuery().use {r ->while(r.next()) {
                val part=CashComponentSqlRow(r.getLong("id"),r.getLong("cash_transaction_id"),r.nullableLong("account_id"),r.cashDecimal("amount",context.sqlite),r.getString("currency"),r.getLong("ordinal"));parts[part.id]=part
                r.nullableLong("funding_id")?.let {id ->funding[id]=CashFundingSqlRow(id,r.getLong("funding_component"),r.getLong("funding_event"),r.getString("funding_direction"))}
            }}
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
