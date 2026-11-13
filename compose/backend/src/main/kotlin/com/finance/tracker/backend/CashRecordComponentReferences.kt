package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import org.jooq.Field

/** Repair references before removing draft parts; confirmed references always reject the deletion. */
internal fun detachCashDraftComponentReferences(context: CashSqlContext, ids: Set<Long>) {
    val sql = context.sql
    val workspace = context.workspace
    val relation = TRANSACTION_RELATIONS
    val candidate = CASH_RELATION_CANDIDATES
    val rows = linkedMapOf<Long, org.jooq.Record>()
    for (batch in ids.sorted().chunked(500)) sql.selectFrom(relation).where(relation.WORKSPACE_ID.eq(workspace)
        .and(relation.PRIMARY_COMPONENT_ID.`in`(batch).or(relation.SECONDARY_COMPONENT_ID.`in`(batch)).or(relation.ANCHOR_COMPONENT_ID.`in`(batch))))
        .fetch().forEach { rows[it[relation.ID]!!] = it }
    if (rows.values.any { it[relation.ACTIVE] == true && it[relation.STATE] == "confirmed" }) relationFailure("relation_context_changed")
    for ((identifier, row) in rows) {
        val fields = linkedMapOf<Field<*>, Any?>()
        if (row[relation.PRIMARY_COMPONENT_ID] in ids) { fields[relation.PRIMARY_COMPONENT_ID] = null; fields[relation.PRIMARY_ROLE] = null }
        if (row[relation.SECONDARY_COMPONENT_ID] in ids) { fields[relation.SECONDARY_COMPONENT_ID] = null; fields[relation.SECONDARY_ROLE] = null }
        if (row[relation.ANCHOR_COMPONENT_ID] in ids) fields[relation.ANCHOR_COMPONENT_ID] = null
        fields[relation.ROW_REVISION] = Math.addExact(row[relation.ROW_REVISION]!!, 1L)
        sql.update(relation).set(fields).where(relation.WORKSPACE_ID.eq(workspace).and(relation.ID.eq(identifier))).execute()
    }
    for (batch in ids.sorted().chunked(500)) sql.update(candidate).setNull(candidate.CANDIDATE_COMPONENT_ID).set(candidate.VALIDITY, "incomplete")
        .where(candidate.WORKSPACE_ID.eq(workspace).and(candidate.CANDIDATE_COMPONENT_ID.`in`(batch))).execute()
}
