package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import com.finance.tracker.business.contracts.*
import com.finance.tracker.business.domain.prepareCashImportWriteSetCommonChoices
import java.math.BigInteger
import java.util.UUID
import kotlinx.serialization.json.*

/** First writes only, after bindings and C. The command must consume exact duplicate batches and B before calling. */
internal fun saveCashImportWriteSetFirstRelations(context: CashSqlContext, sources: CashImportWriteSetSourceResult,
    userId: String): Map<CashWriteReferenceDto, Long> {
    sources.requireOwner(context)
    val changes = sources.changes
    val batches = prepareCashImportWriteSetCommonChoices(changes, sources.before, sources.aliases)
    if (changes.relations.isEmpty()) return emptyMap()
    fun invalid(path: String): Nothing = throw CashPolicyError("invalid_field", listOf(path))
    for ((index, row) in changes.relations.withIndex()) {
        if (row.decisionSource !in setOf("manual", "rule") || row.state != "draft" && row.candidates.isNotEmpty()) invalid("relations[$index]")
    }
    fun parent(ref: CashWriteReferenceDto): Long = sources.transactions[ref] ?: when (ref) {
        is ExistingCashWriteReferenceDto -> ref.id.toLong()
        is NewCashWriteReferenceDto -> error("write_set.missing_source_identity")
    }
    fun part(ref: CashWriteReferenceDto?): String? = ref?.let {
        (sources.components[it] ?: when (it) {
            is ExistingCashWriteReferenceDto -> it.id.toLong()
            is NewCashWriteReferenceDto -> error("write_set.missing_source_component")
        }).toString()
    }
    val rows = changes.relations.associateBy { it.target.reference }
    val ordered = batches.flatten().flatMap { it.relations }.map(rows::getValue)
    val proofs = ordered.associate { it.target.reference to checkedRelationWriteEvidence(it.evidence) }
    val candidateProofs = ordered.associate { row -> row.target.reference to row.candidates.map { checkedRelationWriteEvidence(it.evidence) } }
    val calculationIds = ordered.mapIndexed { index, row -> row.target.reference to BigInteger.ONE.shiftLeft(64).add(index.toBigInteger()) }.toMap()
    val additions = ordered.map { row -> CashRelationGraphEdge(calculationIds.getValue(row.target.reference), row.kind,
        parent(row.primaryTransaction.reference).toString(), row.secondaryTransaction?.let { parent(it.reference).toString() },
        part(row.primaryComponent), part(row.secondaryComponent), row.primaryRole, row.secondaryRole, CashDecimalLiteral(row.appliedAmount), row.state, row.active) }
    val candidateIds = ordered.flatMap { it.candidates }.map { parent(it.transaction.reference) }.distinct()
    val parents = additions.flatMap { listOfNotNull(it.primaryTransactionId, it.secondaryTransactionId) }.map { it.toString().toLong() }.toSet()
    val graph = CashRelationContextService(context)
    val current = graph.load(parents + candidateIds)
    val prepared = graph.validate(current, additions = additions).relations.associateBy { strictCashInteger(it.id)!! }
    val endpoints = current.components.associateBy { it.componentId }
    for (row in ordered) {
        val anchor = endpoints[part(row.anchorComponent)] ?: continue
        val primaryAnchor = parent(row.anchorTransaction.reference) == parent(row.primaryTransaction.reference)
        for (candidate in row.candidates) if (candidate.validity == "valid") {
            val other = endpoints[part(candidate.component)] ?: continue
            val roles = if (primaryAnchor) inferCashRelationRoles(row.kind, anchor, other) else inferCashRelationRoles(row.kind, other, anchor)
            if (row.primaryRole != null && row.primaryRole != roles.first || row.secondaryRole != null && row.secondaryRole != roles.second)
                relationFailure("relation_role_unproven")
        }
    }
    for (row in ordered) if (row.state == "confirmed") {
        val actual = prepared.getValue(calculationIds.getValue(row.target.reference)).appliedAmount
        if (cashRelationDecimal(actual)?.arithmeticValue()?.compareTo(CashAmount.parse(row.appliedAmount).arithmeticValue()) != 0)
            throw CashPolicyError("relation_amount_invalid", listOf("relations"))
    }
    val table = CASH_TRANSACTIONS
    val revisions = candidateIds.chunked(400).flatMap { ids -> context.sql.select(table.ID, table.ROW_REVISION).from(table)
        .where(table.WORKSPACE_ID.eq(context.workspace).and(table.ID.`in`(ids)).and(table.DELETED_AT.isNull)).fetch() }
        .associate { it.value1()!! to it.value2()!! }
    applyCashImportWriteSetFirstCommonChoices(context, changes, sources.before, sources.aliases, ::parent)
    val repository = CurrentCashRelationWriteRepository(context)
    val result = linkedMapOf<CashWriteReferenceDto, Long>()
    for (batch in batches) {
        val members = batch.flatMap { it.relations }.map(rows::getValue)
        val groupId = if (members.size > 1) UUID.randomUUID().toString() else null
        for (row in members) {
            val edge = prepared.getValue(calculationIds.getValue(row.target.reference))
            val saved = repository.add(cashImportEdgeMap(edge).filterKeys { it != "id" && it != "applied_amount" } + mapOf(
                "anchor_transaction_id" to parent(row.anchorTransaction.reference).toString(), "anchor_component_id" to part(row.anchorComponent),
                "decision_group_id" to groupId, "decision_source" to row.decisionSource, "evidence" to proofs.getValue(row.target.reference),
                "applied_amount" to row.appliedAmount, "rule_id" to row.ruleId, "decision_reason" to row.decisionReason,
                "created_by" to userId, "decided_by" to if (row.state == "draft") "" else userId,
                "decided_at" to if (row.state == "draft") null else context.now()))
            val id = saved.getValue("id") as Long
            if (row.state == "draft") {
                val values = row.candidates.mapIndexed { index, candidate -> mapOf(
                    "candidate_transaction_id" to parent(candidate.transaction.reference), "candidate_component_id" to part(candidate.component),
                    "expected_row_revision" to (revisions[parent(candidate.transaction.reference)] ?: throw CashPolicyError("reference_changed", listOf("relations.candidates"))),
                    "evidence" to candidateProofs.getValue(row.target.reference)[index], "validity" to candidate.validity) }
                val actual = repository.setCandidates(id, values, saved.getValue("row_revision"))
                @Suppress("UNCHECKED_CAST") val stored = actual.getValue("candidates") as List<Map<String, Any?>>
                val expected = values.associate { it["candidate_transaction_id"].toString() to it["candidate_component_id"] to it["validity"] }
                if (stored.any { expected[it["candidate_transaction_id"].toString() to it["candidate_component_id"]?.toString()] != it["validity"] })
                    throw CashPolicyError("relation_candidate_changed", listOf("relations.candidates"))
            }
            result[row.target.reference] = id
        }
    }
    return result
}

internal fun checkedRelationWriteEvidence(value: JsonElement): Map<String, Any?> {
    if (value !is JsonObject) relationFailure("relation_data_invalid")
    val pending = ArrayDeque<Pair<JsonElement, Int>>()
    pending.addLast(value to 0)
    while (pending.isNotEmpty()) {
        val (item, depth) = pending.removeLast()
        if (depth > 64) relationFailure("relation_data_invalid")
        when (item) {
            is JsonObject -> item.values.forEach { pending.addLast(it to depth + 1) }
            is JsonArray -> item.forEach { pending.addLast(it to depth + 1) }
            is JsonPrimitive -> Unit
        }
    }
    @Suppress("UNCHECKED_CAST")
    return cashSourceValue(value) as Map<String, Any?>
}
