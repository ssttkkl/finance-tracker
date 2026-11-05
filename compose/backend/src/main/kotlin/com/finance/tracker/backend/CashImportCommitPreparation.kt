package com.finance.tracker.backend

import com.finance.tracker.backend.persistence.generated.tables.references.CASH_TRANSACTIONS
import java.math.BigInteger

internal class CashImportCommitPreparation(private val context: CashSqlContext) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private val candidateCache = mutableSetOf<String>()

    fun candidateOperation(operation: Map<String, Any?>, sets: Map<String, List<String>>): Map<String, Any?> {
        val reference = operation["candidate_set_ref"] as String? ?: return operation
        require(!pythonTruthy(sourceJson(operation["candidate_ids"])) && reference in sets) { "source_candidate_set_invalid" }
        val candidates = sets.getValue(reference)
        if (reference !in candidateCache) {
            val found = candidates.chunked(500).flatMap { batch -> context.sql.select(CASH_TRANSACTIONS.ID).from(CASH_TRANSACTIONS)
                .where(CASH_TRANSACTIONS.WORKSPACE_ID.eq(context.workspace).and(CASH_TRANSACTIONS.ID.`in`(batch.map(String::toLong)))
                    .and(CASH_TRANSACTIONS.DELETED_AT.isNull)).fetch().map { it.value1().toString() } }.toSet()
            if (found != candidates.toSet()) throw CashSourceBindingConflict("source_candidate_changed")
            candidateCache += reference
        }
        return operation + ("candidate_ids" to candidates)
    }

    fun newBatches(operations: List<Map<String, Any?>>, sources: Map<String, SourceOccurrence>, beforeBatch: (List<Map<String, Any?>>) -> Unit = {}): Map<String, CashRecordWriteResult> {
        val eligible = operations.mapNotNull { operation ->
            val updates = maps(operation["source_updates"])
            val rows = updates.filter { it["bind"] == true }.map { sources.getValue(it["row_ref"] as String) }
            if (operation["planned_action"] == "create_posted" && operation["transaction_id"] == null && (operation["issues"] as Collection<*>).isEmpty() &&
                map(operation["pending_fields"]).isEmpty() && rows.isNotEmpty() && rows.all { it.coverage.documentKey == null && it.documentPage == null } && updates.none { it["uncertain"] == true }) operation to rows else null
        }
        if (eligible.size < 20) return emptyMap()
        val result = linkedMapOf<String, CashRecordWriteResult>()
        for (batch in eligible.chunked(100)) {
            beforeBatch(batch.map { it.first })
            val saved = CashImportNewRowsRepository(context).insert(batch)
            for ((index, entry) in batch.withIndex()) result[entry.first["operation_ref"] as String] = saved[index]
        }
        return result
    }

    fun saveEvidence(files: List<ParsedImportFile>, sources: PreparedCashSourcePlan, plan: Map<String, Any?>, beforeGroup: (List<ParsedImportFile>) -> Unit = {}) {
        val conflicts = CashSourceResolver.conflictingSourceDocumentPages(sources.rows.rows.values.toList(), sources.records, sources.evidence)
            .map { DocumentNamespace(it.sourceType, it.parserVersion, it.documentKey) }.toSet()
        val skipped = maps(plan["row_decisions"]).filter { it["planned_action"] == "skip" }.map { it["row_ref"] }.toSet()
        val references = sources.rows.rows.entries.filter { it.value.coverage.documentKey != null }.groupBy { (_, row) ->
            DocumentNamespace(row.sourceType, row.parserVersion, row.coverage.documentKey)
        }.mapValues { (_, rows) -> rows.map { it.key }.toSet() }
        val excluded = conflicts.filter { skipped.containsAll(references[it].orEmpty()) }.toMutableSet()
        val needed = mutableSetOf<String>()
        for (operation in maps(plan["operations"])) {
            (operation["transaction_id"] as String?)?.let(needed::add)
            (operation["candidate_ids"] as? List<*>)?.forEach { needed += it as String }
        }
        for (values in map(plan.getOrDefault("source_candidate_sets", emptyMap<String, Any?>())).values) for (id in values as List<*>) needed += id as String
        excluded -= sources.records.filter { it.transactionId in needed }.flatMap { it.documentEvidence }.map { it.namespace }.toSet()
        val groups = linkedMapOf<DocumentNamespace, MutableList<ParsedImportFile>>()
        for (file in files) for (evidence in CashSourceRows.fileDocumentEvidence(listOf(file)))
            if (evidence.coverage.documentKey != null && evidence.namespace !in excluded) groups.getOrPut(evidence.namespace) { mutableListOf() }.add(file)
        for (members in groups.values) {
            beforeGroup(members)
            CashSourceDocumentWriteRepository(context).saveEmptyPages(members)
        }
    }

    companion object {
        fun frozenCandidateSets(raw: Any?): Map<String, List<String>> {
            fun invalid(): Nothing = throw IllegalArgumentException("source_candidate_set_invalid")
            val values = raw as? Map<*, *> ?: invalid()
            if (values.size > 100_000) invalid()
            return values.entries.associate { (key, rawCandidates) ->
                if (key !is String || !Regex("[0-9a-f]{64}").matches(key) || rawCandidates !is List<*> || rawCandidates.size !in 1..100_000) invalid()
                val candidates = rawCandidates.map { id ->
                    if (id !is String || !Regex("[1-9][0-9]{0,18}").matches(id) || BigInteger(id) >= BigInteger.ONE.shiftLeft(63)) invalid()
                    id
                }
                if (candidates.toSet().size != candidates.size || sourceDigest("cash-source-candidates-v1", candidates) != key) invalid()
                key to candidates
            }
        }
    }
}
