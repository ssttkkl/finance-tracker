package com.finance.tracker.backend

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import kotlinx.serialization.json.*

internal val commonCashFields=setOf("category_id","note","counterparty","counterparty_account","counterparty_account_attrs")
internal data class CashEntry(val id:String,var rootTransactionId:Long,var revision:Long,var values:JsonObject,
    var manualFields:List<String>,var splitInherit:Boolean?)
internal data class CashEntryMember(val transactionId:Long,var entryId:String,var standaloneValues:JsonObject,var standaloneManualFields:List<String>)
internal data class CashEntrySnapshot(val transactionId:Long,val entryId:String,val revision:Long,val digest:String)
internal data class CashSplitFieldDiff(val field:String,val current:JsonElement,val restored:JsonElement,val memberIds:List<Long>)

internal class CashEntryRepository(private val connection:Connection,private val workspace:String,private val sqlite:Boolean) {
    var autoFlush=true
    private val entries=linkedMapOf<String,CashEntry>()
    private val members=linkedMapOf<Long,CashEntryMember>()
    private val savedEntries=mutableMapOf<String,CashEntry>()
    private val savedMembers=mutableMapOf<Long,CashEntryMember>()
    private val expiredMembers=mutableSetOf<Long>()
    private val knownEntries=linkedMapOf<String,CashEntry>()
    private val knownMembers=linkedMapOf<Long,CashEntryMember>()
    private val jsonSlot=if(sqlite) "?" else "CAST(? AS json)"
    private val entryColumns="id,root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit"
    init {(connection as? CashSqlTransactionBoundary)?.let {it.cashBeforeSavepoint(::flush);it.cashOnSavepointRollback(::reloadAfterSavepointRollback)}}
    private fun textList(value:String)=Json.parseToJsonElement(value).jsonArray.map {check(it is JsonPrimitive && it.isString);it.content}
    private fun sorted(values:Collection<String>)=values.distinct().sortedWith(cashRelationStringOrder)
    private fun entry(row:ResultSet,start:Int):CashEntry? {
        val id=row.getString(start) ?: return null
        return entries.getOrPut(id) {
            val split=row.getBoolean(start+9).let {if(row.wasNull()) null else it}
            CashEntry(id,row.getLong(start+1),row.getLong(start+2),buildJsonObject {
                put("category_id",row.getString(start+3)?.let(::JsonPrimitive) ?: JsonNull);put("note",row.getString(start+4));put("counterparty",row.getString(start+5))
                put("counterparty_account",row.getString(start+6));put("counterparty_account_attrs",Json.parseToJsonElement(row.getString(start+7)))
            },textList(row.getString(start+8)),split).also {savedEntries[id]=it.copy();knownEntries[id]=it}
        }
    }
    private fun member(row:ResultSet):CashEntryMember {
        val id=row.getLong(1)
        fun loaded()=CashEntryMember(id,row.getString(2),Json.parseToJsonElement(row.getString(3)).jsonObject,textList(row.getString(4)))
        val current=members.getOrPut(id) {loaded().also {savedMembers[id]=it.copy();knownMembers[id]=it}}
        if(expiredMembers.remove(id)) {
            val fresh=loaded();current.entryId=fresh.entryId;current.standaloneValues=fresh.standaloneValues;current.standaloneManualFields=fresh.standaloneManualFields;savedMembers[id]=current.copy()
        }
        return current
    }
    private fun reloadAfterSavepointRollback() {
        if(knownEntries.isEmpty() && knownMembers.isEmpty()) return
        entries.clear();members.clear();savedEntries.clear();savedMembers.clear();expiredMembers.clear()
        for(batch in knownEntries.keys.toList().chunked(400)) connection.prepareStatement("SELECT $entryColumns FROM cash_entries WHERE workspace_id=? AND id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setString(index+2,id)};it.executeQuery().use {r ->while(r.next()) {
                val id=r.getString(1);val original=knownEntries.getValue(id);val fresh=entry(r,1)!!
                original.rootTransactionId=fresh.rootTransactionId;original.revision=fresh.revision;original.values=fresh.values;original.manualFields=fresh.manualFields;original.splitInherit=fresh.splitInherit
                entries[id]=original;savedEntries[id]=original.copy();knownEntries[id]=original
            }}
        }
        for(batch in knownMembers.keys.toList().chunked(400)) connection.prepareStatement("SELECT transaction_id,entry_id,standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=? AND transaction_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};it.executeQuery().use {r ->while(r.next()) {
                val id=r.getLong(1);val original=knownMembers.getValue(id);val fresh=member(r)
                original.entryId=fresh.entryId;original.standaloneValues=fresh.standaloneValues;original.standaloneManualFields=fresh.standaloneManualFields
                members[id]=original;savedMembers[id]=original.copy();knownMembers[id]=original
            }}
        }
    }
    fun expireMember(id:Long) {
        val current=members.getValue(id);val saved=savedMembers.getValue(id)
        current.entryId=saved.entryId;current.standaloneValues=saved.standaloneValues;current.standaloneManualFields=saved.standaloneManualFields
        expiredMembers+=id
    }
    fun forTransactions(identifiers:Collection<Long>):Pair<Map<Long,CashEntryMember>,Map<String,CashEntry>> {
        val ids=identifiers.distinct();if(ids.isEmpty()) return emptyMap<Long,CashEntryMember>() to emptyMap()
        if(autoFlush) flush()
        val found=linkedMapOf<Long,CashEntryMember>();val objects=linkedMapOf<String,CashEntry>()
        if(ids.size<=400 && autoFlush) {
            val columns=entryColumns.split(',').joinToString(",") {"e.$it"}
            var matches=true
            connection.prepareStatement("SELECT m.transaction_id,m.entry_id,m.standalone_values,m.standalone_manual_fields,$columns FROM cash_entry_members m LEFT JOIN cash_entries e ON e.workspace_id=? AND e.id=m.entry_id WHERE m.workspace_id=? AND m.transaction_id IN (${ids.joinToString(",") {"?"}})").use {
                it.setString(1,workspace);it.setString(2,workspace);ids.forEachIndexed {index,id ->it.setLong(index+3,id)}
                it.executeQuery().use {r ->while(r.next()) {
                    val m=member(r);found[m.transactionId]=m;val e=entry(r,5)
                    if(e==null || m.entryId!=e.id) matches=false
                    if(e!=null) objects[e.id]=e
                }}
            }
            if(matches) return found to objects
        } else for(batch in ids.chunked(400)) connection.prepareStatement("SELECT transaction_id,entry_id,standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=? AND transaction_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};it.executeQuery().use {r ->while(r.next()) {val m=member(r);found[m.transactionId]=m}}
        }
        objects.clear()
        for(batch in found.values.map {it.entryId}.distinct().chunked(400)) connection.prepareStatement("SELECT $entryColumns FROM cash_entries WHERE workspace_id=? AND id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setString(index+2,id)};it.executeQuery().use {r ->while(r.next()) {val e=entry(r,1)!!;objects[e.id]=e}}
        }
        return found to objects
    }
    fun current(id:Long):CashEntry? {val (ms,es)=forTransactions(listOf(id));return ms[id]?.let {es[it.entryId]}}
    fun snapshot(ids:Collection<Long>):List<CashEntrySnapshot> {
        val (ms,es)=forTransactions(ids)
        fun canonical(value:JsonElement):JsonElement=when(value) {
            is JsonObject ->JsonObject(value.entries.sortedWith {a,b ->cashRelationStringOrder.compare(a.key,b.key)}.associate {it.key to canonical(it.value)})
            is JsonArray ->JsonArray(value.map(::canonical))
            else ->value
        }
        return ms.values.sortedBy {it.transactionId}.map {m ->val e=es.getValue(m.entryId)
            val payload=JsonArray(listOf(e.values,JsonArray(e.manualFields.map(::JsonPrimitive)),m.standaloneValues,JsonArray(m.standaloneManualFields.map(::JsonPrimitive))))
            CashEntrySnapshot(m.transactionId,m.entryId,e.revision,tokenDigest(canonical(payload).toString()))
        }
    }
    fun flush() {
        for(e in entries.values) if(e!=savedEntries[e.id]) {
            connection.prepareStatement("UPDATE cash_entries SET root_transaction_id=?,revision=?,category_id=?,note=?,counterparty=?,counterparty_account=?,counterparty_account_attrs=$jsonSlot,manual_fields=$jsonSlot,split_inherit=? WHERE workspace_id=? AND id=?").use {
                it.setLong(1,e.rootTransactionId);it.setLong(2,e.revision);it.setString(3,e.values["category_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content)
                it.setString(4,e.values.getValue("note").jsonPrimitive.content);it.setString(5,e.values.getValue("counterparty").jsonPrimitive.content);it.setString(6,e.values.getValue("counterparty_account").jsonPrimitive.content)
                it.setString(7,e.values.getValue("counterparty_account_attrs").toString());it.setString(8,JsonArray(e.manualFields.map(::JsonPrimitive)).toString());it.setObject(9,e.splitInherit);it.setString(10,workspace);it.setString(11,e.id);check(it.executeUpdate()==1)
            };savedEntries[e.id]=e.copy()
        }
        for(m in members.values) if(m.transactionId !in expiredMembers && m!=savedMembers[m.transactionId]) {
            connection.prepareStatement("UPDATE cash_entry_members SET entry_id=?,standalone_values=$jsonSlot,standalone_manual_fields=$jsonSlot WHERE workspace_id=? AND transaction_id=?").use {
                it.setString(1,m.entryId);it.setString(2,m.standaloneValues.toString());it.setString(3,JsonArray(m.standaloneManualFields.map(::JsonPrimitive)).toString());it.setString(4,workspace);it.setLong(5,m.transactionId);check(it.executeUpdate()==1)
            };savedMembers[m.transactionId]=m.copy()
        }
    }

    private data class Source(val values:JsonObject,val manual:Set<String>)
    private fun sources(ids:Collection<Long>):Map<Long,Source> {
        val result=linkedMapOf<Long,Source>()
        for(batch in ids.chunked(400)) connection.prepareStatement("SELECT id,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_overrides FROM cash_transactions WHERE workspace_id=? AND id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};it.executeQuery().use {r ->while(r.next()) {
                val values=buildJsonObject {put("category_id",r.getString(2)?.let(::JsonPrimitive) ?: JsonNull);put("note",r.getString(3));put("counterparty",r.getString(4));put("counterparty_account",r.getString(5));put("counterparty_account_attrs",Json.parseToJsonElement(r.getString(6)))}
                val overrides=Json.parseToJsonElement(r.getString(7) ?: "null");val keys=if(overrides==JsonNull) emptySet() else overrides.jsonObject.keys
                result[r.getLong(1)]=Source(values,keys intersect commonCashFields)
            }}
        }
        return result
    }
    private fun insert(e:CashEntry) {
        connection.prepareStatement("INSERT INTO cash_entries(id,workspace_id,root_transaction_id,revision,category_id,note,counterparty,counterparty_account,counterparty_account_attrs,manual_fields,split_inherit) VALUES(?,?,?,1,?,?,?,?,$jsonSlot,$jsonSlot,NULL)").use {
            it.setString(1,e.id);it.setString(2,workspace);it.setLong(3,e.rootTransactionId);it.setString(4,e.values["category_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content);it.setString(5,e.values.getValue("note").jsonPrimitive.content)
            it.setString(6,e.values.getValue("counterparty").jsonPrimitive.content);it.setString(7,e.values.getValue("counterparty_account").jsonPrimitive.content);it.setString(8,e.values.getValue("counterparty_account_attrs").toString());it.setString(9,JsonArray(e.manualFields.map(::JsonPrimitive)).toString());check(it.executeUpdate()==1)
        };entries[e.id]=e;savedEntries[e.id]=e.copy();knownEntries[e.id]=e
    }
    private fun normalized(patch:Map<*,*>):JsonObject=JsonObject(patch.entries.associate {(key,value) ->
        val field=key as String
        field to when(val result=CashRecordPolicy.normalize(field,value)) {
            null ->JsonNull
            is String ->JsonPrimitive(result)
            is List<*> ->JsonArray(result.map {JsonPrimitive(it as String)})
            else ->error("unexpected common value")
        }
    })
    fun patch(identifier:Long,patch:Any?,expectedRevision:Long?):CashEntry {
        val entry=current(identifier)
        if(entry==null || entry.revision!=expectedRevision) throw CashPolicyError("entry_changed",emptyList())
        if(patch !is Map<*,*> || patch.keys.any {it !in commonCashFields}) throw CashPolicyError("field_not_editable",emptyList())
        val values=normalized(patch)
        val changed=values.any {(field,value) ->entry.values[field]!=value || field !in entry.manualFields}
        if(changed) {
            entry.values=JsonObject(entry.values+values);entry.manualFields=sorted(entry.manualFields+values.keys);entry.revision=Math.addExact(entry.revision,1)
            val group=mutableListOf<CashEntryMember>()
            connection.prepareStatement("SELECT transaction_id,entry_id,standalone_values,standalone_manual_fields FROM cash_entry_members WHERE workspace_id=? AND entry_id=?").use {
                it.setString(1,workspace);it.setString(2,entry.id);it.executeQuery().use {r ->while(r.next()) group+=member(r)}
            }
            if(group.size==1) group.single().let {m ->m.standaloneValues=JsonObject(m.standaloneValues+values);m.standaloneManualFields=sorted(m.standaloneManualFields+values.keys)}
            flush()
        }
        return entry
    }
    fun prepareSplit(ids:Collection<Long>,inherit:Any?) {
        val es=forTransactions(ids).second
        for(e in es.values) {
            if(e.manualFields.isNotEmpty() && inherit !is Boolean) throw CashPolicyError("inheritance_required",emptyList())
            e.splitInherit=if(inherit is Boolean) inherit else false
        };flush()
    }
    fun splitFieldDiffs(projections:List<CashProjection>):List<CashSplitFieldDiff> {
        val ids=projections.flatMap {it.memberIds}.toSet();val (ms,es)=forTransactions(ids);val source=sources(ids)
        val old=ms.values.groupBy {it.entryId}.mapValues {(_,rows) ->rows.map {it.transactionId}.toSet()};val result=mutableListOf<CashSplitFieldDiff>()
        for(p in projections) {
            val group=p.memberIds.toSet();val root=p.primaryRecord.id;val restored=source.getValue(root).values.toMutableMap()
            ms[root]?.let {m ->m.standaloneManualFields.forEach {restored[it]=m.standaloneValues.getValue(it)}}
            for(id in group.mapNotNull {ms[it]?.entryId}.toSet()) {
                if(old.getValue(id).all {it in group}) continue
                val e=es.getValue(id)
                for(field in sorted(e.manualFields)) if(e.values.getValue(field)!=restored.getValue(field)) result+=CashSplitFieldDiff(field,e.values.getValue(field),restored.getValue(field),group.sorted())
            }
        };return result
    }
    private fun projectionForSource(id:Long):CashProjection {
        val fact=connection.prepareStatement("SELECT id,account_id,occurred_at,amount,currency,counterparty,category_id,note,source_type,record_id,cash_granularity,state,created_at FROM cash_transactions WHERE workspace_id=? AND id=?").use {
            it.setString(1,workspace);it.setLong(2,id);it.executeQuery().use {r ->
                if(!r.next()) throw CashPolicyError("entry_changed",emptyList())
                fun instant(value:String?)=value?.let {raw ->val text=raw.replace(' ','T');if(sqlite) java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC) else java.time.OffsetDateTime.parse(text).toInstant()}
                val account=r.getLong(2).let {if(r.wasNull()) null else it}
                CashProjectionFact.create(r.getLong(1),account,instant(r.getString(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),cashGranularity=r.getString(11),state=r.getString(12),createdAt=instant(r.getString(13)))
            }
        };return buildCashProjections(listOf(fact),emptyList()).projections.single()
    }
    fun applyEdit(id:Long,patch:Any?,revision:Long?):CashEntry {
        val entry=current(id)
        val expected=if(entry==null) {
            if(revision!=null) throw CashPolicyError("entry_changed",emptyList())
            reconcile(listOf(projectionForSource(id)));current(id)!!.revision
        } else revision
        return patch(id,patch,expected)
    }
    fun chooseCommon(ids:Collection<Long>,patch:Map<String,Any?>) {
        val values=normalized(patch.filterKeys {it in commonCashFields})
        val category=values["category_id"]?.takeUnless {it==JsonNull}?.jsonPrimitive?.content
        if(category!=null) connection.prepareStatement("SELECT id FROM cash_categories WHERE workspace_id=? AND id=?").use {
            it.setString(1,workspace);it.setString(2,category);it.executeQuery().use {r ->if(!r.next()) throw CashPolicyError("category_unavailable",listOf("category_id"))}
        }
        if(values.isEmpty()) return
        for(id in ids.toSet()) {
            var entry=current(id)
            if(entry==null) {reconcile(listOf(projectionForSource(id)));entry=current(id)!!}
            entry.values=JsonObject(entry.values+values);entry.manualFields=sorted(entry.manualFields+values.keys);entry.revision=Math.addExact(entry.revision,1)
        };flush()
    }
    fun removeMembers(ids:Collection<Long>) {
        val (ms,es)=forTransactions(ids)
        for(batch in ms.keys.chunked(400)) connection.prepareStatement("DELETE FROM cash_entry_members WHERE workspace_id=? AND transaction_id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {index,id ->it.setLong(index+2,id)};check(it.executeUpdate()==batch.size)
        }
        ms.keys.forEach {members.remove(it);savedMembers.remove(it);expiredMembers.remove(it)}
        es.values.forEach {it.revision=Math.addExact(it.revision,1)};flush()
        for(id in es.keys) connection.prepareStatement("DELETE FROM cash_entries WHERE workspace_id=? AND id=? AND NOT EXISTS(SELECT 1 FROM cash_entry_members WHERE workspace_id=? AND entry_id=?)").use {
            it.setString(1,workspace);it.setString(2,id);it.setString(3,workspace);it.setString(4,id);if(it.executeUpdate()==1) {entries.remove(id);savedEntries.remove(id)}
        }
    }
    fun reconcile(projections:List<CashProjection>):Map<String,CashEntry> {
        val ids=projections.flatMap {it.memberIds}.toSet();val (ms,es)=forTransactions(ids);val source=sources(ids)
        val oldMembers=ms.values.groupBy {it.entryId}.mapValues {(_,values) ->values.map {it.transactionId}.toSet()}
        val oldRoots=es.values.map {it.rootTransactionId}.toSet();val used=mutableSetOf<String>();val result=linkedMapOf<String,CashEntry>()
        val ordered=projections.sortedWith(compareBy<CashProjection> {it.primaryRecord.id !in oldRoots}.thenComparator {a,b ->cashRelationStringOrder.compare(a.projectionId,b.projectionId)})
        val fresh=mutableListOf<CashEntry>();val freshMembers=mutableListOf<CashEntryMember>()
        for(p in ordered) {
            val group=p.memberIds.toSet();val root=p.primaryRecord.id;val oldIds=group.mapNotNull {ms[it]?.entryId}.toSet()
            var candidate=ms[root]?.let {es[it.entryId]}
            if(candidate!=null && (candidate.id in used || candidate.rootTransactionId !in group && candidate.rootTransactionId in ids)) candidate=null
            val human=linkedMapOf<String,JsonElement>()
            for(id in oldIds) {
                val old=es.getValue(id);val split=oldMembers.getValue(id).any {it !in group}
                if(split && old.splitInherit==false) {
                    for(memberId in group intersect oldMembers.getValue(id)) if(memberId==root) {val m=ms.getValue(memberId);m.standaloneManualFields.forEach {human[it]=m.standaloneValues.getValue(it)}}
                    continue
                }
                for(field in old.manualFields) {val value=old.values.getValue(field);if(field in human && human[field]!=value) throw CashPolicyError("common_field_conflict",listOf(field));human[field]=value}
            }
            if(candidate==null) {candidate=CashEntry(UUID.randomUUID().toString(),root,1,source.getValue(root).values,emptyList(),null);fresh+=candidate}
            else if(candidate.rootTransactionId!=root || oldMembers[candidate.id]!=group) {candidate.revision=Math.addExact(candidate.revision,1);candidate.rootTransactionId=root}
            for(memberId in group) {
                val s=source.getValue(memberId);var m=ms[memberId]
                if(m==null) {
                    m=CashEntryMember(memberId,candidate.id,s.values,sorted(s.manual));members[memberId]=m;knownMembers[memberId]=m;freshMembers+=m
                    if(memberId==root) m.standaloneManualFields.filter {it !in human}.forEach {human[it]=m.standaloneValues.getValue(it)}
                } else {
                    m.standaloneValues=JsonObject(m.standaloneValues+s.values.filterKeys {it !in m.standaloneManualFields});m.entryId=candidate.id
                }
                if(oldIds.any {es.getValue(it).splitInherit==true && oldMembers.getValue(it).any {id ->id !in group}}) {
                    m.standaloneValues=JsonObject(m.standaloneValues+human);m.standaloneManualFields=sorted(m.standaloneManualFields+human.keys)
                }
            }
            val values=JsonObject(source.getValue(root).values+human)
            if(candidate.id in es && candidate.values!=values) candidate.revision=Math.addExact(candidate.revision,1)
            candidate.values=values;candidate.manualFields=sorted(human.keys);used+=candidate.id;result[p.projectionId]=candidate
        }
        fresh.forEach(::insert)
        for(m in freshMembers) {
            connection.prepareStatement("INSERT INTO cash_entry_members(workspace_id,transaction_id,entry_id,standalone_values,standalone_manual_fields) VALUES(?,?,?,$jsonSlot,$jsonSlot)").use {
                it.setString(1,workspace);it.setLong(2,m.transactionId);it.setString(3,m.entryId);it.setString(4,m.standaloneValues.toString());it.setString(5,JsonArray(m.standaloneManualFields.map(::JsonPrimitive)).toString());check(it.executeUpdate()==1)
            };savedMembers[m.transactionId]=m.copy()
        }
        flush();es.values.forEach {it.splitInherit=null};flush()
        for(id in es.keys-used) connection.prepareStatement("DELETE FROM cash_entries WHERE workspace_id=? AND id=? AND NOT EXISTS(SELECT 1 FROM cash_entry_members WHERE workspace_id=? AND entry_id=?)").use {
            it.setString(1,workspace);it.setString(2,id);it.setString(3,workspace);it.setString(4,id);if(it.executeUpdate()==1) {entries.remove(id);savedEntries.remove(id)}
        }
        return result
    }
}
