package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*
import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Query
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

class CashClassificationService(private val database:Database,private val now:()->Instant=Instant::now,
    private val beforeWrite:()->Unit={},private val beforeWorkspaceLock:()->Unit={}) {
    private val reads=ReadServices(database,now)
    private data class Entry(val id:String,val root:Long,val revision:Long,val category:String?,val fields:JsonElement)
    private data class Member(val id:Long,val values:JsonElement,val fields:JsonElement)
    private data class Selection(val id:Long,val projectionId:String,val root:Long,val count:Int,val category:String?,val path:String?,val entry:Entry,val members:MutableMap<Long,Member>)
    private fun iterable(value:JsonElement):List<JsonElement> = when(value) {
        is JsonArray ->value
        is JsonObject ->value.keys.map(::JsonPrimitive)
        is JsonPrimitive ->if(value.isString) value.content.codePoints().toArray().map {JsonPrimitive(String(Character.toChars(it)))} else error("value is not iterable")
        else ->error("value is not iterable")
    }
    private fun protected(fields:JsonElement):Boolean=when(fields) {
        is JsonPrimitive ->if(fields.isString) "category_id" in fields.content else error("manual fields are not iterable")
        else ->iterable(fields).any {it is JsonPrimitive && it.isString && it.content=="category_id"}
    }
    private fun protect(fields:JsonElement):JsonArray=JsonArray((iterable(fields).map {
        check(it is JsonPrimitive && it.isString) {"manual fields are not sortable strings"};it.content
    }+"category_id").distinct().sortedWith(cashRelationStringOrder).map(::JsonPrimitive))

    fun setCategory(token:String?,projectionIds:JsonElement,projectionVersion:JsonElement?,category:JsonElement?,versionRepresentation:String?=null,requestWorkspace:String?=null):JsonObject {
        val ids=iterable(projectionIds).map {pythonString(it).trim {c ->c.code in pythonWhitespace}}.filter {it.isNotEmpty()}.distinct()
        if(ids.isEmpty()) throw CategoryFailure("category.projections_required")
        beforeWrite()
        return database.write {c ->
            val workspace=requestWorkspace ?: reads.session(c,token).workspaceId ?: throw AccessFailure(true)
            beforeWorkspaceLock()
            if (!c.lockWorkspace(database.sqlite, workspace)) throw AccessFailure(true)
            reads.lockedContext(c,token,workspace,write=true)
            val state=ProjectionStateRepository(c,workspace,database.sqlite).readyState() ?: throw CategoryFailure("projection.unavailable")
            check(projectionVersion!=null && projectionVersion!=JsonNull) {"projection version is not integer-compatible"}
            if(!categoryRevisionMatches(projectionVersion,null,state.projectionVersion,versionRepresentation)) throw CategoryFailure("projection.version_conflict")
            val sql = c.jooq(database.sqlite); val categories = CASH_CATEGORIES
            val target=if(category==null || category==JsonNull) null else sql.select(categories.ID, categories.CATEGORY_PATH).from(categories)
                .where(categories.WORKSPACE_ID.eq(workspace)).and(categories.ID.eq(pythonString(category))).fetchOne()
                ?.let { it.value1()!! to it.value2()!! } ?: throw CategoryFailure("category.not_found")
            val selected=select(c,workspace,state.datasetId,ids)
            val changed=linkedMapOf<String,Pair<Entry,JsonArray>>()
            val members=linkedMapOf<Long,Pair<JsonObject,JsonArray>>()
            for(row in selected) if(row.entry.category!=target?.first || !protected(row.entry.fields)) {
                changed[row.entry.id]=row.entry to protect(row.entry.fields)
                if(row.count==1) {
                    val member=row.members.values.single()
                    check(member.values is JsonObject) {"standalone values are not a mapping"}
                    members[member.id]=JsonObject(member.values+mapOf("category_id" to (target?.first?.let(::JsonPrimitive) ?: JsonNull))) to protect(member.fields)
                }
            }
            var version=state.projectionVersion
            if(changed.isNotEmpty() || selected.any {it.category!=target?.first || it.path!=target?.second}) {
                version=Math.addExact(version,1);val source=Math.addExact(state.sourceRevision,1)
                fun batch(queries: List<Query>) {
                    if (queries.isEmpty()) return
                    val batch = sql.batch(queries.first())
                    queries.forEach { batch.bind(*it.bindValues.toTypedArray()) }
                    checkBatch(batch.execute())
                }
                val entries = CASH_ENTRIES; val entryMembers = CASH_ENTRY_MEMBERS; val projections = CASH_PROJECTIONS; val states = CASH_PROJECTION_STATES
                batch(changed.values.map { (entry, fields) -> sql.update(entries).set(entries.CATEGORY_ID, target?.first).set(entries.MANUAL_FIELDS, fields)
                    .set(entries.REVISION, Math.addExact(entry.revision,1)).where(entries.WORKSPACE_ID.eq(workspace)).and(entries.ID.eq(entry.id)) })
                batch(members.map { (id, values) -> sql.update(entryMembers).set(entryMembers.STANDALONE_VALUES, values.first)
                    .set(entryMembers.STANDALONE_MANUAL_FIELDS, values.second).where(entryMembers.WORKSPACE_ID.eq(workspace)).and(entryMembers.TRANSACTION_ID.eq(id)) })
                val instant=now()
                batch(selected.map { row -> sql.update(projections).set(projections.CATEGORY_ID, target?.first).set(projections.CATEGORY_PATH, target?.second)
                    .set(projections.BUILT_PROJECTION_VERSION, version).set(projections.UPDATED_AT, instant).where(projections.WORKSPACE_ID.eq(workspace))
                    .and(projections.DATASET_ID.eq(state.datasetId)).and(projections.ID.eq(row.id)) })
                check(sql.update(states).set(states.PROJECTION_VERSION, version).set(states.SOURCE_REVISION, source).set(states.UPDATED_AT, instant)
                    .where(states.WORKSPACE_ID.eq(workspace)).execute()==1)
            }
            buildJsonObject {put("projection_version",version);put("projection_count",selected.size);put("updated_transaction_count",selected.sumOf {it.members.size.toLong()});put("category_id",target?.first?.let(::JsonPrimitive) ?: JsonNull)}
        }
    }

    private fun checkBatch(counts:IntArray) {check(counts.all {it==1 || it==java.sql.Statement.SUCCESS_NO_INFO}) {"classification write lost a selected row"}}

    private fun select(c:Connection,workspace:String,dataset:String,ids:List<String>):List<Selection> {
        val p = CASH_PROJECTIONS.`as`("p"); val root = CASH_TRANSACTIONS.`as`("root")
        val member = CASH_PROJECTION_MEMBERS.`as`("member"); val tx = CASH_TRANSACTIONS.`as`("tx")
        val validRoot = DSL.select(DSL.field(root.WORKSPACE_ID.eq(p.WORKSPACE_ID).and(root.DELETED_AT.isNull)))
            .from(root).where(root.ID.eq(p.ROOT_CASH_TRANSACTION_ID)).limit(1).asField<Boolean>()
        val deleted = DSL.select(DSL.field(tx.WORKSPACE_ID.eq(member.WORKSPACE_ID).and(tx.DELETED_AT.isNotNull)))
            .from(tx).where(tx.ID.eq(member.CASH_TRANSACTION_ID)).limit(1).asField<Boolean>()
        val deletedMember = DSL.select(DSL.inline(true)).from(member).where(member.WORKSPACE_ID.eq(p.WORKSPACE_ID))
            .and(member.DATASET_ID.eq(p.DATASET_ID)).and(member.PROJECTION_ROW_ID.eq(p.ID)).and(deleted.isTrue).limit(1).asField<Boolean>()
        val selection = DSL.select(p.ID, p.WORKSPACE_ID, p.DATASET_ID, p.PROJECTION_ID, p.ROOT_CASH_TRANSACTION_ID, p.MEMBER_COUNT, p.CATEGORY_ID, p.CATEGORY_PATH)
            .from(p).where(p.WORKSPACE_ID.eq(workspace)).and(p.DATASET_ID.eq(dataset)).and(p.PROJECTION_ID.`in`(ids))
            .and(p.VISIBLE.isTrue.or(p.ATTENTION_COUNT.gt(DSL.inline(0L)))).and(validRoot.isTrue).and(deletedMember.isNull).limit(ids.size)
        val name = DSL.name("selected")
        val selected = if (database.sqlite) name.`as`(selection) else name.asMaterialized(selection)
        val sId = selected.field(p.ID)!!; val sWorkspace = selected.field(p.WORKSPACE_ID)!!; val sDataset = selected.field(p.DATASET_ID)!!
        val pmTable = CASH_PROJECTION_MEMBERS; val mTable = CASH_ENTRY_MEMBERS; val eTable = CASH_ENTRIES
        val pm: Table<*> = if (database.sqlite) pmTable.`as`("pm") else DSL.lateral(DSL.selectFrom(pmTable)
            .where(pmTable.PROJECTION_ROW_ID.eq(sId)).and(pmTable.WORKSPACE_ID.eq(sWorkspace)).and(pmTable.DATASET_ID.eq(sDataset)).offset(0)).`as`("pm")
        val pmWorkspace = pm.field(pmTable.WORKSPACE_ID)!!; val pmTransaction = pm.field(pmTable.CASH_TRANSACTION_ID)!!
        val m: Table<*> = if (database.sqlite) mTable.`as`("m") else DSL.lateral(DSL.selectFrom(mTable)
            .where(mTable.WORKSPACE_ID.eq(pmWorkspace)).and(mTable.TRANSACTION_ID.eq(pmTransaction)).limit(1)).`as`("m")
        val mWorkspace = m.field(mTable.WORKSPACE_ID)!!; val mEntry = m.field(mTable.ENTRY_ID)!!
        val e: Table<*> = if (database.sqlite) eTable.`as`("e") else DSL.lateral(DSL.selectFrom(eTable)
            .where(eTable.ID.eq(mEntry)).and(eTable.WORKSPACE_ID.eq(mWorkspace)).limit(1)).`as`("e")
        val eId = e.field(eTable.ID)!!; val eWorkspace = e.field(eTable.WORKSPACE_ID)!!
        val joins = if (database.sqlite) selected.join(pm).on(pmWorkspace.eq(sWorkspace).and(pm.field(pmTable.DATASET_ID)!!.eq(sDataset))
            .and(pm.field(pmTable.PROJECTION_ROW_ID)!!.eq(sId))).join(m).on(mWorkspace.eq(pmWorkspace).and(m.field(mTable.TRANSACTION_ID)!!.eq(pmTransaction)))
            .join(e).on(eWorkspace.eq(mWorkspace).and(eId.eq(mEntry)))
        else selected.join(pm).on(DSL.trueCondition()).join(m).on(DSL.trueCondition()).join(e).on(DSL.trueCondition())
        val allMembers = CASH_ENTRY_MEMBERS.`as`("all_members")
        val count = DSL.select(DSL.count().cast(SQLDataType.BIGINT)).from(allMembers)
            .where(allMembers.WORKSPACE_ID.eq(eWorkspace)).and(allMembers.ENTRY_ID.eq(eId)).asField<Long>()
        val projectionId = selected.field(p.PROJECTION_ID)!!; val projectionRoot = selected.field(p.ROOT_CASH_TRANSACTION_ID)!!
        val memberCount = selected.field(p.MEMBER_COUNT)!!; val projectionCategory = selected.field(p.CATEGORY_ID)!!; val path = selected.field(p.CATEGORY_PATH)!!
        val entryRoot = e.field(eTable.ROOT_TRANSACTION_ID)!!; val revision = e.field(eTable.REVISION)!!
        val entryCategory = e.field(eTable.CATEGORY_ID)!!; val fields = e.field(eTable.MANUAL_FIELDS)!!
        val transaction = m.field(mTable.TRANSACTION_ID)!!; val standalone = m.field(mTable.STANDALONE_VALUES)!!; val standaloneFields = m.field(mTable.STANDALONE_MANUAL_FIELDS)!!
        val result=linkedMapOf<String,Selection>()
        c.jooq(database.sqlite).with(selected).select(sId, projectionId, projectionRoot, memberCount, projectionCategory, path,
            eId, entryRoot, revision, entryCategory, fields, transaction, standalone, standaloneFields, count).from(joins).fetch().forEach { r ->
                val entry=Entry(r[eId]!!,r[entryRoot]!!,r[revision]!!,r[entryCategory],r[fields]!!)
                val key=r[projectionId]!!
                val row=result.getOrPut(key) {Selection(r[sId]!!,key,r[projectionRoot]!!,r[memberCount]!!.toInt(),r[projectionCategory],r[path],entry,linkedMapOf())}
                if(r[count]!=row.count.toLong() || entry.root!=row.root || row.entry.id!=entry.id) throw CategoryFailure("projection.version_conflict")
                row.members[r[transaction]!!]=Member(r[transaction]!!,r[standalone]!!,r[standaloneFields]!!)
        }
        if(result.keys!=ids.toSet() || result.values.any {it.members.size!=it.count}) throw CategoryFailure("projection.version_conflict")
        return result.values.toList()
    }
}
