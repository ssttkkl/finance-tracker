package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.LedgerSnapshotDto
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Assemble initial preview state from one snapshot/context and validated operations, without IO or writes. */
fun buildLocalCashImportPreviewSnapshot(
    source: LedgerSnapshotDto,
    context: CashRelationReadContext<Long, Instant>,
    operations: JsonArray,
    rows: JsonArray,
    now: () -> Instant,
): CashImportPreviewSnapshot<CashImportPreviewInteger, Instant> {
    @Suppress("UNCHECKED_CAST") val decodedOperations = decodeLocalCashValue(operations) as List<Map<String, Any?>>
    @Suppress("UNCHECKED_CAST") val decodedRows = decodeLocalCashValue(rows) as List<Map<String, Any?>>
    return buildLocalCashImportPreviewSnapshot(source, context, decodedOperations, decodedRows, now)
}

/** Consume computed local operations directly; do not serialize their amount/time types before fingerprinting. */
fun buildLocalCashImportPreviewSnapshot(
    source: LedgerSnapshotDto,
    context: CashRelationReadContext<Long, Instant>,
    operations: List<Map<String, Any?>>,
    rows: List<Map<String, Any?>>,
    now: () -> Instant,
): CashImportPreviewSnapshot<CashImportPreviewInteger, Instant> {
    val projectionValues = CashProjectionValues<CashImportPreviewInteger, Instant>({ it.positive },
        { try { formatPythonCashTimestamp(it); true } catch (_: IllegalArgumentException) { false } }, ::pythonCashUpper)
    val projections = CashProjectionRules(projectionValues, ::localCashInstantText)
    val relationGraph = CashRelationGraphRules(projectionValues, projections, ::localCashPreviewInteger, CashImportPreviewInteger::parse)
    fun promote(fact: CashProjectionFact<Long, Instant>) = CashProjectionFact.create(projectionValues,
        CashImportPreviewInteger.parse(fact.id.toString()), fact.accountId, fact.occurredAt, fact.amount?.toString(), fact.currency,
        fact.counterparty, fact.categoryId, fact.note, fact.sourceType, fact.recordId, fact.fundingRelationId, fact.cashGranularity,
        fact.state, fact.createdAt, fact.needsAttention, fact.recordType, fact.recordSubtype, fact.stableSourceKey, fact.recordOrigin)
    val graph = with(context) { CashRelationReadContext(seedIds, decisionGroupIds, transactionIds, facts.map(::promote),
        components, relations, jointMembers, transactionRevisions, activeTransactionIds, componentVersions, relationRevisions,
        issueVersions, entryVersions, relationGroups, fundingVersions, fundingComponentIds, sourceOrderKeys) }
    val values = readLocalCashImportPreviewValues(source, context)
    val current = values.keys.map(String::toLong).toSet()
    @Suppress("UNCHECKED_CAST")
    fun copies(input: List<Map<String, Any?>>) = copyLocalCashValue(input) as MutableList<MutableMap<String, Any?>>
    val result = CashImportPreviewSnapshot(graph, values,
        graph.facts.associateByTo(linkedMapOf()) { it.id.toString() },
        graph.components.associateByTo(linkedMapOf()) { it.componentId as String },
        graph.relations.associateByTo(linkedMapOf()) { localCashPreviewInteger(it.id)!! },
        graph.fundingVersions.associateByTo(linkedMapOf()) { it.id }, linkedMapOf(), linkedMapOf(),
        linkedSetOf(), linkedSetOf(), copies(operations), copies(rows), projectionValues, ::localCashPreviewInteger,
        relationGraph::build, { facts, relations -> projections.build(facts, relations) })
    fun fact(identifier: String, value: Map<String, Any?>, attention: Boolean): CashProjectionFact<CashImportPreviewInteger, Instant> =
        CashProjectionFact.create(projectionValues, CashImportPreviewInteger.parse(identifier),
            (value["account_id"] as? String)?.toLong(), value["occurred_at"] as? Instant,
            value["amount"]?.toString(), value["currency"] as? String, value["counterparty"] as? String ?: "",
            value["category_id"] as? String, value["note"] as? String ?: "", value["source_type"] as? String,
            value["record_id"] as? String ?: "", cashGranularity = value["cash_granularity"] as String,
            state = value["state"] as String, createdAt = value["created_at"] as Instant, needsAttention = attention,
            recordType = value["record_type"] as? String, recordSubtype = value["record_subtype"] as? String,
            stableSourceKey = (value["stable_source_key"] as? List<*>)?.map { it.toString() }.orEmpty(),
            recordOrigin = value["record_origin"]?.toString() ?: "legacy")
    for ((id, value) in values) if (value["state"] == "draft" && id.toLong() in graph.activeTransactionIds)
        result.facts[id] = fact(id, value, true)
    CashImportPreviewOverlayRules(LocalCashRecordValues, now,
        { kind, items -> CashImportPreviewInteger.parse(localCashImportVirtualId(kind, items)) }, ::fact).apply(result)
    val (members, entries) = readLocalPreviewEntries(source, current)
    applyCashImportPreviewEntryPublic(result, members, entries, ::decodeLocalCashValue)
    return result
}

private fun readLocalPreviewEntries(source: LedgerSnapshotDto, current: Set<Long>): Pair<Map<Long, CashEntryMember>, Map<String, CashEntry>> {
    fun textList(value: JsonElement) = value.jsonArray.map { check(it is JsonPrimitive && it.isString); it.content }
    val members = source.facts.cashEntryMembers.filter { it.transactionId.toLong() in current }.associate { member ->
        val id = member.transactionId.toLong()
        id to CashEntryMember(id, member.entryId, member.standaloneValues.jsonObject, textList(member.standaloneManualFields))
    }
    val entryIds = members.values.map { it.entryId }.toSet()
    val entries = source.facts.cashEntries.filter { it.id in entryIds }.associate { entry ->
        entry.id to CashEntry(entry.id, entry.rootTransactionId.toLong(), entry.identityTransactionId.toLong(), entry.revision.toLong(),
            buildJsonObject {
                put("category_id", entry.categoryId?.let(::JsonPrimitive) ?: JsonNull)
                put("note", entry.note); put("counterparty", entry.counterparty)
                put("counterparty_account", entry.counterpartyAccount); put("counterparty_account_attrs", entry.counterpartyAccountAttrs)
            }, textList(entry.manualFields), entry.splitInherit)
    }
    return members to entries
}
