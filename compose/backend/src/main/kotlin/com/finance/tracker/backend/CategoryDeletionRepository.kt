package com.finance.tracker.backend

import java.sql.Connection
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.jooq.Field
import org.jooq.JSON
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import com.finance.tracker.backend.persistence.JsonElementConverter
import com.finance.tracker.backend.persistence.generated.tables.references.*

internal class CategoryDeletionRepository(private val sqlite: Boolean) {
    data class Impact(val rowRevision: Long,val children: Long,val usage: Long)
    fun impact(c: Connection,workspace: String,id: String,lock: Boolean=false): Impact {
        val category = CASH_CATEGORIES.`as`("category"); val child = CASH_CATEGORIES.`as`("child"); val entry = CASH_ENTRIES.`as`("entry")
        val children = DSL.select(DSL.count().cast(SQLDataType.BIGINT)).from(child)
            .where(child.WORKSPACE_ID.eq(category.WORKSPACE_ID)).and(child.PARENT_ID.eq(category.ID)).asField<Long>()
        val usage = DSL.select(DSL.count().cast(SQLDataType.BIGINT)).from(entry)
            .where(entry.WORKSPACE_ID.eq(category.WORKSPACE_ID)).and(entry.CATEGORY_ID.eq(category.ID)).asField<Long>()
        val query = c.jooq(sqlite).select(category.REVISION, children, usage).from(category)
            .where(category.WORKSPACE_ID.eq(workspace)).and(category.ID.eq(id))
        val row = (if(lock && !sqlite) query.forUpdate().of(category) else query).fetchOne() ?: throw CategoryFailure("category.not_found")
        return Impact(row.value1()!!, row.value2()!!, row.value3()!!)
    }

    private fun json(field: Field<JsonElement?>): Field<JSON> = field.coerce(SQLDataType.JSON)
    private fun jsonb(field: Field<JsonElement?>): Field<JSONB> = field.cast(SQLDataType.JSONB)
    private fun converted(field: Field<JSON>): Field<JsonElement> = field.convert(JsonElementConverter())
    private fun protection(field: Field<JsonElement?>): Field<JsonElement> {
        val result = if (sqlite) {
            val elements = DSL.table("{0}", DSL.function("json_each", SQLDataType.JSON, json(field))).`as`("protected_fields")
            val value = DSL.field(DSL.name("protected_fields", "value"), SQLDataType.VARCHAR)
            DSL.`when`(DSL.exists(DSL.selectOne().from(elements).where(value.eq(DSL.inline("category_id")))), json(field))
                .otherwise(DSL.function("json_insert", SQLDataType.JSON, json(field), DSL.inline("$[#]"), DSL.inline("category_id")))
        } else {
            val addition = DSL.inline(JSONB.jsonb("[\"category_id\"]"))
            DSL.`when`(jsonb(field).contains(addition), jsonb(field))
                .otherwise(DSL.function("jsonb_concat", SQLDataType.JSONB, jsonb(field), addition)).cast(SQLDataType.JSON)
        }
        return converted(result)
    }
    private fun clearedValues(field: Field<JsonElement?>): Field<JsonElement> = converted(if (sqlite)
        DSL.function("json_set", SQLDataType.JSON, json(field), DSL.inline("$.category_id"), DSL.inline(null, SQLDataType.VARCHAR))
    else DSL.function("jsonb_set", SQLDataType.JSONB, jsonb(field), DSL.inline(arrayOf("category_id")), DSL.inline(JSONB.jsonb("null"))).cast(SQLDataType.JSON))

    private fun clearedOverride(field: Field<JsonElement?>): Field<JsonElement> {
        val result = if (sqlite) {
            val attribute = DSL.function("json_extract", SQLDataType.JSON, json(field), DSL.inline("$.category_id"))
            val existing = DSL.`when`(DSL.function("json_type", SQLDataType.VARCHAR, json(field), DSL.inline("$.category_id")).eq(DSL.inline("object")), attribute)
                .otherwise(DSL.inline(JSON.json("{}")))
            val inner = DSL.function("json_set", SQLDataType.JSON, existing, DSL.inline("$.value"), DSL.inline(null, SQLDataType.VARCHAR))
            DSL.function("json_set", SQLDataType.JSON, json(field), DSL.inline("$.category_id"), DSL.function("json", SQLDataType.JSON, inner))
        } else {
            val attribute = DSL.jsonbGetAttribute(jsonb(field), DSL.inline("category_id"))
            val existing = DSL.`when`(DSL.function("jsonb_typeof", SQLDataType.VARCHAR, attribute).eq(DSL.inline("object")), attribute)
                .otherwise(DSL.inline(JSONB.jsonb("{}")))
            val inner = DSL.function("jsonb_concat", SQLDataType.JSONB, existing, DSL.inline(JSONB.jsonb("{\"value\":null}")))
            DSL.function("jsonb_set", SQLDataType.JSONB, jsonb(field), DSL.inline(arrayOf("category_id")), inner, DSL.inline(true)).cast(SQLDataType.JSON)
        }
        return converted(result)
    }

