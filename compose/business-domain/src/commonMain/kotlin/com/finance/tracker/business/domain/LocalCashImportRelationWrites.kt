package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Add decided file relations and B to the same source write set; final entry effects are composed separately. */
fun buildLocalCashImportRelationWrites(source: LedgerSnapshotDto, plan: JsonObject, sourceWrites: CashWriteSetDto,
    relationChoices: JsonArray = JsonArray(emptyList())): CashWriteSetDto =
    LocalRelationWrites(source, plan, sourceWrites, relationChoices).build()

private class LocalRelationWrites(source: LedgerSnapshotDto, private val plan: JsonObject,
    private val changes: CashWriteSetDto, choices: JsonArray) {
    private val parents = source.facts.cashTransactions.associateBy { it.id }
    private val parts = source.facts.cashTransactionComponents.associateBy { it.id }
    private fun text(reference: CashWriteReferenceDto): String = when (reference) {
        is ExistingCashWriteReferenceDto -> reference.id
        is NewCashWriteReferenceDto -> reference.ref
    }
    private val writtenParents = changes.transactions.associateBy { text(it.target.reference) }
    private val writtenParts = changes.transactions.flatMap { it.components }.associateBy { text(it.reference) }
    private val manual = choices.map { it.jsonObject.getValue("suggestion_id").jsonPrimitive.content }.toSet()
    private val empty = JsonObject(emptyMap())
    private val storedLimit = CashImportPreviewInteger.parse("9223372036854775808")

    fun build(): CashWriteSetDto {
        check(plan["can_commit"] == JsonPrimitive(true)) { "local_import_plan_not_committable" }
        check(changes.relations.isEmpty() && changes.relationGroups.isEmpty()) { "local_import_relations_already_composed" }
        val batches = linkedMapOf<String, MutableList<JsonObject>>()
        for (raw in plan.getValue("relation_groups").jsonArray) {
            val group = raw.jsonObject
            check(group["origin"] != JsonPrimitive("current")) { "local_import_existing_relation_command" }
            val key = if ("commit_group_id" in group) group.getValue("commit_group_id").jsonPrimitive.content
                else group.getValue("suggestion_id").jsonPrimitive.content
            batches.getOrPut(key) { mutableListOf() } += group
        }
        val relations = mutableListOf<CashRelationWriteDto>()
        val groups = mutableListOf<CashRelationWriteGroupDto>()
        for (batch in batches.values) for (group in batch) {
            val action = group.getValue("action").jsonPrimitive.content
            check(action in setOf("accept", "defer", "ignore")) { "local_import_invalid_relation_action" }
            val rows = group.getValue("edges").jsonArray.map { raw -> relation(group, raw.jsonObject) }
            check(rows.isNotEmpty()) { "local_import_empty_relation_group" }
            relations += rows
            groups += CashRelationWriteGroupDto(rows.map { it.target.reference },
                if (action == "accept") group["common_fields"]?.jsonObject ?: empty else empty)
        }
        return changes.copy(relations = relations, relationGroups = groups)
    }

    private fun parent(id: String): CashWriteVersionedReferenceDto = writtenParents[id]?.target ?: parents.getValue(id).let {
        CashWriteVersionedReferenceDto(ExistingCashWriteReferenceDto(it.id), it.rowRevision)
    }
    private fun part(id: String?): CashWriteReferenceDto? = id?.let {
        writtenParts[it]?.reference ?: ExistingCashWriteReferenceDto(parts.getValue(it).id)
    }

    private fun relation(group: JsonObject, edge: JsonObject): CashRelationWriteDto {
        val id = edge.getValue("id").jsonPrimitive.content
        val first = edge.getValue("primary_transaction_id").jsonPrimitive.content
        val second = edge.text("secondary_transaction_id")
        val firstPart = edge.text("primary_component_id"); val secondPart = edge.text("secondary_component_id")
        val anchor = group.getValue("anchors").jsonObject.getValue(id).takeIf { it != JsonNull }?.jsonPrimitive?.content
        val anchorParent = if (anchor == firstPart) first else checkNotNull(second) { "local_import_relation_anchor_missing" }
        val state = edge.getValue("state").jsonPrimitive.content
        val suggestion = group.getValue("suggestion_id").jsonPrimitive.content
        return CashRelationWriteDto(CashWriteVersionedReferenceDto(NewCashWriteReferenceDto("relation:$id"), null),
            edge.getValue("kind").jsonPrimitive.content, parent(first), second?.let(::parent), part(firstPart), part(secondPart),
            parent(anchorParent), part(anchor), edge.text("primary_role"), edge.text("secondary_role"), state,
            edge.getValue("active").jsonPrimitive.boolean, null, if (suggestion in manual) "manual" else "rule",
            group.getValue("evidence").jsonObject.getValue(id), edge.text("applied_amount") ?: "0",
            "", "", if (state == "draft") candidates(group, id, anchorParent) else emptyList())
    }

    private fun candidates(group: JsonObject, edgeId: String, anchorParent: String): List<CashRelationCandidateWriteDto> {
        val candidates = linkedMapOf<Pair<CashWriteReferenceDto, CashWriteReferenceDto?>, CashRelationCandidateWriteDto>()
        for (raw in group.getValue("candidates").jsonArray) {
            val choice = raw.jsonObject
            val edges = choice.getValue("edges").jsonArray.map { it.jsonObject }
            val variant = edges.firstOrNull { it.getValue("id").jsonPrimitive.content == edgeId } ?: continue
            if (edges.any { edge -> listOf("primary", "secondary").any { role ->
                edge.text("${role}_transaction_id")?.let { it !in writtenParents && CashImportPreviewInteger.parse(it) >= storedLimit } == true
            } }) continue
            for (role in listOf("primary", "secondary")) {
                val id = variant.text("${role}_transaction_id") ?: continue
                if (id == anchorParent) continue
                val written = writtenParents[id]
                val old = parents[id]
                if (written == null && old == null || old?.deletedAt != null) continue
                val target = parent(id)
                val component = part(variant.text("${role}_component_id"))
                val validity = if (component == null || (written?.values?.state ?: old!!.state) != "posted") "incomplete" else "valid"
                candidates[target.reference to component] = CashRelationCandidateWriteDto(target, component,
                    choice.getValue("evidence").jsonObject.getValue(edgeId), validity)
            }
        }
        return candidates.values.toList()
    }

    private fun JsonObject.text(key: String): String? = getValue(key).takeIf { it != JsonNull }?.jsonPrimitive?.content
}
