package com.finance.tracker.business.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** The original duplicate fast path compares issue reasons, not stored issue bodies or candidate observations. */
fun cashImportIssuesUnchanged(operation: Map<String, Any?>, truthy: (Any?) -> Boolean): Boolean {
    fun reasons(key: String) = (operation.getValue(key) as List<*>).map { raw ->
        val issue = raw as Map<*, *>
        issue["code"] to issue["field_path"]
    }.toSet()
    return reasons("expected_issues") == reasons("issues") && !truthy(operation["pending_fields"]) &&
        !truthy(operation["candidate_ids"]) && !truthy(operation["candidate_set_ref"]) && !truthy(operation["resolved_issue_ids"])
}

internal fun projectedCashImportIssuesUnchanged(operation: JsonObject, truthy: (Any?) -> Boolean): Boolean {
    fun reasons(key: String) = operation.getValue(key).jsonArray.map { raw ->
        val issue = raw.jsonObject
        decodeLocalCashValue(issue["code"]) to decodeLocalCashValue(issue["field_path"])
    }.toSet()
    fun present(key: String): Boolean = when (val value = operation[key]) {
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        else -> truthy(decodeLocalCashValue(value))
    }
    return reasons("expected_issues") == reasons("issues") && !present("pending_fields") &&
        !present("candidate_ids") && !present("candidate_set_ref") && !present("resolved_issue_ids")
}

/** Select the original document groups before writing any source rows; no file parsing or ledger reads. */
fun cashImportSourceEvidenceGroups(files: List<ParsedImportFile>, sources: PreparedCashSourcePlan,
    plan: Map<String, Any?>): List<List<ParsedImportFile>> {
    @Suppress("UNCHECKED_CAST")
    fun maps(value: Any?) = value as List<Map<String, Any?>>
    val conflicts = localCashSourceResolver.conflictingSourceDocumentPages(sources.rows.rows.values.toList(), sources.records, sources.evidence)
        .map { DocumentNamespace(it.sourceType, it.parserVersion, it.documentKey) }.toSet()
    val skipped = maps(plan.getValue("row_decisions")).filter { it["planned_action"] == "skip" }.map { it["row_ref"] }.toSet()
    val references = sources.rows.rows.entries.filter { it.value.coverage.documentKey != null }.groupBy { (_, row) ->
        DocumentNamespace(row.sourceType, row.parserVersion, row.coverage.documentKey)
    }.mapValues { (_, rows) -> rows.map { it.key }.toSet() }
    val excluded = conflicts.filter { skipped.containsAll(references[it].orEmpty()) }.toMutableSet()
    val needed = mutableSetOf<String>()
    for (operation in maps(plan.getValue("operations"))) {
        (operation["transaction_id"] as String?)?.let(needed::add)
        (operation["candidate_ids"] as? List<*>)?.forEach { needed += it as String }
    }
    val candidateSets = if ("source_candidate_sets" in plan) plan.getValue("source_candidate_sets") else emptyMap<String, Any?>()
    for (value in (candidateSets as Map<*, *>).values)
        for (id in value as List<*>) needed += id as String
    excluded -= sources.records.filter { it.transactionId in needed }.flatMap { it.documentEvidence }.map { it.namespace }.toSet()
    val groups = linkedMapOf<DocumentNamespace, MutableList<ParsedImportFile>>()
    for (file in files) for (evidence in CashSourceRows.fileDocumentEvidence(listOf(file)))
        if (evidence.coverage.documentKey != null && evidence.namespace !in excluded) groups.getOrPut(evidence.namespace) { mutableListOf() }.add(file)
    return groups.values.toList()
}
