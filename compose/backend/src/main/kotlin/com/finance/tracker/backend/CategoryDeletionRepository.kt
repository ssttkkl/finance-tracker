package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant

internal class CategoryDeletionRepository(private val sqlite: Boolean) {
    data class Impact(val rowRevision: Long,val children: Long,val usage: Long)
    fun impact(c: Connection,workspace: String,id: String,lock: Boolean=false): Impact = c.prepareStatement("""SELECT category.revision,
        (SELECT count(*) FROM cash_categories child WHERE child.workspace_id=category.workspace_id AND child.parent_id=category.id),
        (SELECT count(*) FROM cash_entries entry WHERE entry.workspace_id=category.workspace_id AND entry.category_id=category.id)
        FROM cash_categories category WHERE category.workspace_id=? AND category.id=?"""+if(lock && !sqlite) " FOR UPDATE OF category" else "").use {
        it.setString(1,workspace);it.setString(2,id);it.executeQuery().use {rows ->
            if(!rows.next()) throw CategoryFailure("category.not_found")
            Impact(rows.getLong(1),rows.getLong(2),rows.getLong(3))
        }
    }
    private fun protection(column:String):String=if(sqlite) "CASE WHEN EXISTS(SELECT 1 FROM json_each($column) WHERE value='category_id') THEN $column ELSE json_insert($column,'$[#]','category_id') END"
        else "CAST(CASE WHEN CAST($column AS jsonb) @> '[\"category_id\"]'::jsonb THEN CAST($column AS jsonb) ELSE CAST($column AS jsonb) || '[\"category_id\"]'::jsonb END AS json)"

    fun clear(c: Connection,workspace: String,id: String,now: Instant,usage: Long) {
        fun update(sql:String)=c.prepareStatement(sql).use {it.setString(1,workspace);it.setString(2,id);it.executeUpdate()}
        update("UPDATE cash_entries SET category_id=NULL,revision=revision+1,manual_fields=${protection("manual_fields")} WHERE workspace_id=? AND category_id=?")
        val values=if(sqlite) "json_set(standalone_values,'$.category_id',NULL)" else "CAST(jsonb_set(CAST(standalone_values AS jsonb),'{category_id}','null'::jsonb) AS json)"
        val category=if(sqlite) "json_extract(standalone_values,'$.category_id')" else "CAST(standalone_values AS jsonb)->>'category_id'"
        update("UPDATE cash_entry_members SET standalone_values=$values,standalone_manual_fields=${protection("standalone_manual_fields")} WHERE workspace_id=? AND $category=?")
        val candidates=c.prepareStatement("SELECT id FROM cash_relation_candidates WHERE workspace_id=? AND validity<>'stale' LIMIT 1").use {
            it.setString(1,workspace);it.executeQuery().use {rows ->rows.next()}
        }
        val overrides=if(sqlite) """json_set(manual_overrides,'$.category_id',json(json_set(
            CASE WHEN json_type(manual_overrides,'$.category_id')='object' THEN json_extract(manual_overrides,'$.category_id') ELSE '{}' END,'$.value',NULL)))"""
            else """CAST(jsonb_set(CAST(manual_overrides AS jsonb),'{category_id}',
                (CASE WHEN jsonb_typeof(CAST(manual_overrides AS jsonb)->'category_id')='object'
                THEN CAST(manual_overrides AS jsonb)->'category_id' ELSE '{}'::jsonb END) || '{"value":null}'::jsonb,true) AS json)"""
        val query="UPDATE cash_transactions SET category_id=NULL,row_revision=row_revision+1,manual_overrides=$overrides WHERE workspace_id=? AND category_id=?"
        val changedIds=mutableListOf<Long>()
        val changed=c.prepareStatement(query+if(candidates) " RETURNING id" else "").use {
            it.setString(1,workspace);it.setString(2,id)
            if(candidates) {it.executeQuery().use {rows ->while(rows.next()) changedIds+=rows.getLong(1)};changedIds.size.toLong()} else it.executeUpdate().toLong()
        }
        if(candidates) invalidateCandidates(c,workspace,changedIds)
        val state=c.prepareStatement("SELECT availability,active_dataset_id,projection_version,source_revision FROM cash_projection_states WHERE workspace_id=?"+if(sqlite) "" else " FOR UPDATE").use {
            it.setString(1,workspace);it.executeQuery().use {rows ->if(rows.next() && rows.getString(1)=="ready") rows.getString(2)?.let {dataset ->Triple(dataset,rows.getLong(3),rows.getLong(4))} else null}
        }
        c.prepareStatement("UPDATE cash_projections SET category_id=NULL,category_path=NULL,updated_at=?"+
            (if(state!=null) ",built_projection_version=CASE WHEN dataset_id=? THEN ? ELSE built_projection_version END" else "")+" WHERE workspace_id=? AND category_id=?").use {
            var index=1;it.instant(index++,now,sqlite)
            if(state!=null) {it.setString(index++,state.first);it.setLong(index++,Math.addExact(state.second,1))}
            it.setString(index++,workspace);it.setString(index,id);it.executeUpdate()
        }
        if(state!=null && (usage!=0L || changed!=0L)) c.prepareStatement("""UPDATE cash_projection_states SET projection_version=?,
            source_revision=?,updated_at=?,last_build_status='succeeded',last_error_code=NULL,last_error_summary=NULL WHERE workspace_id=?""").use {
            it.setLong(1,Math.addExact(state.second,1));it.setLong(2,Math.addExact(state.third,1));it.instant(3,now,sqlite);it.setString(4,workspace);check(it.executeUpdate()==1)
        }
    }

