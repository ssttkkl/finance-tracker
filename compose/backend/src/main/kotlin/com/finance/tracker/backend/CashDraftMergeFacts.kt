package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.*
import java.time.Instant
import kotlinx.serialization.json.JsonElement
import org.jooq.Condition
import org.jooq.Table

internal fun cashMergeWire(value: Any?): Any? = when (value) {
    is Instant -> ParsedImportCodec.timeText(value)
    is CashAmount -> value.arithmeticValue().toPlainString()
    is CashStoredDecimal -> value.text.toBigDecimal().toPlainString()
    is JsonElement -> cashSourceValue(value)
    is Map<*, *> -> value.entries.associate { it.key.toString() to cashMergeWire(it.value) }
    is List<*> -> value.map(::cashMergeWire)
    else -> value
}

internal class CashDraftMergeFacts(private val context: CashSqlContext) {
    fun rows(table: Table<*>, condition: Condition): List<Map<String, Any?>> = context.sql.selectFrom(table)
        .where(table.field("workspace_id", String::class.java)!!.eq(context.workspace)).and(condition)
        .orderBy(table.field("id")).fetch().map { row -> row.intoMap().mapValues { (_, value) -> when (value) {
            is JsonElement -> cashSourceValue(value)
            is CashStoredDecimal -> CashAmount.parse(value.text)
            else -> value
        } } }

    fun references(source: Long, target: Long): Map<String, List<Map<String, Any?>>> {
        val parents = listOf(source, target)
        val issue = CASH_TRANSACTION_ISSUES; val candidate = CASH_ISSUE_CANDIDATES
        val edge = TRANSACTION_RELATIONS; val related = CASH_RELATION_CANDIDATES
        val issues = rows(issue, issue.TRANSACTION_ID.`in`(parents)).associateByTo(linkedMapOf()) { it.getValue("id").toString().toLong() }
        val candidates = linkedMapOf<Long, Map<String, Any?>>()
        for (batch in issues.keys.chunked(400)) for (row in rows(candidate, candidate.ISSUE_ID.`in`(batch))) candidates[row.getValue("id").toString().toLong()] = row
        for (row in rows(candidate, candidate.CANDIDATE_TRANSACTION_ID.`in`(parents))) candidates[row.getValue("id").toString().toLong()] = row
        val extraIssues = candidates.values.map { it.getValue("issue_id").toString().toLong() }.toSet() - issues.keys
        for (batch in extraIssues.chunked(400)) for (row in rows(issue, issue.ID.`in`(batch))) issues[row.getValue("id").toString().toLong()] = row
        for (batch in issues.keys.chunked(400)) for (row in rows(candidate, candidate.ISSUE_ID.`in`(batch))) candidates[row.getValue("id").toString().toLong()] = row
        val reverse = rows(related, related.CANDIDATE_TRANSACTION_ID.`in`(parents))
        val relations = rows(edge, edge.PRIMARY_TRANSACTION_ID.`in`(parents).or(edge.SECONDARY_TRANSACTION_ID.`in`(parents))
            .or(edge.ANCHOR_TRANSACTION_ID.`in`(parents))).associateByTo(linkedMapOf()) { it.getValue("id").toString().toLong() }
        val extraRelations = reverse.map { it.getValue("relation_id").toString().toLong() }.toSet() - relations.keys
        for (batch in extraRelations.chunked(400)) for (row in rows(edge, edge.ID.`in`(batch))) relations[row.getValue("id").toString().toLong()] = row
        val groups = relations.values.mapNotNull { it["decision_group_id"] as? String }.toSet()
        for (batch in groups.chunked(400)) for (row in rows(edge, edge.DECISION_GROUP_ID.`in`(batch))) relations[row.getValue("id").toString().toLong()] = row
        val relationCandidates = linkedMapOf<Long, Map<String, Any?>>()
        for (batch in relations.keys.chunked(400)) for (row in rows(related, related.RELATION_ID.`in`(batch))) relationCandidates[row.getValue("id").toString().toLong()] = row
        return mapOf("issues" to issues.toSortedMap().values.toList(), "issue_candidates" to candidates.toSortedMap().values.toList(),
            "relations" to relations.toSortedMap().values.toList(), "relation_candidates" to relationCandidates.toSortedMap().values.toList())
    }
}
