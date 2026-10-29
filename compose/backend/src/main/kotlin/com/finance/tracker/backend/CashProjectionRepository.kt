package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import com.finance.tracker.backend.persistence.generated.tables.records.*
import org.jooq.Query
import org.jooq.Record
import org.jooq.Table
import org.jooq.Field
import org.jooq.impl.DSL
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
    private val sql get()=context.sql
    private val projections = CASH_PROJECTIONS
    private val members = CASH_PROJECTION_MEMBERS
    private val relations = CASH_PROJECTION_RELATIONS
    private val states = CASH_PROJECTION_STATES
    private val datasets = CASH_PROJECTION_DATASETS
    private data class State(val dataset:String?,val version:Long,val sourceRevision:Long,val projectionCount:Long,val memberCount:Long)
    fun status():JsonObject {context.requireActive();return ProjectionStateRepository(c,workspace,context.sqlite).status()}
    fun readValidatedSources()=sources.readValidatedSources()
    private fun state(create:Boolean=false):State? {
        context.requireActive()
        if (!c.lockWorkspace(context.sqlite, workspace)) throw CashProjectionFailure("projection.incomplete")
        val query = sql.select(states.ACTIVE_DATASET_ID, states.PROJECTION_VERSION, states.SOURCE_REVISION, states.PROJECTION_COUNT, states.MEMBER_COUNT)
            .from(states).where(states.WORKSPACE_ID.eq(workspace))
        val row = (if (context.sqlite) query else query.forUpdate()).fetchOne()
        if (row != null) return State(row[states.ACTIVE_DATASET_ID], row[states.PROJECTION_VERSION]!!, row[states.SOURCE_REVISION]!!,
            row[states.PROJECTION_COUNT]!!, row[states.MEMBER_COUNT]!!)
        if (!create) return null
        sql.insertInto(states).set(states.WORKSPACE_ID, workspace).setNull(states.ACTIVE_DATASET_ID).set(states.PROJECTION_VERSION, 0L)
            .set(states.SOURCE_REVISION, 0L).set(states.RULES_VERSION, CASH_PROJECTION_RULES_VERSION).set(states.AVAILABILITY, "uninitialized")
            .set(states.LAST_BUILD_STATUS, "never").setNull(states.LAST_BUILD_ID).setNull(states.LAST_ERROR_CODE).setNull(states.LAST_ERROR_SUMMARY)
            .set(states.PROJECTION_COUNT, 0L).set(states.MEMBER_COUNT, 0L).set(states.UPDATED_AT, now()).execute()
        return State(null, 0, 0, 0, 0)
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
        check(sql.update(states).set(states.LAST_BUILD_STATUS, "running").set(states.LAST_BUILD_ID, dataset).set(states.BUILD_STARTED_AT, at)
            .setNull(states.LAST_ERROR_CODE).setNull(states.LAST_ERROR_SUMMARY).set(states.UPDATED_AT, at).where(states.WORKSPACE_ID.eq(workspace)).execute() == 1)
        sql.insertInto(datasets).set(datasets.ID, dataset).set(datasets.WORKSPACE_ID, workspace).set(datasets.STATE, "staging")
            .set(datasets.SOURCE_REVISION, current.sourceRevision).set(datasets.SOURCE_DIGEST, digest).set(datasets.RULES_VERSION, rulesVersion)
            .set(datasets.CREATED_AT, now()).setNull(datasets.PUBLISHED_AT).execute()
        return dataset
    }
    private fun staging(dataset: String) {
        state()
        if (sql.select(datasets.STATE).from(datasets).where(datasets.WORKSPACE_ID.eq(workspace)).and(datasets.ID.eq(dataset))
                .fetchOne(datasets.STATE) != "staging") throw CashProjectionFailure("projection.incomplete")
    }
    private fun mapping(dataset: String, p: CashProjection, version: Long, entry: CashEntry): CashProjectionsRecord {
        fun text(key: String) = entry.values[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
        val category = text("category_id")
        val path = category?.takeIf { it.isNotEmpty() }?.let { id -> sql.select(CASH_CATEGORIES.CATEGORY_PATH).from(CASH_CATEGORIES)
            .where(CASH_CATEGORIES.WORKSPACE_ID.eq(workspace)).and(CASH_CATEGORIES.ID.eq(id)).fetchOne(CASH_CATEGORIES.CATEGORY_PATH) }
        return CashProjectionsRecord().apply {
            workspaceId = workspace; datasetId = dataset; projectionId = p.projectionId; entryId = entry.id; rootCashTransactionId = p.primaryRecord.id
            fundingRelationId = p.fundingRelationId; state = p.state; statisticsEligible = p.statisticsEligible; attentionCount = p.attentionCount.toLong(); sortAt = p.sortAt
            createdAt = p.primaryRecord.createdAt ?: now(); updatedAt = now(); economicType = p.economicType?.value; transferSubtype = p.transferSubtype
            netAmount = p.netAmount?.let { CashStoredDecimal(it.toString()) }; currency = p.primaryRecord.currency; occurredAt = p.primaryRecord.occurredAt
            accountId = p.primaryRecord.accountId; counterparty = text("counterparty"); categoryId = category; categoryPath = path; note = text("note")
            sourceType = p.primaryRecord.sourceType; recordId = p.primaryRecord.recordId; visible = p.visible; hiddenReason = p.hiddenReason
            hasPaymentMirror = "payment_mirror" in p.compositions; hasRefundOffset = "refund_offset" in p.compositions; hasTransferPair = "transfer_pair" in p.compositions
            memberCount = p.members.size.toLong(); acceptedRelationCount = p.relations.size.toLong(); builtProjectionVersion = version
        }
    }
    fun replaceDataset(dataset:String,build:CashProjectionBuild,projectionVersion:Long) {
        staging(dataset)
        val live=build.projections.flatMap {it.members.map {member ->member.first.id}}.toSet()
        val existing = sql.select(membersCashEntry.TRANSACTION_ID).from(membersCashEntry).where(membersCashEntry.WORKSPACE_ID.eq(workspace))
            .fetch(membersCashEntry.TRANSACTION_ID).filterNotNull().toSet()
        context.entries.removeMembers(existing - live)
        val entries = context.entries.reconcile(build.projections)
        deleteDatasetRows(dataset)
        insertProjections(dataset, build.projections, projectionVersion, entries)
    }
    private val membersCashEntry = CASH_ENTRY_MEMBERS
    private fun deleteDatasetRows(dataset: String) {
        sql.deleteFrom(relations).where(relations.WORKSPACE_ID.eq(workspace)).and(relations.DATASET_ID.eq(dataset)).execute()
        sql.deleteFrom(members).where(members.WORKSPACE_ID.eq(workspace)).and(members.DATASET_ID.eq(dataset)).execute()
        sql.deleteFrom(projections).where(projections.WORKSPACE_ID.eq(workspace)).and(projections.DATASET_ID.eq(dataset)).execute()
    }
    private fun insertProjections(dataset:String,projections:List<CashProjection>,projectionVersion:Long,entries:Map<String,CashEntry>) {
        if(projections.isEmpty()) return
        val identifiers=projections.map {it.projectionId}.toSet();if(identifiers.size!=projections.size) throw CashProjectionFailure("projection.incomplete")
        val parents=projections.map {mapping(dataset,it,projectionVersion,entries.getValue(it.projectionId))}
        val ids=linkedMapOf<String,Long>()
        val table = CASH_PROJECTIONS
        if (parents.size == 1) ids[projections.single().projectionId] = sql.insertInto(table).set(parents.single()).returningResult(table.ID).fetchSingle().value1()!!
        else {
            for (batch in parents.chunked(writeBatchSize)) sql.batchInsert(batch).execute()
            for (batch in identifiers.sortedWith(cashRelationStringOrder).chunked(writeBatchSize)) {
                sql.select(table.ID, table.PROJECTION_ID).from(table).where(table.WORKSPACE_ID.eq(workspace)).and(table.DATASET_ID.eq(dataset))
                    .and(table.PROJECTION_ID.`in`(batch)).fetch().forEach { ids[it[table.PROJECTION_ID]!!] = it[table.ID]!! }
            }
        }
        if (ids.size != projections.size || ids.keys != identifiers) throw CashProjectionFailure("projection.incomplete")
        val memberRows = projections.flatMap { p -> p.members.mapIndexed { ordinal, (fact, roles) -> CashProjectionMembersRecord().apply {
            workspaceId = workspace; datasetId = dataset; projectionRowId = ids.getValue(p.projectionId); cashTransactionId = fact.id
            rolesJson = JsonArray(roles.map(::JsonPrimitive)); this.ordinal = ordinal.toLong()
        } } }
        val relationRows = projections.flatMap { p -> p.relations.mapIndexed { ordinal, relation -> CashProjectionRelationsRecord().apply {
            workspaceId = workspace; datasetId = dataset; projectionRowId = ids.getValue(p.projectionId); transactionRelationId = relation.id.longValueExact()
            kind = relation.kind; subtype = relation.subtype; this.ordinal = ordinal.toLong()
        } } }
        for (batch in memberRows.chunked(writeBatchSize)) sql.batchInsert(batch).execute()
        for (batch in relationRows.chunked(writeBatchSize)) sql.batchInsert(batch).execute()
    }
    fun currentReferences(transactionIds:Set<Long>,dataset:String?):Map<String,JsonObject> {
        context.requireActive();if(dataset==null || transactionIds.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, JsonObject>()
        for (batch in transactionIds.sorted().chunked(500)) {
            val memberQuery = DSL.select(members.CASH_TRANSACTION_ID, members.PROJECTION_ROW_ID).from(members)
                .where(members.WORKSPACE_ID.eq(workspace)).and(members.DATASET_ID.eq(dataset)).and(members.CASH_TRANSACTION_ID.`in`(batch))
            val memberName = DSL.name("reference_members")
            val memberCte = if (context.sqlite) memberName.`as`(memberQuery) else memberName.asMaterialized(memberQuery)
            val memberRow = memberCte.field(members.PROJECTION_ROW_ID)!!
            val projectionQuery = DSL.select(projections.ID, projections.WORKSPACE_ID, projections.DATASET_ID, projections.PROJECTION_ID, projections.ATTENTION_COUNT)
                .from(projections).where(projections.ID.`in`(DSL.select(memberRow).from(memberCte)))
            val projectionName = DSL.name("reference_projections")
            val projectionCte = if (context.sqlite) projectionName.`as`(projectionQuery) else projectionName.asMaterialized(projectionQuery)
            val source = memberCte.field(members.CASH_TRANSACTION_ID)!!
            val projectionId = projectionCte.field(projections.PROJECTION_ID)!!
            val attention = projectionCte.field(projections.ATTENTION_COUNT)!!
            sql.with(memberCte, projectionCte).select(source, projectionId, attention).from(memberCte).join(projectionCte)
                .on(projectionCte.field(projections.ID)!!.eq(memberRow)).where(projectionCte.field(projections.WORKSPACE_ID)!!.eq(workspace))
                .and(projectionCte.field(projections.DATASET_ID)!!.eq(dataset)).fetch().forEach { row ->
                    val id = row[source]!!.toString()
                    result[id] = buildJsonObject { put("transaction_id", id); put("projection_id", row[projectionId]!!); put("attention_count", row[attention]!!) }
                }
        }
        return result
    }
    private data class CurrentProjection(val id:Long,val projectionId:String,val root:Long,val memberCount:Long)
    private fun currentRows(dataset:String,ids:Set<Long>,roots:Boolean):List<CurrentProjection> {
        val result=linkedMapOf<Long,CurrentProjection>()
        for(batch in ids.sorted().chunked(writeBatchSize)) {
            val predicate = if (roots) projections.ROOT_CASH_TRANSACTION_ID.`in`(batch) else projections.ID.`in`(
                DSL.select(members.PROJECTION_ROW_ID).from(members).where(members.WORKSPACE_ID.eq(workspace)).and(members.DATASET_ID.eq(dataset))
                    .and(members.CASH_TRANSACTION_ID.`in`(batch)))
            sql.select(projections.ID, projections.PROJECTION_ID, projections.ROOT_CASH_TRANSACTION_ID, projections.MEMBER_COUNT).from(projections)
                .where(projections.WORKSPACE_ID.eq(workspace)).and(projections.DATASET_ID.eq(dataset)).and(predicate).fetch().forEach { row ->
                    val value = CurrentProjection(row[projections.ID]!!, row[projections.PROJECTION_ID]!!, row[projections.ROOT_CASH_TRANSACTION_ID]!!, row[projections.MEMBER_COUNT]!!)
                    result[value.id] = value
                }
        }
        return result.values.toList()
    }
    private fun oldMembers(dataset: String, rowIds: Set<Long>): Set<Long> {
        val found = mutableSetOf<Long>()
        for (batch in rowIds.sorted().chunked(writeBatchSize)) sql.select(members.CASH_TRANSACTION_ID).from(members)
            .where(members.WORKSPACE_ID.eq(workspace)).and(members.DATASET_ID.eq(dataset)).and(members.PROJECTION_ROW_ID.`in`(batch))
            .fetch().forEach { found += it[members.CASH_TRANSACTION_ID]!! }
        return found
    }
    private fun deleteRows(dataset: String, rowIds: Set<Long>) {
        for (batch in rowIds.sorted().chunked(writeBatchSize)) sql.deleteFrom(relations).where(relations.WORKSPACE_ID.eq(workspace))
            .and(relations.DATASET_ID.eq(dataset)).and(relations.PROJECTION_ROW_ID.`in`(batch)).execute()
        for (batch in rowIds.sorted().chunked(writeBatchSize)) sql.deleteFrom(members).where(members.WORKSPACE_ID.eq(workspace))
            .and(members.DATASET_ID.eq(dataset)).and(members.PROJECTION_ROW_ID.`in`(batch)).execute()
        for (batch in rowIds.sorted().chunked(writeBatchSize)) sql.deleteFrom(projections).where(projections.WORKSPACE_ID.eq(workspace))
            .and(projections.DATASET_ID.eq(dataset)).and(projections.ID.`in`(batch)).execute()
    }
    private fun advance(state:CashLockedProjectionState,projectionCount:Long=state.projectionCount,memberCount:Long=state.memberCount):JsonObject {
        state.requireFor(context);val next=Math.addExact(state.projectionVersion,1);val revision=Math.addExact(state.sourceRevision,1)
        check(sql.update(states).set(states.PROJECTION_VERSION, next).set(states.SOURCE_REVISION, revision).set(states.PROJECTION_COUNT, projectionCount)
            .set(states.MEMBER_COUNT, memberCount).set(states.LAST_BUILD_STATUS, "succeeded").setNull(states.LAST_ERROR_CODE).setNull(states.LAST_ERROR_SUMMARY)
            .set(states.UPDATED_AT, now()).where(states.WORKSPACE_ID.eq(workspace)).execute() == 1)
        state.synchronize(state.datasetId, next, revision, projectionCount, memberCount)
        return status()
    }
    private fun updateRows(dataset: String, updates: List<Pair<CurrentProjection, CashProjectionsRecord>>) {
        for (batch in updates.chunked(writeBatchSize)) {
            val queries = batch.map { (old, values) -> sql.update(projections).set(values).where(projections.WORKSPACE_ID.eq(workspace))
                .and(projections.DATASET_ID.eq(dataset)).and(projections.ID.eq(old.id)) }
            val statement = sql.batch(queries.first())
            queries.forEach { statement.bind(*it.bindValues.toTypedArray()) }
            if (statement.execute().any { it != 1 && it != java.sql.Statement.SUCCESS_NO_INFO }) throw CashProjectionFailure("projection.incomplete")
        }
    }
    private fun sameStructure(dataset:String,old:List<CurrentProjection>,replacement:List<CashProjection>):Boolean {
        val byProjection=old.associateBy {it.projectionId};if(byProjection.keys!=replacement.map {it.projectionId}.toSet()) return false
        val members=old.associate {it.id to mutableListOf<Pair<Long,List<String>>>()};val relations=old.associate {it.id to mutableListOf<Triple<Long,String,String>>()}
        for(batch in old.map {it.id}.sorted().chunked(writeBatchSize)) {
            val member = CASH_PROJECTION_MEMBERS
            sql.select(member.PROJECTION_ROW_ID, member.CASH_TRANSACTION_ID, member.ROLES_JSON).from(member).where(member.WORKSPACE_ID.eq(workspace))
                .and(member.DATASET_ID.eq(dataset)).and(member.PROJECTION_ROW_ID.`in`(batch)).orderBy(member.ORDINAL).fetch().forEach { row ->
                    members.getValue(row[member.PROJECTION_ROW_ID]!!) += row[member.CASH_TRANSACTION_ID]!! to row[member.ROLES_JSON]!!.jsonArray.map { it.jsonPrimitive.content }
                }
            val relation = CASH_PROJECTION_RELATIONS
            sql.select(relation.PROJECTION_ROW_ID, relation.TRANSACTION_RELATION_ID, relation.KIND, relation.SUBTYPE).from(relation)
                .where(relation.WORKSPACE_ID.eq(workspace)).and(relation.DATASET_ID.eq(dataset)).and(relation.PROJECTION_ROW_ID.`in`(batch)).orderBy(relation.ORDINAL)
                .fetch().forEach { row -> relations.getValue(row[relation.PROJECTION_ROW_ID]!!) += Triple(row[relation.TRANSACTION_RELATION_ID]!!, row[relation.KIND]!!, row[relation.SUBTYPE]!!) }
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
        sql.update(projections).set(projections.COUNTERPARTY, counterparty).set(projections.NOTE, note).set(projections.UPDATED_AT, now())
            .set(projections.BUILT_PROJECTION_VERSION, Math.addExact(state.projectionVersion, 1L)).where(projections.WORKSPACE_ID.eq(workspace))
            .and(projections.DATASET_ID.eq(state.datasetId)).and(projections.ROOT_CASH_TRANSACTION_ID.eq(factId)).execute()
        return advance(state)
    }
    fun factFromRow(row:CashFactSqlRow):CashProjectionFact {
        val reads=CashRelationReadRepository(context);val ids=setOf(row.id)
        return row.projection(attention=row.id in reads.attention(ids,reads.graphRowsForTransactions(ids)),fundingId=null)
    }
    fun replaceStandaloneFact(row:CashFactSqlRow,lockedState:CashLockedProjectionState?=null):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context)
        val build=buildCashProjections(listOf(factFromRow(row)),emptyList());val projection=build.projections.single()
        val entries=context.entries.reconcile(build.projections);val next=Math.addExact(state.projectionVersion,1)
        val values = mapping(state.datasetId, projection, next, entries.getValue(projection.projectionId))
        for (field in listOf(projections.WORKSPACE_ID, projections.DATASET_ID, projections.PROJECTION_ID, projections.ROOT_CASH_TRANSACTION_ID)) values.touched(field, false)
        val changed = sql.update(projections).set(values).where(projections.WORKSPACE_ID.eq(workspace)).and(projections.DATASET_ID.eq(state.datasetId))
            .and(projections.ROOT_CASH_TRANSACTION_ID.eq(row.id)).execute()
        if(changed!=1) return replaceActiveComponents(build,setOf(row.id),state)
        return advance(state)
    }
    fun removeActiveComponents(affectedFactIds:Set<Long>,lockedState:CashLockedProjectionState?=null):JsonObject {
        val state=lockedState ?: requireReadyStateLock();state.requireFor(context);context.entries.removeMembers(affectedFactIds)
        val old=currentRows(state.datasetId,affectedFactIds,affectedFactIds.size==1);deleteRows(state.datasetId,old.map {it.id}.toSet())
        return advance(state,Math.subtractExact(state.projectionCount,old.size.toLong()),Math.subtractExact(state.memberCount,old.fold(0L) {sum,row ->Math.addExact(sum,row.memberCount)}))
    }
    private fun countProjections(dataset: String): Long = sql.select(DSL.count().cast(Long::class.java)).from(projections)
        .where(projections.WORKSPACE_ID.eq(workspace)).and(projections.DATASET_ID.eq(dataset)).fetchSingle().value1()!!
    private fun countMembers(dataset: String): Long = sql.select(DSL.count().cast(Long::class.java)).from(members)
        .where(members.WORKSPACE_ID.eq(workspace)).and(members.DATASET_ID.eq(dataset)).fetchSingle().value1()!!
    fun publishDataset(dataset:String,digest:String,rulesVersion:String,proof:CashValidatedProjectionSources?=null):JsonObject {
        val current=state(true)!!;staging(dataset)
        if(dataset==current.dataset) throw CashProjectionFailure("projection.incomplete")
        if(sources.sourceDigest()!=digest) throw CashProjectionFailure("projection.concurrent_update")
        sql.update(datasets).set(datasets.STATE, "staging").where(datasets.WORKSPACE_ID.eq(workspace)).and(datasets.STATE.eq("active")).execute()
        val projectionCount = countProjections(dataset); val memberCount = countMembers(dataset)
        val sourceCount=if(proof==null) sources.readSources().facts.size.toLong() else {
            proof.requireFor(sources,digest)
            val rows = sql.select(DSL.count().cast(Long::class.java)).from(CASH_TRANSACTIONS).where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(workspace))
                .and(CASH_TRANSACTIONS.DELETED_AT.isNull).fetchSingle().value1()!!
            if(rows!=proof.facts.size.toLong()) throw CashProjectionFailure("projection.incomplete");rows
        }
        if(memberCount!=sourceCount) throw CashProjectionFailure("projection.incomplete")
        val next=Math.addExact(current.version,1)
        check(sql.update(datasets).set(datasets.STATE, "active").set(datasets.PUBLISHED_AT, now()).where(datasets.WORKSPACE_ID.eq(workspace))
            .and(datasets.ID.eq(dataset)).execute() == 1)
        check(sql.update(states).set(states.ACTIVE_DATASET_ID, dataset).set(states.PROJECTION_VERSION, next).set(states.AVAILABILITY, "ready")
            .set(states.RULES_VERSION, rulesVersion).set(states.LAST_BUILD_STATUS, "succeeded").set(states.BUILD_FINISHED_AT, now())
            .set(states.PROJECTION_COUNT, projectionCount).set(states.MEMBER_COUNT, memberCount).set(states.UPDATED_AT, now())
            .where(states.WORKSPACE_ID.eq(workspace)).execute() == 1)
        current.dataset?.let { old -> sql.deleteFrom(datasets).where(datasets.WORKSPACE_ID.eq(workspace)).and(datasets.ID.eq(old)).execute() }
        context.projectionState?.synchronize(dataset, next, current.sourceRevision, projectionCount, memberCount)
        return status()
    }
    fun recordFailedBuild(expected:CashProjectionFailureContext,errorCode:String):Boolean {
        val current=state(true)!!;if(current.dataset!=expected.activeDatasetId || current.sourceRevision!=expected.sourceRevision) return false
        check(sql.update(states).set(states.LAST_BUILD_STATUS, "failed").set(states.LAST_ERROR_CODE, errorCode).set(states.LAST_ERROR_SUMMARY, "收支投影构建失败")
            .set(states.BUILD_FINISHED_AT, now()).set(states.UPDATED_AT, now()).where(states.WORKSPACE_ID.eq(workspace)).execute() == 1)
        return true
    }
}
