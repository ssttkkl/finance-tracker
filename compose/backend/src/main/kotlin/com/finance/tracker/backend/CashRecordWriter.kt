package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import kotlinx.serialization.json.*
import org.jooq.Field

private fun cashWriterTruthy(value: Any?): Boolean = when (value) {
    null -> false
    is Boolean -> value
    is Map<*, *> -> value.isNotEmpty()
    is Collection<*> -> value.isNotEmpty()
    is String -> value.isNotEmpty()
    is Number -> value.toDouble() != 0.0
    else -> true
}

internal fun cashImportIssuesUnchanged(operation: Map<String, Any?>): Boolean {
    fun reasons(key: String) = (operation[key] as List<*>).map { raw -> val issue = raw as Map<*, *>; issue["code"] to issue["field_path"] }.toSet()
    return reasons("expected_issues") == reasons("issues") && !cashWriterTruthy(operation["pending_fields"]) &&
        !cashWriterTruthy(operation["candidate_ids"]) && !cashWriterTruthy(operation["candidate_set_ref"]) &&
        !cashWriterTruthy(operation["resolved_issue_ids"])
}

internal data class CashRecordWriteResult(val transactionId: Long, val componentIds: Map<String, String>,
    val before: Map<String, Any?>?, val after: Map<String, Any?>, val touched: Set<Long>)

/** 消费调用方已重验的操作；流水、现金部分、来源及当前问题共用外层事务。 */
internal class CashRecordWriter(private val context: CashSqlContext) {
    private val sql get() = context.sql
    private val workspace get() = context.workspace
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") private fun copy(value: Any?): Map<String, Any?>? = CashRecordPolicy.copyValue(value) as? Map<String, Any?>
    private fun id(value: Any?): Long? = value?.toString()?.toLong()

    fun save(operation: Map<String, Any?>, sourceRows: Map<String, SourceOccurrence>): CashRecordWriteResult {
        context.requireActive()
        val input = map(operation["values"])
        val values = input.mapValues { (key, value) -> CashRecordPolicy.normalize(key, value) }
        val parentId = id(operation["transaction_id"])
        val fact = CASH_TRANSACTIONS
        val parent = parentId?.let { sql.selectFrom(fact).where(fact.WORKSPACE_ID.eq(workspace).and(fact.ID.eq(it))).fetchOne() }
        if (parentId != null && parent == null) throw CashPolicyError("record_changed", emptyList())
        val before = copy(operation["current"])
        val wasPosted = parent?.get(fact.STATE) == "posted"
        val oldContext = if (wasPosted && operation["type_changed"] == true && parent?.get(fact.RECORD_ORIGIN) != "manual_transfer")
            CashRelationContextService(context).load(setOf(parentId!!)) else null
        val manual = (operation["manual_fields"] as List<*>).map { it.toString() }
        val sourceChanged = operation["planned_action"] != "duplicate" &&
            (!CashRecordPolicy.valuesEqual(input, operation["current"]) || manual.isNotEmpty() || cashWriterTruthy(operation["merge_draft"]) ||
                "source_explanation" in operation && !CashRecordPolicy.valuesEqual(operation["source_explanation"], cashSourceValue(parent?.get(fact.RELATION_METADATA)) ?: emptyMap<String, Any?>()))
        val writer = CashRecordValueWriter(context)
        val identifier = writer.saveParent(operation, values, parentId, parent, sourceChanged)
        operation["entry_edit"]?.let { raw ->
            val edit = map(raw)
            context.entries.applyEdit(identifier, edit["patch"], id(edit["revision"]))
        }
        val mapping = writer.saveComponents(identifier, operation, values, before, parentId != null, wasPosted, ::detachDraftComponents)
        if (oldContext != null) CashRelationTypeUpdateService(context).maintain(oldContext, setOf(identifier), publish = false)
        val source = CashSourceWriteRepository(context)
        var firstBinding = parentId == null
        var bindingsChanged = false
        for (update in maps(operation["source_updates"])) if (update["bind"] == true) {
            val changed = source.saveOccurrence(identifier, sourceRows.getValue(update["row_ref"].toString()), update["uncertain"] == true, firstBinding)
            bindingsChanged = bindingsChanged || changed
            firstBinding = false
        }
        if (parentId != null || maps(operation["issues"]).isNotEmpty()) rowIssues(identifier, operation, source)
        if (parentId != null && sourceChanged) context.markCashRelationCandidatesStale(setOf(identifier))
        val touched = oldContext?.transactionIds.orEmpty().toMutableSet()
        if (bindingsChanged || !cashImportIssuesUnchanged(operation)) touched += identifier
        return CashRecordWriteResult(identifier, mapping, before, copy(input)!!, touched)
    }

    private fun detachDraftComponents(ids: Set<Long>) {
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

    private fun rowIssues(parent: Long, operation: Map<String, Any?>, source: CashSourceWriteRepository) {
        val issue = CASH_TRANSACTION_ISSUES
        val transaction = CASH_TRANSACTIONS
        val existing = sql.selectFrom(issue).where(issue.WORKSPACE_ID.eq(workspace).and(issue.TRANSACTION_ID.eq(parent))).fetch()
        val byReason = existing.associateBy { it[issue.CODE] to it[issue.FIELD_PATH] }
        val retained = (operation["retained_issue_ids"] as List<*>).map { it.toString().toLong() }.toMutableSet()
        for (value in maps(operation["issues"])) {
            val old = byReason[value["code"] to value["field_path"]]
            val code = value["code"].toString()
            if (old != null && old[issue.ID] in retained && code == "source_update_pending" && map(operation["pending_fields"]).isEmpty()) continue
            val candidateIds = (operation["candidate_ids"] as? List<*>).orEmpty()
            if (old != null && code == "source_identity_ambiguous" && candidateIds.isEmpty()) { retained += old[issue.ID]!!; continue }
            val candidates = linkedMapOf<Long, Long>()
            if (code == "source_identity_ambiguous") for (candidate in candidateIds) {
                val key = candidate.toString().toLong()
                candidates[key] = sql.select(transaction.ROW_REVISION).from(transaction).where(transaction.WORKSPACE_ID.eq(workspace)
                    .and(transaction.ID.eq(key))).fetchOne(transaction.ROW_REVISION) ?: throw CashSourceBindingConflict("source_candidate_changed")
            }
            var pending = if (code == "source_update_pending") map(operation["pending_fields"]) else emptyMap()
            if (pending.isNotEmpty()) pending = pending + ("_source_explanation" to (operation["source_explanation"] ?: emptyMap<String, Any?>())) +
                if ("source_payload" in pending) mapOf("_source_payload_present" to true) else emptyMap()
            val key = old?.get(issue.ISSUE_KEY) ?: "import:" + cashImportDigest(value["code"], value["field_path"])
            val saved = source.saveIssue(parent, key, code, value["field_path"] as? String, pending["source_payload"],
                pending.filterKeys { it != "source_payload" }, candidates = candidates)
            retained += saved.id
        }
        for (old in existing) if (old[issue.ID] !in retained) source.clearIssue(parent, old[issue.ISSUE_KEY]!!)
    }
}
