package com.finance.tracker.backend

import java.sql.PreparedStatement
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.*

internal data class CashProjectionFailureContext(val activeDatasetId:String?,val sourceRevision:Long)
internal class CashLockedProjectionState(private val owner:CashSqlContext,dataset:String,version:Long,revision:Long,projections:Long,members:Long) {
    var datasetId=dataset;private set
    var projectionVersion=version;private set
    var sourceRevision=revision;private set
    var projectionCount=projections;private set
    var memberCount=members;private set
    private val transaction=owner.requireTransactionStamp()
    fun requireFor(context:CashSqlContext) {if(owner!==context || context.projectionState!==this || !transaction.isCurrent()) throw CashProjectionFailure("projection.unavailable")}
    fun synchronize(dataset:String,version:Long,revision:Long,projections:Long,members:Long) {
        datasetId=dataset;projectionVersion=version;sourceRevision=revision;projectionCount=projections;memberCount=members
    }
}
internal class CashProjectionRepository(private val context:CashSqlContext,private val now:()->Instant=context.now,
    val writeBatchSize:Int=900,private val newId:()->String={UUID.randomUUID().toString()}) {
    init {require(writeBatchSize>0)}
    internal val sources=CashProjectionSourceRepository(context)
    private val c get()=context.connection
    private val workspace get()=context.workspace
    private data class State(val dataset:String?,val version:Long,val sourceRevision:Long,val projectionCount:Long,val memberCount:Long)
    fun status():JsonObject {context.requireActive();return ProjectionStateRepository(c,workspace,context.sqlite).status()}
    fun readValidatedSources()=sources.readValidatedSources()
    private fun state(create:Boolean=false):State? {
        context.requireActive()
        c.prepareStatement("SELECT id FROM workspaces WHERE id=?"+(if(context.sqlite) "" else " FOR UPDATE")).use {it.setString(1,workspace);it.executeQuery().use {r ->if(!r.next()) throw CashProjectionFailure("projection.incomplete")}}
        fun read():State?=c.prepareStatement("SELECT active_dataset_id,projection_version,source_revision,projection_count,member_count FROM cash_projection_states WHERE workspace_id=?"+(if(context.sqlite) "" else " FOR UPDATE")).use {
            it.setString(1,workspace);it.executeQuery().use {r ->if(r.next()) State(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5)) else null}
        }
        return read() ?: if(!create) null else {
            insert("cash_projection_states",listOf(linkedMapOf("workspace_id" to workspace,"active_dataset_id" to null,"projection_version" to 0L,"source_revision" to 0L,
                "rules_version" to CASH_PROJECTION_RULES_VERSION,"availability" to "uninitialized","last_build_status" to "never","last_build_id" to null,
                "last_error_code" to null,"last_error_summary" to null,"projection_count" to 0L,"member_count" to 0L,"updated_at" to now())))
            State(null,0,0,0,0)
        }
    }
    fun rebuildFailureContext():CashProjectionFailureContext=state(true)!!.let {CashProjectionFailureContext(it.dataset,it.sourceRevision)}
    fun readyStateLockOrNone():CashLockedProjectionState? {
        val current=state()
        if(current==null || current.dataset.isNullOrEmpty() || status().getValue("availability").jsonPrimitive.content!="ready") {context.projectionState=null;return null}
        val cached=context.projectionState
        if(cached!=null) {cached.requireFor(context);cached.synchronize(current.dataset,current.version,current.sourceRevision,current.projectionCount,current.memberCount);return cached}
        return CashLockedProjectionState(context,current.dataset,current.version,current.sourceRevision,current.projectionCount,current.memberCount).also {context.projectionState=it}
    }
    fun requireReadyStateLock():CashLockedProjectionState=readyStateLockOrNone() ?: throw CashProjectionFailure("projection.unavailable")
    fun createStagingDataset(digest:String,rulesVersion:String):String {
        val current=state(true)!!;val dataset=newId();val at=now()
        c.prepareStatement("UPDATE cash_projection_states SET last_build_status='running',last_build_id=?,build_started_at=?,last_error_code=NULL,last_error_summary=NULL,updated_at=? WHERE workspace_id=?").use {
            it.setString(1,dataset);it.instant(2,at,context.sqlite);it.instant(3,at,context.sqlite);it.setString(4,workspace);check(it.executeUpdate()==1)
        }
        insert("cash_projection_datasets",listOf(linkedMapOf("id" to dataset,"workspace_id" to workspace,"state" to "staging","source_revision" to current.sourceRevision,
            "source_digest" to digest,"rules_version" to rulesVersion,"created_at" to now(),"published_at" to null)))
        return dataset
    }
    private fun staging(dataset:String) {
        state()
        c.prepareStatement("SELECT state FROM cash_projection_datasets WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.executeQuery().use {r ->
            if(!r.next() || r.getString(1)!="staging") throw CashProjectionFailure("projection.incomplete")
        }}
    }
    private fun bind(s:PreparedStatement,index:Int,value:Any?) {when(value) {
        is Instant ->s.instant(index,value,context.sqlite)
        is JsonElement ->s.setString(index,value.toString())
        is CashAmount ->if(context.sqlite) s.setString(index,fixedCashDecimal(CashStoredDecimal(value.toString()))) else s.setBigDecimal(index,value.arithmeticValue())
        else ->s.setObject(index,value)
    }}
    private fun insert(table:String,rows:List<Map<String,Any?>>,returnId:Boolean=false):Long? {
        if(rows.isEmpty()) return null
        val keys=rows.first().keys.toList();check(rows.all {it.keys.toList()==keys})
        val slots=keys.joinToString(",") {key ->if(rows.first()[key] is JsonElement && !context.sqlite) "CAST(? AS json)" else "?"}
        val sql="INSERT INTO $table(${keys.joinToString(",")}) VALUES($slots)"+(if(returnId) " RETURNING id" else "")
        c.prepareStatement(sql).use {statement ->
            if(returnId) {check(rows.size==1);keys.forEachIndexed {i,key ->bind(statement,i+1,rows.single()[key])};return statement.executeQuery().use {r ->if(!r.next()) throw CashProjectionFailure("projection.incomplete");r.getLong(1)}}
            for(batch in rows.chunked(writeBatchSize)) {for(row in batch) {keys.forEachIndexed {i,key ->bind(statement,i+1,row[key])};statement.addBatch()};statement.executeBatch()}
        };return null
    }
    private fun mapping(dataset:String,p:CashProjection,version:Long,entry:CashEntry):Map<String,Any?> {
        fun text(key:String)=entry.values[key]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
        val category=text("category_id")
        val path=category?.takeIf {it.isNotEmpty()}?.let {id ->c.prepareStatement("SELECT category_path FROM cash_categories WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setString(2,id);it.executeQuery().use {r ->if(r.next()) r.getString(1) else null}}}
        return linkedMapOf("workspace_id" to workspace,"dataset_id" to dataset,"projection_id" to p.projectionId,"entry_id" to entry.id,"root_cash_transaction_id" to p.primaryRecord.id,
            "funding_relation_id" to p.fundingRelationId,"state" to p.state,"statistics_eligible" to p.statisticsEligible,"attention_count" to p.attentionCount,"sort_at" to p.sortAt,
            "created_at" to (p.primaryRecord.createdAt ?: now()),"updated_at" to now(),"economic_type" to p.economicType?.value,"transfer_subtype" to p.transferSubtype,"net_amount" to p.netAmount,
            "currency" to p.primaryRecord.currency,"occurred_at" to p.primaryRecord.occurredAt,"account_id" to p.primaryRecord.accountId,"counterparty" to text("counterparty"),
            "category_id" to category,"category_path" to path,"note" to text("note"),"source_type" to p.primaryRecord.sourceType,"record_id" to p.primaryRecord.recordId,"visible" to p.visible,
            "hidden_reason" to p.hiddenReason,"has_payment_mirror" to ("payment_mirror" in p.compositions),"has_refund_offset" to ("refund_offset" in p.compositions),"has_transfer_pair" to ("transfer_pair" in p.compositions),
            "member_count" to p.members.size,"accepted_relation_count" to p.relations.size,"built_projection_version" to version)
    }
    fun replaceDataset(dataset:String,build:CashProjectionBuild,projectionVersion:Long) {
        staging(dataset)
        val live=build.projections.flatMap {it.members.map {member ->member.first.id}}.toSet()
        val existing=c.prepareStatement("SELECT transaction_id FROM cash_entry_members WHERE workspace_id=?").use {it.setString(1,workspace);it.executeQuery().use {r ->buildSet {while(r.next()) add(r.getLong(1))}}}
        context.entries.removeMembers(existing-live)
        val entries=context.entries.reconcile(build.projections)
        for(table in listOf("cash_projection_relations","cash_projection_members","cash_projections")) c.prepareStatement("DELETE FROM $table WHERE workspace_id=? AND dataset_id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.executeUpdate()}
        insertProjections(dataset,build.projections,projectionVersion,entries)
    }
    private fun insertProjections(dataset:String,projections:List<CashProjection>,projectionVersion:Long,entries:Map<String,CashEntry>) {
        if(projections.isEmpty()) return
        val identifiers=projections.map {it.projectionId}.toSet();if(identifiers.size!=projections.size) throw CashProjectionFailure("projection.incomplete")
        val parents=projections.map {mapping(dataset,it,projectionVersion,entries.getValue(it.projectionId))}
        val ids=linkedMapOf<String,Long>()
        if(parents.size==1) ids[projections.single().projectionId]=insert("cash_projections",parents,true)!!
        else {
            insert("cash_projections",parents)
            for(batch in identifiers.sortedWith(cashRelationStringOrder).chunked(writeBatchSize)) c.prepareStatement("SELECT id,projection_id FROM cash_projections WHERE workspace_id=? AND dataset_id=? AND projection_id IN (${batch.joinToString(",") {"?"}})").use {
                it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setString(i+3,id)};it.executeQuery().use {r ->while(r.next()) ids[r.getString(2)]=r.getLong(1)}
            }
        }
        if(ids.size!=projections.size || ids.keys!=identifiers) throw CashProjectionFailure("projection.incomplete")
        val members=projections.flatMap {p ->p.members.mapIndexed {ordinal,(fact,roles) ->linkedMapOf<String,Any?>("workspace_id" to workspace,"dataset_id" to dataset,"projection_row_id" to ids.getValue(p.projectionId),
            "cash_transaction_id" to fact.id,"roles_json" to JsonArray(roles.map(::JsonPrimitive)),"ordinal" to ordinal)}}
        val relations=projections.flatMap {p ->p.relations.mapIndexed {ordinal,relation ->linkedMapOf<String,Any?>("workspace_id" to workspace,"dataset_id" to dataset,"projection_row_id" to ids.getValue(p.projectionId),
            "transaction_relation_id" to relation.id.longValueExact(),"kind" to relation.kind,"subtype" to relation.subtype,"ordinal" to ordinal)}}
        insert("cash_projection_members",members);insert("cash_projection_relations",relations)
    }
    fun currentReferences(transactionIds:Set<Long>,dataset:String?):Map<String,JsonObject> {
        context.requireActive();if(dataset==null || transactionIds.isEmpty()) return emptyMap()
        val result=linkedMapOf<String,JsonObject>();val materialized=if(context.sqlite) "" else "MATERIALIZED "
        for(batch in transactionIds.sorted().chunked(500)) c.prepareStatement("WITH reference_members AS ${materialized}(SELECT cash_transaction_id,projection_row_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND cash_transaction_id IN (${batch.joinToString(",") {"?"}})), reference_projections AS ${materialized}(SELECT id,workspace_id,dataset_id,projection_id,attention_count FROM cash_projections WHERE id IN (SELECT projection_row_id FROM reference_members)) SELECT m.cash_transaction_id,p.projection_id,p.attention_count FROM reference_members m JOIN reference_projections p ON p.id=m.projection_row_id WHERE p.workspace_id=? AND p.dataset_id=?").use {
            it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setLong(i+3,id)};it.setString(batch.size+3,workspace);it.setString(batch.size+4,dataset)
            it.executeQuery().use {r ->while(r.next()) {val id=r.getLong(1).toString();result[id]=buildJsonObject {put("transaction_id",id);put("projection_id",r.getString(2));put("attention_count",r.getLong(3))}}}
        };return result
    }
    private data class CurrentProjection(val id:Long,val projectionId:String,val root:Long,val memberCount:Long)
    private fun currentRows(dataset:String,ids:Set<Long>,roots:Boolean):List<CurrentProjection> {
        val result=linkedMapOf<Long,CurrentProjection>()
        for(batch in ids.sorted().chunked(writeBatchSize)) {
            val predicate=if(roots) "root_cash_transaction_id IN (${batch.joinToString(",") {"?"}})" else "id IN (SELECT projection_row_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND cash_transaction_id IN (${batch.joinToString(",") {"?"}}))"
            c.prepareStatement("SELECT id,projection_id,root_cash_transaction_id,member_count FROM cash_projections WHERE workspace_id=? AND dataset_id=? AND $predicate").use {
                it.setString(1,workspace);it.setString(2,dataset);var offset=3
                if(!roots) {it.setString(offset++,workspace);it.setString(offset++,dataset)}
                batch.forEachIndexed {i,id ->it.setLong(offset+i,id)};it.executeQuery().use {r ->while(r.next()) {val row=CurrentProjection(r.getLong(1),r.getString(2),r.getLong(3),r.getLong(4));result[row.id]=row}}
            }
        };return result.values.toList()
    }
    private fun oldMembers(dataset:String,rowIds:Set<Long>):Set<Long> {
        val found=mutableSetOf<Long>()
        for(batch in rowIds.sorted().chunked(writeBatchSize)) c.prepareStatement("SELECT cash_transaction_id FROM cash_projection_members WHERE workspace_id=? AND dataset_id=? AND projection_row_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setLong(i+3,id)};it.executeQuery().use {r ->while(r.next()) found+=r.getLong(1)}
        };return found
    }
    private fun deleteRows(dataset:String,rowIds:Set<Long>) {
        for(table in listOf("cash_projection_relations","cash_projection_members","cash_projections")) for(batch in rowIds.sorted().chunked(writeBatchSize)) c.prepareStatement("DELETE FROM $table WHERE workspace_id=? AND dataset_id=? AND ${if(table=="cash_projections") "id" else "projection_row_id"} IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setLong(i+3,id)};it.executeUpdate()
        }
    }
    private fun advance(state:CashLockedProjectionState,projectionCount:Long=state.projectionCount,memberCount:Long=state.memberCount):JsonObject {
        state.requireFor(context);val next=Math.addExact(state.projectionVersion,1);val revision=Math.addExact(state.sourceRevision,1)
        c.prepareStatement("UPDATE cash_projection_states SET projection_version=?,source_revision=?,projection_count=?,member_count=?,last_build_status='succeeded',last_error_code=NULL,last_error_summary=NULL,updated_at=? WHERE workspace_id=?").use {
            it.setLong(1,next);it.setLong(2,revision);it.setLong(3,projectionCount);it.setLong(4,memberCount);it.instant(5,now(),context.sqlite);it.setString(6,workspace);check(it.executeUpdate()==1)
        }
        state.synchronize(state.datasetId,next,revision,projectionCount,memberCount)
        return status()
    }
    private fun updateRows(dataset:String,updates:List<Pair<CurrentProjection,Map<String,Any?>>>) {
        if(updates.isEmpty()) return
        val keys=updates.first().second.keys.toList()
        val assignments=keys.joinToString(",") {key ->"$key="+(if(updates.first().second[key] is JsonElement && !context.sqlite) "CAST(? AS json)" else "?")}
        c.prepareStatement("UPDATE cash_projections SET $assignments WHERE workspace_id=? AND dataset_id=? AND id=?").use {s ->
            for(batch in updates.chunked(writeBatchSize)) {for((old,values) in batch) {
                check(values.keys.toList()==keys);keys.forEachIndexed {i,key ->bind(s,i+1,values[key])};s.setString(keys.size+1,workspace);s.setString(keys.size+2,dataset);s.setLong(keys.size+3,old.id);s.addBatch()
            };if(s.executeBatch().any {it!=1 && it!=java.sql.Statement.SUCCESS_NO_INFO}) throw CashProjectionFailure("projection.incomplete")}
        }
    }
    private fun sameStructure(dataset:String,old:List<CurrentProjection>,replacement:List<CashProjection>):Boolean {
        val byProjection=old.associateBy {it.projectionId};if(byProjection.keys!=replacement.map {it.projectionId}.toSet()) return false
        val members=old.associate {it.id to mutableListOf<Pair<Long,List<String>>>()};val relations=old.associate {it.id to mutableListOf<Triple<Long,String,String>>()}
        for(batch in old.map {it.id}.sorted().chunked(writeBatchSize)) {
            val suffix=" WHERE workspace_id=? AND dataset_id=? AND projection_row_id IN (${batch.joinToString(",") {"?"}}) ORDER BY ordinal"
            c.prepareStatement("SELECT projection_row_id,cash_transaction_id,roles_json FROM cash_projection_members"+suffix).use {
                it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setLong(i+3,id)};it.executeQuery().use {r ->while(r.next()) members.getValue(r.getLong(1))+=r.getLong(2) to Json.parseToJsonElement(r.getString(3)).jsonArray.map {v ->v.jsonPrimitive.content}}
            }
            c.prepareStatement("SELECT projection_row_id,transaction_relation_id,kind,subtype FROM cash_projection_relations"+suffix).use {
                it.setString(1,workspace);it.setString(2,dataset);batch.forEachIndexed {i,id ->it.setLong(i+3,id)};it.executeQuery().use {r ->while(r.next()) relations.getValue(r.getLong(1))+=Triple(r.getLong(2),r.getString(3),r.getString(4))}
            }
        }
        return replacement.all {p ->val id=byProjection.getValue(p.projectionId).id
            members.getValue(id)==p.members.map {it.first.id to it.second} && relations.getValue(id)==p.relations.map {Triple(it.id.longValueExact(),it.kind,it.subtype)}
        }
    }
    fun replaceActiveComponents(build:CashProjectionBuild,affectedFactIds:Set<Long>,lockedState:CashLockedProjectionState?=null,knownNewFactIds:Set<Long> = emptySet()):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context)
        val visible=build.projections.flatMap {it.members.map {m ->m.first.id}}.toSet();context.entries.removeMembers(affectedFactIds-visible)
        val entries=context.entries.reconcile(build.projections);val dataset=state.datasetId;val lookup=affectedFactIds-knownNewFactIds
        val roots=currentRows(dataset,lookup,true);val old=(roots+currentRows(dataset,lookup-roots.map {it.root}.toSet(),false)).distinctBy {it.id}
        val oldIds=old.map {it.id}.toSet();val next=Math.addExact(state.projectionVersion,1)
        val replacement=build.projections.filter {p ->p.memberIds.any {it in affectedFactIds}}
        fun update() {val byProjection=old.associateBy {it.projectionId};updateRows(dataset,replacement.map {p ->byProjection.getValue(p.projectionId) to mapping(dataset,p,next,entries.getValue(p.projectionId))})}
        if(old.isNotEmpty() && old.size==lookup.size && replacement.size==old.size && old.all {it.memberCount==1L} && replacement.all {it.relations.isEmpty()} &&
            old.all {row ->replacement.singleOrNull {it.projectionId==row.projectionId}?.memberIds==listOf(row.root)}) {update();return advance(state)}
        val oldMemberIds=oldMembers(dataset,oldIds)
        if(old.size==1 && replacement.size==1 && old.single().memberCount==1L && replacement.single().members.size==1 && replacement.single().relations.isEmpty() &&
            replacement.single().projectionId==old.single().projectionId && replacement.single().memberIds.toSet()==oldMemberIds) {update();return advance(state)}
        if(sameStructure(dataset,old,replacement)) {update();return advance(state)}
        deleteRows(dataset,oldIds);insertProjections(dataset,replacement,next,entries)
        val members=replacement.flatMap {p ->p.members.map {it.first.id}}
        if(members.toSet().size!=members.size) throw CashProjectionFailure("projection.incomplete")
        return advance(state,Math.addExact(Math.subtractExact(state.projectionCount,old.size.toLong()),replacement.size.toLong()),Math.addExact(Math.subtractExact(state.memberCount,oldMemberIds.size.toLong()),members.size.toLong()))
    }
    fun refreshDisplayFields(factId:Long,counterparty:String,note:String,lockedState:CashLockedProjectionState?=null):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context)
        c.prepareStatement("UPDATE cash_projections SET counterparty=?,note=?,updated_at=?,built_projection_version=? WHERE workspace_id=? AND dataset_id=? AND root_cash_transaction_id=?").use {
            it.setString(1,counterparty);it.setString(2,note);it.instant(3,now(),context.sqlite);it.setLong(4,Math.addExact(state.projectionVersion,1));it.setString(5,workspace);it.setString(6,state.datasetId);it.setLong(7,factId);it.executeUpdate()
        };return advance(state)
    }
    fun factFromRow(row:CashFactSqlRow):CashProjectionFact {
        val reads=CashRelationReadRepository(context);val ids=setOf(row.id)
        return row.projection(attention=row.id in reads.attention(ids,reads.graphRowsForTransactions(ids)),fundingId=null)
    }
    fun replaceStandaloneFact(row:CashFactSqlRow,lockedState:CashLockedProjectionState?=null):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context)
        val build=buildCashProjections(listOf(factFromRow(row)),emptyList());val projection=build.projections.single()
        val entries=context.entries.reconcile(build.projections);val next=Math.addExact(state.projectionVersion,1)
        val values=mapping(state.datasetId,projection,next,entries.getValue(projection.projectionId)).filterKeys {it !in setOf("workspace_id","dataset_id","projection_id","root_cash_transaction_id")}
        val keys=values.keys.toList()
        val assignments=keys.joinToString(",") {key ->"$key="+(if(values[key] is JsonElement && !context.sqlite) "CAST(? AS json)" else "?")}
        val changed=c.prepareStatement("UPDATE cash_projections SET $assignments WHERE workspace_id=? AND dataset_id=? AND root_cash_transaction_id=?").use {s ->
            keys.forEachIndexed {index,key ->bind(s,index+1,values[key])};s.setString(keys.size+1,workspace);s.setString(keys.size+2,state.datasetId);s.setLong(keys.size+3,row.id);s.executeUpdate()
        }
        if(changed!=1) return replaceActiveComponents(build,setOf(row.id),state)
        return advance(state)
    }
    fun removeActiveComponents(affectedFactIds:Set<Long>,lockedState:CashLockedProjectionState?=null):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context);context.entries.removeMembers(affectedFactIds)
        val old=currentRows(state.datasetId,affectedFactIds,affectedFactIds.size==1);deleteRows(state.datasetId,old.map {it.id}.toSet())
        return advance(state,Math.subtractExact(state.projectionCount,old.size.toLong()),Math.subtractExact(state.memberCount,old.fold(0L) {sum,row ->Math.addExact(sum,row.memberCount)}))
    }
    private fun count(table:String,dataset:String):Long=c.prepareStatement("SELECT count(*) FROM $table WHERE workspace_id=? AND dataset_id=?").use {it.setString(1,workspace);it.setString(2,dataset);it.executeQuery().use {r ->check(r.next());r.getLong(1)}}
    fun publishDataset(dataset:String,digest:String,rulesVersion:String,proof:CashValidatedProjectionSources?=null):JsonObject {
        val current=state(true)!!;staging(dataset)
        if(dataset==current.dataset) throw CashProjectionFailure("projection.incomplete")
        if(sources.sourceDigest()!=digest) throw CashProjectionFailure("projection.concurrent_update")
        c.prepareStatement("UPDATE cash_projection_datasets SET state='staging' WHERE workspace_id=? AND state='active'").use {it.setString(1,workspace);it.executeUpdate()}
        val projections=count("cash_projections",dataset);val members=count("cash_projection_members",dataset)
        val sourceCount=if(proof==null) sources.readSources().facts.size.toLong() else {
            proof.requireFor(sources,digest)
            val rows=c.prepareStatement("SELECT count(*) FROM cash_transactions WHERE workspace_id=? AND deleted_at IS NULL").use {it.setString(1,workspace);it.executeQuery().use {r ->check(r.next());r.getLong(1)}}
            if(rows!=proof.facts.size.toLong()) throw CashProjectionFailure("projection.incomplete");rows
        }
        if(members!=sourceCount) throw CashProjectionFailure("projection.incomplete")
        val next=Math.addExact(current.version,1)
        c.prepareStatement("UPDATE cash_projection_datasets SET state='active',published_at=? WHERE workspace_id=? AND id=?").use {it.instant(1,now(),context.sqlite);it.setString(2,workspace);it.setString(3,dataset);check(it.executeUpdate()==1)}
        c.prepareStatement("UPDATE cash_projection_states SET active_dataset_id=?,projection_version=?,availability='ready',rules_version=?,last_build_status='succeeded',build_finished_at=?,projection_count=?,member_count=?,updated_at=? WHERE workspace_id=?").use {
            it.setString(1,dataset);it.setLong(2,next);it.setString(3,rulesVersion);it.instant(4,now(),context.sqlite);it.setLong(5,projections);it.setLong(6,members);it.instant(7,now(),context.sqlite);it.setString(8,workspace);check(it.executeUpdate()==1)
        }
        current.dataset?.let {old ->c.prepareStatement("DELETE FROM cash_projection_datasets WHERE workspace_id=? AND id=?").use {it.setString(1,workspace);it.setString(2,old);it.executeUpdate()}}
        context.projectionState?.synchronize(dataset,next,current.sourceRevision,projections,members)
        return status()
    }
    fun recordFailedBuild(expected:CashProjectionFailureContext,errorCode:String):Boolean {
        val current=state(true)!!;if(current.dataset!=expected.activeDatasetId || current.sourceRevision!=expected.sourceRevision) return false
        c.prepareStatement("UPDATE cash_projection_states SET last_build_status='failed',last_error_code=?,last_error_summary=?,build_finished_at=?,updated_at=? WHERE workspace_id=?").use {
            it.setString(1,errorCode);it.setString(2,"收支投影构建失败");it.instant(3,now(),context.sqlite);it.instant(4,now(),context.sqlite);it.setString(5,workspace);check(it.executeUpdate()==1)
        };return true
    }
}
