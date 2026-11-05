package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table

internal class CashDraftMergeWriter(private val context: CashSqlContext, private val afterWrite: (String) -> Unit = {}) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun number(value: Any?) = value?.toString()?.toLong()
    private fun id(table: Table<*>) = table.field("id", Long::class.javaObjectType)!!
    private fun scope(table: Table<*>) = table.field("workspace_id", String::class.java)!!.eq(context.workspace)
    private fun row(table: Table<*>, identifier: Long): Record? = context.sql.selectFrom(table).where(scope(table)).and(id(table).eq(identifier)).fetchOne()
    private fun delete(table: Table<*>, identifier: Long) = context.sql.deleteFrom(table).where(scope(table)).and(id(table).eq(identifier)).execute()
    private fun bump(table: Table<*>, identifier: Long, revision: String) {
        val field = table.field(revision, Long::class.javaObjectType)!!
        val updates = linkedMapOf<Field<*>, Any?>(field to field.plus(1L))
        table.field("updated_at")?.takeIf { it.type == Instant::class.java }?.let { updates[it] = context.now() }
        context.sql.update(table).set(updates).where(scope(table)).and(id(table).eq(identifier)).execute()
    }
    private fun update(table: Table<*>, identifier: Long, values: Map<String, Any?>, revision: String? = null): Boolean {
        val current = row(table, identifier) ?: throw CashRelationFailure("relation_context_changed")
        val updates = values.mapKeys { table.field(it.key) ?: error("Unknown merge field") }.mapValues { (field, value) -> when {
            JsonElement::class.java.isAssignableFrom(field.type) -> sourceJson(value)
            field.type == Long::class.javaObjectType -> number(value)
            else -> value
        } }
        if (updates.all { (field, value) -> CashRecordPolicy.valuesEqual(current[field], value) }) return false
        val actual = updates.toMutableMap()
        if (revision != null) {
            val field = table.field(revision, Long::class.javaObjectType)!!; actual[field] = field.plus(1L)
        }
        table.field("updated_at")?.takeIf { it.type == Instant::class.java }?.let { actual[it] = context.now() }
        check(context.sql.update(table).set(actual).where(scope(table)).and(id(table).eq(identifier)).execute() == 1)
        return true
    }
    private val candidates = listOf(
        Triple(CASH_ISSUE_CANDIDATES, "issue_candidates", Triple("issue_id", CASH_TRANSACTION_ISSUES, "revision")),
        Triple(CASH_RELATION_CANDIDATES, "relation_candidates", Triple("relation_id", TRANSACTION_RELATIONS, "row_revision")),
    )
    fun prepareRecordUpdates(operation: Map<String, Any?>) {
        val moves = map(map(operation["merge_draft"])["reference_moves"])
        for ((table, key, owner) in candidates) {
            val touched = linkedSetOf<Long>()
            for (move in maps(moves[key]).filter { it["delete"] == true }) {
                val identifier = number(move["id"])!!
                row(table, identifier)?.let { current -> touched += number(current.get(owner.first))!!; delete(table, identifier) }
            }
            touched.forEach { bump(owner.second, it, owner.third) }
        }
    }
    fun save(operation: Map<String, Any?>): Set<Long> {
        val merge = map(operation["merge_draft"]); val source = number(merge["source_transaction_id"])!!; val target = number(merge["target_transaction_id"])!!
        CashSourceWriteRepository(context).transferDraftBindings(source, target); afterWrite("merge_bindings")
        moveIssues(operation); afterWrite("merge_issues")
        moveRelations(operation); afterWrite("merge_relations")
        moveCandidates(operation); afterWrite("merge_candidates")
        val transaction = CASH_TRANSACTIONS
        val current = row(transaction, source)
        if (current == null || current[transaction.STATE] != "draft" || current[transaction.DELETED_AT] != null) throw CashRelationFailure("relation_context_changed")
        update(transaction, source, mapOf("deleted_at" to context.now()), "row_revision"); afterWrite("merge_source_exit")
        val refs = map(merge["references"])
        return setOf(source, target) + maps(refs["issues"]).map { number(it["transaction_id"])!! } + maps(refs["relations"]).flatMap { relation ->
            listOf("primary_transaction_id", "secondary_transaction_id").mapNotNull { number(relation[it]) }
        }
    }
    private fun moveIssues(operation: Map<String, Any?>) {
        val merge = map(operation["merge_draft"]); val source = number(merge["source_transaction_id"])!!; val target = number(merge["target_transaction_id"])!!
        val resolved = (operation["resolved_issue_ids"] as List<*>).map { number(it)!! }.toSet()
        for (issue in maps(map(merge["references"])["issues"])) if (number(issue["transaction_id"]) == source && number(issue["id"]) in resolved)
            delete(CASH_TRANSACTION_ISSUES, number(issue["id"])!!)
        for (move in maps(map(merge["reference_moves"])["issues"])) update(CASH_TRANSACTION_ISSUES, number(move["id"])!!,
            mapOf("transaction_id" to target, "issue_key" to move["issue_key"], "pending_fields" to move["pending_fields"]), "revision")
        val pending = map(merge["pending_fields"]).toMutableMap()
        if (pending.isNotEmpty()) {
            val payload = pending.remove("source_payload")
            if ("source_payload" in map(merge["pending_fields"])) pending["_source_payload_present"] = true
            CashSourceWriteRepository(context).saveIssue(target, map(merge["reference_moves"])["pending_issue_key"], "source_update_pending",
                pendingSourcePayload = payload, pendingFields = pending)
        }
    }
    private fun moveRelations(operation: Map<String, Any?>) {
        val fields = listOf("primary", "secondary", "anchor").flatMap { listOf(it + "_transaction_id", it + "_component_id") } + listOf("primary_role", "secondary_role")
        for (relation in maps(map(operation["merge_draft"])["relations"])) update(TRANSACTION_RELATIONS, number(relation["id"])!!,
            relation.filterKeys { it in fields }, "row_revision")
    }
    private fun moveCandidates(operation: Map<String, Any?>) {
        val moves = map(map(operation["merge_draft"])["reference_moves"])
        for ((table, key, owner) in candidates) {
            val touched = linkedSetOf<Long>(); val planned = maps(moves[key])
            for (move in planned.filter { it["delete"] == true }) {
                val identifier = number(move["id"])!!
                row(table, identifier)?.let { current -> touched += number(current.get(owner.first))!!; delete(table, identifier) }
            }
            for (move in planned.filter { it["delete"] != true }) {
                val identifier = number(move["id"])!!; val current = row(table, identifier) ?: continue
                if (update(table, identifier, map(move["values"]))) touched += number(current.get(owner.first))!!
            }
            touched.forEach { bump(owner.second, it, owner.third) }
        }
    }
}
