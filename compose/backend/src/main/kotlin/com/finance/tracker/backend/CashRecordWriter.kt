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
    private fun amount(value: Any?): CashStoredDecimal? = value?.let { CashStoredDecimal(it.toString()) }

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
        val updates = linkedMapOf<Field<*>, Any?>()
        if (sourceChanged) {
            val fields = mapOf("state" to fact.STATE, "source_type" to fact.SOURCE_TYPE, "record_id" to fact.RECORD_ID,
                "counterparty" to fact.COUNTERPARTY, "counterparty_account" to fact.COUNTERPARTY_ACCOUNT,
                "counterparty_account_attrs" to fact.COUNTERPARTY_ACCOUNT_ATTRS, "note" to fact.NOTE, "category_id" to fact.CATEGORY_ID,
                "record_type" to fact.RECORD_TYPE, "record_subtype" to fact.RECORD_SUBTYPE, "source_payload" to fact.SOURCE_PAYLOAD,
                "amount" to fact.AMOUNT, "currency" to fact.CURRENCY, "occurred_at" to fact.OCCURRED_AT,
                "account_id" to fact.ACCOUNT_ID, "cash_granularity" to fact.CASH_GRANULARITY)
            for ((key, field) in fields) if (key in values) updates[field] = when (key) {
                "account_id" -> id(values[key])
                "amount" -> amount(values[key])
                "source_payload", "counterparty_account_attrs" -> sourceJson(values[key])
                else -> values[key]
            }
            val overrides = (parent?.get(fact.MANUAL_OVERRIDES) as? JsonObject).orEmpty().toMutableMap()
            manual.forEach { overrides[it] = JsonPrimitive(true) }
            updates[fact.MANUAL_OVERRIDES] = JsonObject(overrides)
            if ("source_explanation" in operation && map(operation["pending_fields"]).isEmpty()) updates[fact.RELATION_METADATA] = sourceJson(operation["source_explanation"])
            if (parentId != null) updates[fact.ROW_REVISION] = Math.addExact(parent!![fact.ROW_REVISION]!!, 1L)
        }
        val identifier = if (parentId == null) {
            val defaults = linkedMapOf<Field<*>, Any?>(fact.WORKSPACE_ID to workspace, fact.RECORD_ORIGIN to "import", fact.STATE to "posted",
                fact.CASH_GRANULARITY to "atomic", fact.ROW_REVISION to 1L, fact.SOURCE_TYPE to null, fact.RECORD_ID to "",
                fact.SOURCE_PAYLOAD to JsonNull, fact.RELATION_METADATA to JsonNull, fact.MANUAL_OVERRIDES to JsonObject(emptyMap()),
                fact.SOURCE_FINGERPRINT to null, fact.MANUAL_TRANSFER_ID to null, fact.ACCOUNT_ID to null, fact.AMOUNT to null,
                fact.CURRENCY to null, fact.OCCURRED_AT to null, fact.COUNTERPARTY to "", fact.COUNTERPARTY_ACCOUNT to "",
                fact.COUNTERPARTY_ACCOUNT_ATTRS to JsonArray(emptyList()), fact.NOTE to "", fact.CATEGORY_ID to null,
                fact.RECORD_TYPE to null, fact.RECORD_SUBTYPE to null, fact.CREATED_AT to context.now(), fact.DELETED_AT to null,
                fact.DELETED_BY to "", fact.DELETE_REASON to "")
            sql.insertInto(fact).set(defaults + updates).returningResult(fact.ID).fetchSingle().value1()!!
        } else {
            if (updates.isNotEmpty()) check(sql.update(fact).set(updates).where(fact.WORKSPACE_ID.eq(workspace).and(fact.ID.eq(parentId))).execute() == 1)
            parentId
        }
        operation["entry_edit"]?.let { raw ->
            val edit = map(raw)
            context.entries.applyEdit(identifier, edit["patch"], id(edit["revision"]))
        }
        val mapping = saveComponents(identifier, operation, values, before, parentId != null, wasPosted)
        if (oldContext != null) CashRelationTypeUpdateService(context).maintain(oldContext, setOf(identifier), publish = false)
        val source = CashSourceWriteRepository(context)
        var firstBinding = parentId == null
        for (update in maps(operation["source_updates"])) if (update["bind"] == true) {
            source.saveOccurrence(identifier, sourceRows.getValue(update["row_ref"].toString()), update["uncertain"] == true, firstBinding)
            firstBinding = false
        }
        if (parentId != null || maps(operation["issues"]).isNotEmpty()) rowIssues(identifier, operation, source)
        if (parentId != null && sourceChanged) context.markCashRelationCandidatesStale(setOf(identifier))
        val touched = oldContext?.transactionIds.orEmpty().toMutableSet()
        if (!cashImportIssuesUnchanged(operation)) touched += identifier
        return CashRecordWriteResult(identifier, mapping, before, copy(input)!!, touched)
    }

    private fun saveComponents(parent: Long, operation: Map<String, Any?>, values: Map<String, Any?>,
        before: Map<String, Any?>?, existing: Boolean, wasPosted: Boolean): Map<String, String> {
        val virtual = maps(operation["preview_components"]).associate { it["ordinal"].toString().toLong() to it["component_id"].toString() }
        val previous = before?.get("components")?.let(::maps).orEmpty()
        val identifiers = previous.map { it["component_id"] }
        if (wasPosted && values["state"] == "posted" && before?.get("state") == "posted" && parent.toString() == operation["transaction_id"] &&
            CashRecordPolicy.valuesEqual(previous, map(operation["values"])["components"]) && identifiers.all { it != null } && identifiers.toSet().size == identifiers.size)
            return previous.associate { virtual.getValue(it["ordinal"].toString().toLong()) to it["component_id"].toString() }
        val part = CASH_TRANSACTION_COMPONENTS
        val parts = if (existing) sql.selectFrom(part).where(part.WORKSPACE_ID.eq(workspace).and(part.CASH_TRANSACTION_ID.eq(parent))).fetch().associateBy { it[part.ID]!! }
            else emptyMap()
        val desired = maps(values["components"])
        val wanted = desired.mapNotNull { id(it["component_id"]) }.toSet()
        val removed = parts.keys - wanted
        if (removed.isNotEmpty()) {
            detachDraftComponents(removed)
            for (batch in removed.sorted().chunked(500)) sql.deleteFrom(part).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.`in`(batch))).execute()
        }
        val moving = desired.mapNotNull { value -> id(value["component_id"])?.let { parts[it] }?.takeIf { it[part.ORDINAL] != id(value["ordinal"]) } }
        if (moving.isNotEmpty()) {
            val blocked = (parts.values.map { it[part.ORDINAL]!! } + desired.map { id(it["ordinal"])!! }).toMutableSet()
            var temporary = 0L
            for (row in moving) {
                while (temporary in blocked) temporary++
                sql.update(part).set(part.ORDINAL, temporary).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.eq(row[part.ID]))).execute()
                blocked += temporary
            }
        }
        val mapping = linkedMapOf<String, String>()
        for (value in desired) {
            val old = id(value["component_id"])?.let { parts[it] }
            val fields = linkedMapOf<Field<*>, Any?>(part.ORDINAL to id(value["ordinal"]), part.ACCOUNT_ID to id(value["account_id"]),
                part.AMOUNT to amount(value["amount"]), part.CURRENCY to value["currency"])
            val identifier = if (old == null) sql.insertInto(part).set(fields + mapOf(part.WORKSPACE_ID to workspace, part.CASH_TRANSACTION_ID to parent,
                part.LABEL to "", part.SOURCE_KEY to "", part.METADATA_JSON to JsonObject(emptyMap()), part.CREATED_AT to context.now()))
                .returningResult(part.ID).fetchSingle().value1()!!
            else old[part.ID]!!.also { key -> sql.update(part).set(fields).where(part.WORKSPACE_ID.eq(workspace).and(part.ID.eq(key))).execute() }
            mapping[virtual.getValue(id(value["ordinal"])!!)] = identifier.toString()
        }
        return mapping
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