    fun clear(c: Connection,workspace: String,id: String,now: Instant,usage: Long) {
        val sql = c.jooq(sqlite); val entries = CASH_ENTRIES; val members = CASH_ENTRY_MEMBERS; val facts = CASH_TRANSACTIONS
        val candidates = CASH_RELATION_CANDIDATES; val states = CASH_PROJECTION_STATES; val projections = CASH_PROJECTIONS
        sql.update(entries).setNull(entries.CATEGORY_ID).set(entries.REVISION, entries.REVISION.plus(1L))
            .set(entries.MANUAL_FIELDS, protection(entries.MANUAL_FIELDS)).where(entries.WORKSPACE_ID.eq(workspace)).and(entries.CATEGORY_ID.eq(id)).execute()
        val category = if (sqlite) DSL.function("json_extract", SQLDataType.VARCHAR, json(members.STANDALONE_VALUES), DSL.inline("$.category_id"))
            else DSL.jsonbGetAttributeAsText(jsonb(members.STANDALONE_VALUES), DSL.inline("category_id"))
        sql.update(members).set(members.STANDALONE_VALUES, clearedValues(members.STANDALONE_VALUES))
            .set(members.STANDALONE_MANUAL_FIELDS, protection(members.STANDALONE_MANUAL_FIELDS))
            .where(members.WORKSPACE_ID.eq(workspace)).and(category.eq(id)).execute()
        val hasCandidates = sql.fetchExists(candidates, candidates.WORKSPACE_ID.eq(workspace).and(candidates.VALIDITY.ne(DSL.inline("stale"))))
        val update = sql.update(facts).setNull(facts.CATEGORY_ID).set(facts.ROW_REVISION, facts.ROW_REVISION.plus(1L))
            .set(facts.MANUAL_OVERRIDES, clearedOverride(facts.MANUAL_OVERRIDES)).where(facts.WORKSPACE_ID.eq(workspace)).and(facts.CATEGORY_ID.eq(id))
        val changed = if (hasCandidates) {
            val ids = update.returningResult(facts.ID).fetch(facts.ID).filterNotNull()
            invalidateCandidates(c, workspace, ids); ids.size.toLong()
        } else update.execute().toLong()
        val stateQuery = sql.select(states.AVAILABILITY, states.ACTIVE_DATASET_ID, states.PROJECTION_VERSION, states.SOURCE_REVISION)
            .from(states).where(states.WORKSPACE_ID.eq(workspace))
        val row = (if (sqlite) stateQuery else stateQuery.forUpdate()).fetchOne()
        val state = row?.takeIf { it[states.AVAILABILITY] == "ready" }?.let { value -> value[states.ACTIVE_DATASET_ID]?.let {
            Triple(it, value[states.PROJECTION_VERSION]!!, value[states.SOURCE_REVISION]!!)
        } }
        val projectionUpdate = sql.update(projections).setNull(projections.CATEGORY_ID).setNull(projections.CATEGORY_PATH).set(projections.UPDATED_AT, now)
        if (state != null) projectionUpdate.set(projections.BUILT_PROJECTION_VERSION,
            DSL.`when`(projections.DATASET_ID.eq(state.first), Math.addExact(state.second, 1)).otherwise(projections.BUILT_PROJECTION_VERSION))
        projectionUpdate.where(projections.WORKSPACE_ID.eq(workspace)).and(projections.CATEGORY_ID.eq(id)).execute()
        if(state!=null && (usage!=0L || changed!=0L)) check(sql.update(states).set(states.PROJECTION_VERSION, Math.addExact(state.second,1))
            .set(states.SOURCE_REVISION, Math.addExact(state.third,1)).set(states.UPDATED_AT, now).set(states.LAST_BUILD_STATUS, "succeeded")
            .setNull(states.LAST_ERROR_CODE).setNull(states.LAST_ERROR_SUMMARY).where(states.WORKSPACE_ID.eq(workspace)).execute()==1)
    }

    internal fun invalidateCandidates(c: Connection,workspace: String,ids: Collection<Long>):Set<Long> {
        val sql = c.jooq(sqlite); val candidate = CASH_RELATION_CANDIDATES.`as`("candidate"); val relation = TRANSACTION_RELATIONS
        val facts = CASH_TRANSACTIONS.`as`("tx")
        val changed=mutableSetOf<Long>()
        for(batch in ids.distinct().sorted().chunked(500)) {
            val endpoints = listOf(relation.PRIMARY_TRANSACTION_ID, relation.SECONDARY_TRANSACTION_ID, relation.ANCHOR_TRANSACTION_ID).map {
                DSL.select(relation.ID).from(relation).where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ACTIVE.isTrue).and(it.`in`(batch))
            }
            val live = candidate.WORKSPACE_ID.eq(workspace).and(candidate.VALIDITY.ne(DSL.inline("stale")))
            val revision = DSL.select(facts.ROW_REVISION).from(facts).where(facts.WORKSPACE_ID.eq(workspace)).and(facts.ID.eq(candidate.CANDIDATE_TRANSACTION_ID))
            val direct = sql.select(candidate.ID, candidate.RELATION_ID).from(candidate).where(live).and(candidate.CANDIDATE_TRANSACTION_ID.`in`(batch))
                .and(candidate.EXPECTED_ROW_REVISION.ne(revision))
            val indirect = sql.select(candidate.ID, candidate.RELATION_ID).from(candidate).where(live)
                .and(candidate.RELATION_ID.`in`(endpoints[0].union(endpoints[1]).union(endpoints[2])))
            val rows = direct.union(indirect).fetch().map { it.value1()!! to it.value2()!! }
            changed.addAll(rows.map {it.second})
            val target = CASH_RELATION_CANDIDATES
            for(candidateBatch in rows.map {it.first}.chunked(500)) sql.update(target).set(target.VALIDITY, "stale")
                .where(target.WORKSPACE_ID.eq(workspace)).and(target.ID.`in`(candidateBatch)).execute()
        }
        for(batch in changed.sorted().chunked(500)) sql.update(relation).set(relation.ROW_REVISION, relation.ROW_REVISION.plus(1L))
            .where(relation.WORKSPACE_ID.eq(workspace)).and(relation.ID.`in`(batch)).execute()
        return changed
    }
}
