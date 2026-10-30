package com.finance.tracker.backend

import java.sql.Connection
import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Record
import org.jooq.impl.DSL
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
    private val sql = connection.jooq(sqlite)
    private val e = CASH_ENTRIES.`as`("e")
    private val m = CASH_ENTRY_MEMBERS.`as`("m")
    private val entryFields = listOf(e.ID, e.ROOT_TRANSACTION_ID, e.REVISION, e.CATEGORY_ID, e.NOTE, e.COUNTERPARTY,
        e.COUNTERPARTY_ACCOUNT, e.COUNTERPARTY_ACCOUNT_ATTRS, e.MANUAL_FIELDS, e.SPLIT_INHERIT)
    private val memberFields = listOf(m.TRANSACTION_ID, m.ENTRY_ID, m.STANDALONE_VALUES, m.STANDALONE_MANUAL_FIELDS)
    init {(connection as? CashSqlTransactionBoundary)?.let {it.cashBeforeSavepoint(::flush);it.cashOnSavepointRollback(::reloadAfterSavepointRollback)}}
    private fun textList(value: JsonElement) = value.jsonArray.map { check(it is JsonPrimitive && it.isString); it.content }
    private fun sorted(values:Collection<String>)=values.distinct().sortedWith(cashRelationStringOrder)
    private fun entry(row: Record): CashEntry? {
        val id = row[e.ID] ?: return null
        return entries.getOrPut(id) {
            CashEntry(id, row[e.ROOT_TRANSACTION_ID]!!, row[e.REVISION]!!, buildJsonObject {
                put("category_id", row[e.CATEGORY_ID]?.let(::JsonPrimitive) ?: JsonNull); put("note", row[e.NOTE]!!); put("counterparty", row[e.COUNTERPARTY]!!)
                put("counterparty_account", row[e.COUNTERPARTY_ACCOUNT]!!); put("counterparty_account_attrs", row[e.COUNTERPARTY_ACCOUNT_ATTRS]!!)
            }, textList(row[e.MANUAL_FIELDS]!!), row[e.SPLIT_INHERIT]).also { savedEntries[id] = it.copy(); knownEntries[id] = it }
        }
    }
    private fun member(row: Record): CashEntryMember {
        val id = row[m.TRANSACTION_ID]!!
        fun loaded() = CashEntryMember(id, row[m.ENTRY_ID]!!, row[m.STANDALONE_VALUES]!!.jsonObject, textList(row[m.STANDALONE_MANUAL_FIELDS]!!))
        val current = members.getOrPut(id) { loaded().also { savedMembers[id] = it.copy(); knownMembers[id] = it } }
        if (expiredMembers.remove(id)) {
            val fresh = loaded(); current.entryId = fresh.entryId; current.standaloneValues = fresh.standaloneValues
            current.standaloneManualFields = fresh.standaloneManualFields; savedMembers[id] = current.copy()
        }
        return current
    }
    private fun reloadAfterSavepointRollback() {
        if (knownEntries.isEmpty() && knownMembers.isEmpty()) return
        entries.clear(); members.clear(); savedEntries.clear(); savedMembers.clear(); expiredMembers.clear()
        for (batch in knownEntries.keys.toList().chunked(400)) {
            sql.select(entryFields).from(e).where(e.WORKSPACE_ID.eq(workspace)).and(e.ID.`in`(batch)).fetch().forEach { row ->
                val id = row[e.ID]!!; val original = knownEntries.getValue(id); val fresh = entry(row)!!
                original.rootTransactionId = fresh.rootTransactionId; original.revision = fresh.revision; original.values = fresh.values
                original.manualFields = fresh.manualFields; original.splitInherit = fresh.splitInherit
                entries[id] = original; savedEntries[id] = original.copy(); knownEntries[id] = original
            }
        }
        for (batch in knownMembers.keys.toList().chunked(400)) {
            sql.select(memberFields).from(m).where(m.WORKSPACE_ID.eq(workspace)).and(m.TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                val id = row[m.TRANSACTION_ID]!!; val original = knownMembers.getValue(id); val fresh = member(row)
                original.entryId = fresh.entryId; original.standaloneValues = fresh.standaloneValues; original.standaloneManualFields = fresh.standaloneManualFields
                members[id] = original; savedMembers[id] = original.copy(); knownMembers[id] = original
            }
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
        if (ids.size <= 400 && autoFlush) {
            var matches = true
            sql.select(memberFields + entryFields).from(m).leftJoin(e).on(e.WORKSPACE_ID.eq(workspace)).and(e.ID.eq(m.ENTRY_ID))
                .where(m.WORKSPACE_ID.eq(workspace)).and(m.TRANSACTION_ID.`in`(ids)).fetch().forEach { row ->
                    val member = member(row); found[member.transactionId] = member; val entry = entry(row)
                    if (entry == null || member.entryId != entry.id) matches = false
                    if (entry != null) objects[entry.id] = entry
                }
            if (matches) return found to objects
        } else for (batch in ids.chunked(400)) {
            sql.select(memberFields).from(m).where(m.WORKSPACE_ID.eq(workspace)).and(m.TRANSACTION_ID.`in`(batch)).fetch().forEach { row ->
                val member = member(row); found[member.transactionId] = member
            }
        }
        objects.clear()
        for (batch in found.values.map { it.entryId }.distinct().chunked(400)) {
            sql.select(entryFields).from(e).where(e.WORKSPACE_ID.eq(workspace)).and(e.ID.`in`(batch)).fetch().forEach { row ->
                val entry = entry(row)!!; objects[entry.id] = entry
            }
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
        val table = CASH_ENTRIES
        for (entry in entries.values) if (entry != savedEntries[entry.id]) {
            check(sql.update(table).set(table.ROOT_TRANSACTION_ID, entry.rootTransactionId).set(table.REVISION, entry.revision)
                .set(table.CATEGORY_ID, entry.values["category_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
                .set(table.NOTE, entry.values.getValue("note").jsonPrimitive.content).set(table.COUNTERPARTY, entry.values.getValue("counterparty").jsonPrimitive.content)
                .set(table.COUNTERPARTY_ACCOUNT, entry.values.getValue("counterparty_account").jsonPrimitive.content)
                .set(table.COUNTERPARTY_ACCOUNT_ATTRS, entry.values.getValue("counterparty_account_attrs"))
                .set(table.MANUAL_FIELDS, JsonArray(entry.manualFields.map(::JsonPrimitive))).set(table.SPLIT_INHERIT, entry.splitInherit)
                .where(table.WORKSPACE_ID.eq(workspace)).and(table.ID.eq(entry.id)).execute() == 1)
            savedEntries[entry.id] = entry.copy()
        }
        val member = CASH_ENTRY_MEMBERS
        for (value in members.values) if (value.transactionId !in expiredMembers && value != savedMembers[value.transactionId]) {
            check(sql.update(member).set(member.ENTRY_ID, value.entryId).set(member.STANDALONE_VALUES, value.standaloneValues)
                .set(member.STANDALONE_MANUAL_FIELDS, JsonArray(value.standaloneManualFields.map(::JsonPrimitive)))
                .where(member.WORKSPACE_ID.eq(workspace)).and(member.TRANSACTION_ID.eq(value.transactionId)).execute() == 1)
            savedMembers[value.transactionId] = value.copy()
        }
    }

    private data class Source(val values:JsonObject,val manual:Set<String>)
    private fun sources(ids: Collection<Long>): Map<Long, Source> {
        val result = linkedMapOf<Long, Source>()
        val fact = CASH_TRANSACTIONS
        for (batch in ids.chunked(400)) {
            sql.select(fact.ID, fact.CATEGORY_ID, fact.NOTE, fact.COUNTERPARTY, fact.COUNTERPARTY_ACCOUNT, fact.COUNTERPARTY_ACCOUNT_ATTRS, fact.MANUAL_OVERRIDES)
                .from(fact).where(fact.WORKSPACE_ID.eq(workspace)).and(fact.ID.`in`(batch)).fetch().forEach { row ->
                    val values = buildJsonObject {
                        put("category_id", row[fact.CATEGORY_ID]?.let(::JsonPrimitive) ?: JsonNull); put("note", row[fact.NOTE]!!)
                        put("counterparty", row[fact.COUNTERPARTY]!!); put("counterparty_account", row[fact.COUNTERPARTY_ACCOUNT]!!)
                        put("counterparty_account_attrs", row[fact.COUNTERPARTY_ACCOUNT_ATTRS]!!)
                    }
                    val overrides = row[fact.MANUAL_OVERRIDES] ?: JsonNull
                    val keys = if (overrides == JsonNull) emptySet() else overrides.jsonObject.keys
                    result[row[fact.ID]!!] = Source(values, keys intersect commonCashFields)
                }
        }
        return result
    }
    private fun insert(e: CashEntry) {
        val table = CASH_ENTRIES
        check(sql.insertInto(table).set(table.ID, e.id).set(table.WORKSPACE_ID, workspace).set(table.ROOT_TRANSACTION_ID, e.rootTransactionId)
            .set(table.REVISION, 1L).set(table.CATEGORY_ID, e.values["category_id"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content)
            .set(table.NOTE, e.values.getValue("note").jsonPrimitive.content).set(table.COUNTERPARTY, e.values.getValue("counterparty").jsonPrimitive.content)
            .set(table.COUNTERPARTY_ACCOUNT, e.values.getValue("counterparty_account").jsonPrimitive.content)
            .set(table.COUNTERPARTY_ACCOUNT_ATTRS, e.values.getValue("counterparty_account_attrs"))
            .set(table.MANUAL_FIELDS, JsonArray(e.manualFields.map(::JsonPrimitive))).setNull(table.SPLIT_INHERIT).execute() == 1)
        entries[e.id] = e; savedEntries[e.id] = e.copy(); knownEntries[e.id] = e
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
            val group = sql.select(memberFields).from(m).where(m.WORKSPACE_ID.eq(workspace)).and(m.ENTRY_ID.eq(entry.id)).fetch(::member)
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
        val ids=projections.flatMap {it.storedMemberIds()}.toSet();val (ms,es)=forTransactions(ids);val source=sources(ids)
        val old=ms.values.groupBy {it.entryId}.mapValues {(_,rows) ->rows.map {it.transactionId}.toSet()};val result=mutableListOf<CashSplitFieldDiff>()
        for(p in projections) {
            val group=p.storedMemberIds().toSet();val root=p.primaryRecord.id.storedCashId();val restored=source.getValue(root).values.toMutableMap()
            ms[root]?.let {m ->m.standaloneManualFields.forEach {restored[it]=m.standaloneValues.getValue(it)}}
            for(id in group.mapNotNull {ms[it]?.entryId}.toSet()) {
                if(old.getValue(id).all {it in group}) continue
                val e=es.getValue(id)
                for(field in sorted(e.manualFields)) if(e.values.getValue(field)!=restored.getValue(field)) result+=CashSplitFieldDiff(field,e.values.getValue(field),restored.getValue(field),group.sorted())
            }
        };return result
    }
    private fun projectionForSource(id:Long):CashProjection {
        val fact = CASH_TRANSACTIONS
        val row = sql.select(fact.ID, fact.ACCOUNT_ID, fact.OCCURRED_AT, fact.AMOUNT, fact.CURRENCY, fact.COUNTERPARTY,
            fact.CATEGORY_ID, fact.NOTE, fact.SOURCE_TYPE, fact.RECORD_ID, fact.CASH_GRANULARITY, fact.STATE, fact.CREATED_AT)
            .from(fact).where(fact.WORKSPACE_ID.eq(workspace)).and(fact.ID.eq(id)).fetchOne()
            ?: throw CashPolicyError("entry_changed", emptyList())
        val source = CashProjectionFact.create(row[fact.ID]!!, row[fact.ACCOUNT_ID], row[fact.OCCURRED_AT], row[fact.AMOUNT]?.text,
            row[fact.CURRENCY], row[fact.COUNTERPARTY]!!, row[fact.CATEGORY_ID], row[fact.NOTE]!!, row[fact.SOURCE_TYPE], row[fact.RECORD_ID]!!,
            cashGranularity = row[fact.CASH_GRANULARITY]!!, state = row[fact.STATE]!!, createdAt = row[fact.CREATED_AT])
        return buildCashProjections(listOf(source), emptyList()).projections.single()
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
        if (category != null && !sql.fetchExists(CASH_CATEGORIES, CASH_CATEGORIES.WORKSPACE_ID.eq(workspace).and(CASH_CATEGORIES.ID.eq(category))))
            throw CashPolicyError("category_unavailable", listOf("category_id"))
        if(values.isEmpty()) return
        for(id in ids.toSet()) {
            var entry=current(id)
            if(entry==null) {reconcile(listOf(projectionForSource(id)));entry=current(id)!!}
            entry.values=JsonObject(entry.values+values);entry.manualFields=sorted(entry.manualFields+values.keys);entry.revision=Math.addExact(entry.revision,1)
        };flush()
    }
    fun removeMembers(ids:Collection<Long>) {
        val (ms,es)=forTransactions(ids)
        for (batch in ms.keys.chunked(400)) {
            check(sql.deleteFrom(CASH_ENTRY_MEMBERS).where(CASH_ENTRY_MEMBERS.WORKSPACE_ID.eq(workspace))
                .and(CASH_ENTRY_MEMBERS.TRANSACTION_ID.`in`(batch)).execute() == batch.size)
        }
        ms.keys.forEach {members.remove(it);savedMembers.remove(it);expiredMembers.remove(it)}
        es.values.forEach {it.revision=Math.addExact(it.revision,1)};flush()
        for (id in es.keys) deleteEmptyEntry(id)
    }
    private fun deleteEmptyEntry(id: String) {
        val entry = CASH_ENTRIES
        val member = CASH_ENTRY_MEMBERS
        val count = sql.deleteFrom(entry).where(entry.WORKSPACE_ID.eq(workspace)).and(entry.ID.eq(id))
            .andNotExists(DSL.selectOne().from(member).where(member.WORKSPACE_ID.eq(workspace)).and(member.ENTRY_ID.eq(id))).execute()
        if (count == 1) { entries.remove(id); savedEntries.remove(id) }
    }
    fun reconcile(projections:List<CashProjection>):Map<String,CashEntry> {
        val ids=projections.flatMap {it.storedMemberIds()}.toSet();val (ms,es)=forTransactions(ids);val source=sources(ids)
        val oldMembers=ms.values.groupBy {it.entryId}.mapValues {(_,values) ->values.map {it.transactionId}.toSet()}
        val oldRoots=es.values.map {it.rootTransactionId}.toSet();val used=mutableSetOf<String>();val result=linkedMapOf<String,CashEntry>()
        val ordered=projections.sortedWith(compareBy<CashProjection> {it.primaryRecord.id.storedCashId() !in oldRoots}.thenComparator {a,b ->cashRelationStringOrder.compare(a.projectionId,b.projectionId)})
        val fresh=mutableListOf<CashEntry>();val freshMembers=mutableListOf<CashEntryMember>()
        for(p in ordered) {
            val group=p.storedMemberIds().toSet();val root=p.primaryRecord.id.storedCashId();val oldIds=group.mapNotNull {ms[it]?.entryId}.toSet()
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
            val table = CASH_ENTRY_MEMBERS
            check(sql.insertInto(table).set(table.WORKSPACE_ID, workspace).set(table.TRANSACTION_ID, m.transactionId).set(table.ENTRY_ID, m.entryId)
                .set(table.STANDALONE_VALUES, m.standaloneValues).set(table.STANDALONE_MANUAL_FIELDS, JsonArray(m.standaloneManualFields.map(::JsonPrimitive))).execute() == 1)
            savedMembers[m.transactionId] = m.copy()
        }
        flush();es.values.forEach {it.splitInherit=null};flush()
        for (id in es.keys-used) deleteEmptyEntry(id)
        return result
    }
}
