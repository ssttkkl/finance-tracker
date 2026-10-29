package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.*

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
            val lock=if(database.sqlite) "" else " FOR UPDATE"
            c.prepareStatement("SELECT id FROM workspaces WHERE id=?$lock").use {it.setString(1,workspace);it.executeQuery().use {r ->if(!r.next()) throw AccessFailure(true)}}
            reads.lockedContext(c,token,workspace,write=true)
            val state=ProjectionStateRepository(c,workspace,database.sqlite).readyState() ?: throw CategoryFailure("projection.unavailable")
            check(projectionVersion!=null && projectionVersion!=JsonNull) {"projection version is not integer-compatible"}
            if(!categoryRevisionMatches(projectionVersion,null,state.projectionVersion,versionRepresentation)) throw CategoryFailure("projection.version_conflict")
            val target=if(category==null || category==JsonNull) null else c.prepareStatement("SELECT id,category_path FROM cash_categories WHERE workspace_id=? AND id=?").use {
                it.setString(1,workspace);it.setString(2,pythonString(category));it.executeQuery().use {r ->if(!r.next()) throw CategoryFailure("category.not_found");r.getString(1) to r.getString(2)}
            }
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
                val json=if(database.sqlite) "?" else "CAST(? AS json)"
                if(changed.isNotEmpty()) c.prepareStatement("UPDATE cash_entries SET category_id=?,manual_fields=$json,revision=? WHERE workspace_id=? AND id=?").use {statement ->
                    for((entry,fields) in changed.values) {statement.setString(1,target?.first);statement.setString(2,fields.toString());statement.setLong(3,Math.addExact(entry.revision,1));statement.setString(4,workspace);statement.setString(5,entry.id);statement.addBatch()};checkBatch(statement.executeBatch())
                }
                if(members.isNotEmpty()) c.prepareStatement("UPDATE cash_entry_members SET standalone_values=$json,standalone_manual_fields=$json WHERE workspace_id=? AND transaction_id=?").use {statement ->
                    for((id,values) in members) {statement.setString(1,values.first.toString());statement.setString(2,values.second.toString());statement.setString(3,workspace);statement.setLong(4,id);statement.addBatch()};checkBatch(statement.executeBatch())
                }
                val instant=now()
                c.prepareStatement("UPDATE cash_projections SET category_id=?,category_path=?,built_projection_version=?,updated_at=? WHERE workspace_id=? AND dataset_id=? AND id=?").use {statement ->
                    for(row in selected) {statement.setString(1,target?.first);statement.setString(2,target?.second);statement.setLong(3,version);statement.instant(4,instant,database.sqlite);statement.setString(5,workspace);statement.setString(6,state.datasetId);statement.setLong(7,row.id);statement.addBatch()};checkBatch(statement.executeBatch())
                }
                c.prepareStatement("UPDATE cash_projection_states SET projection_version=?,source_revision=?,updated_at=? WHERE workspace_id=?").use {it.setLong(1,version);it.setLong(2,source);it.instant(3,instant,database.sqlite);it.setString(4,workspace);check(it.executeUpdate()==1)}
            }
            buildJsonObject {put("projection_version",version);put("projection_count",selected.size);put("updated_transaction_count",selected.sumOf {it.members.size.toLong()});put("category_id",target?.first?.let(::JsonPrimitive) ?: JsonNull)}
        }
    }

    private fun checkBatch(counts:IntArray) {check(counts.all {it==1 || it==java.sql.Statement.SUCCESS_NO_INFO}) {"classification write lost a selected row"}}

    private fun select(c:Connection,workspace:String,dataset:String,ids:List<String>):List<Selection> {
        val marks=ids.joinToString(",") {"?"}
        val materialized=if(database.sqlite) "" else "MATERIALIZED "
        val joins=if(database.sqlite) """FROM selected s JOIN cash_projection_members pm ON pm.workspace_id=s.workspace_id AND pm.dataset_id=s.dataset_id AND pm.projection_row_id=s.id
            JOIN cash_entry_members m ON m.workspace_id=pm.workspace_id AND m.transaction_id=pm.cash_transaction_id
            JOIN cash_entries e ON e.workspace_id=m.workspace_id AND e.id=m.entry_id""" else """FROM selected s
            JOIN LATERAL (SELECT * FROM cash_projection_members WHERE projection_row_id=s.id AND workspace_id=s.workspace_id AND dataset_id=s.dataset_id OFFSET 0) pm ON true
            JOIN LATERAL (SELECT * FROM cash_entry_members WHERE workspace_id=pm.workspace_id AND transaction_id=pm.cash_transaction_id LIMIT 1) m ON true
            JOIN LATERAL (SELECT * FROM cash_entries WHERE id=m.entry_id AND workspace_id=m.workspace_id LIMIT 1) e ON true"""
        val sql="""WITH selected AS ${materialized}(SELECT p.* FROM cash_projections p WHERE p.workspace_id=? AND p.dataset_id=? AND p.projection_id IN ($marks)
            AND (p.visible=true OR p.attention_count>0)
            AND (SELECT root.workspace_id=p.workspace_id AND root.deleted_at IS NULL FROM cash_transactions root WHERE root.id=p.root_cash_transaction_id LIMIT 1)
            AND (SELECT true FROM cash_projection_members member WHERE member.workspace_id=p.workspace_id AND member.dataset_id=p.dataset_id AND member.projection_row_id=p.id
                AND (SELECT tx.workspace_id=member.workspace_id AND tx.deleted_at IS NOT NULL FROM cash_transactions tx WHERE tx.id=member.cash_transaction_id LIMIT 1) LIMIT 1) IS NULL LIMIT ?)
            SELECT s.id,s.projection_id,s.root_cash_transaction_id,s.member_count,s.category_id,s.category_path,
            e.id,e.root_transaction_id,e.revision,e.category_id,e.manual_fields,m.transaction_id,m.standalone_values,m.standalone_manual_fields,
            (SELECT count(*) FROM cash_entry_members all_members WHERE all_members.workspace_id=e.workspace_id AND all_members.entry_id=e.id)
            $joins"""
        val result=linkedMapOf<String,Selection>()
        c.prepareStatement(sql).use {statement ->
            var index=1;statement.setString(index++,workspace);statement.setString(index++,dataset);ids.forEach {statement.setString(index++,it)}
            statement.setInt(index,ids.size)
            statement.executeQuery().use {r ->while(r.next()) {
                val entry=Entry(r.getString(7),r.getLong(8),r.getLong(9),r.getString(10),Json.parseToJsonElement(r.getString(11)))
                val key=r.getString(2)
                val row=result.getOrPut(key) {Selection(r.getLong(1),key,r.getLong(3),r.getInt(4),r.getString(5),r.getString(6),entry,linkedMapOf())}
                if(r.getLong(15)!=row.count.toLong() || entry.root!=row.root || row.entry.id!=entry.id) throw CategoryFailure("projection.version_conflict")
                row.members[r.getLong(12)]=Member(r.getLong(12),Json.parseToJsonElement(r.getString(13)),Json.parseToJsonElement(r.getString(14)))
            }}
        }
        if(result.keys!=ids.toSet() || result.values.any {it.members.size!=it.count}) throw CategoryFailure("projection.version_conflict")
        return result.values.toList()
    }
}