    internal fun invalidateCandidates(c: Connection,workspace: String,ids: Collection<Long>):Set<Long> {
        val changed=mutableSetOf<Long>()
        for(batch in ids.distinct().sorted().chunked(500)) {
            val marks=batch.joinToString(",") {"?"}
            val endpoints=listOf("primary_transaction_id","secondary_transaction_id","anchor_transaction_id").joinToString(" UNION ") {
                "SELECT id FROM transaction_relations WHERE workspace_id=? AND active=true AND $it IN ($marks)"
            }
            val sql="""SELECT candidate.id,candidate.relation_id FROM cash_relation_candidates candidate
                WHERE candidate.workspace_id=? AND candidate.validity<>'stale'
                AND candidate.candidate_transaction_id IN ($marks) AND candidate.expected_row_revision<>(SELECT tx.row_revision FROM cash_transactions tx WHERE tx.workspace_id=? AND tx.id=candidate.candidate_transaction_id)
                UNION SELECT candidate.id,candidate.relation_id FROM cash_relation_candidates candidate
                WHERE candidate.workspace_id=? AND candidate.validity<>'stale' AND candidate.relation_id IN ($endpoints)"""
            val rows=c.prepareStatement(sql).use {statement ->
                var index=1;statement.setString(index++,workspace);batch.forEach {statement.setLong(index++,it)};statement.setString(index++,workspace);statement.setString(index++,workspace)
                repeat(3) {statement.setString(index++,workspace);batch.forEach {statement.setLong(index++,it)}}
                statement.executeQuery().use {result ->buildList {while(result.next()) add(result.getLong(1) to result.getLong(2))}}
            }
            changed.addAll(rows.map {it.second})
            for(candidateBatch in rows.map {it.first}.chunked(500)) c.prepareStatement("UPDATE cash_relation_candidates SET validity='stale' WHERE workspace_id=? AND id IN (${candidateBatch.joinToString(",") {"?"}})").use {
                it.setString(1,workspace);candidateBatch.forEachIndexed {i,id ->it.setLong(i+2,id)};it.executeUpdate()
            }
        }
        for(batch in changed.sorted().chunked(500)) c.prepareStatement("UPDATE transaction_relations SET row_revision=row_revision+1 WHERE workspace_id=? AND id IN (${batch.joinToString(",") {"?"}})").use {
            it.setString(1,workspace);batch.forEachIndexed {i,id ->it.setLong(i+2,id)};it.executeUpdate()
        }
        return changed
    }
}
