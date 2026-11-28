package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Compare complete review results and writes. Choice recovery, discovery and retry authorization remain with the caller. */
fun localCashImportConclusion(source: LedgerSnapshotDto, plan: JsonObject, changes: CashWriteSetDto): String =
    localCashImportConclusion(source, plan, changes, trustedPlan = false)

internal fun localCashImportConclusion(
    source: LedgerSnapshotDto, plan: JsonObject, changes: CashWriteSetDto, trustedPlan: Boolean,
): String {
    val values = CashImportConclusionValues(source.facts, changes)
    return hashCashImportConclusion(source.workspaceId, { values.forSerialization(trustedPlan) }) { append ->
        PlanConclusion(source.facts, plan, values).writeNormalized(append)
    }
}

internal val fullConclusionJson = Json { encodeDefaults = true }

internal fun localCashImportChoiceKeys(source: LedgerSnapshotDto, plan: JsonObject, changes: CashWriteSetDto): JsonObject =
    PlanConclusion(source.facts, plan, CashImportConclusionValues(source.facts, changes)).choiceKeys()

private class PlanConclusion(private val facts: LedgerFactsDto, private val plan: JsonObject, private val values: CashImportConclusionValues) {
    private val participants = linkedSetOf<String>()
    private val operations = plan.getValue("operations").jsonArray.reviewObjects()
    private val references = plan.getValue("operations").jsonArray.frozenOperationReferences()
    private val operationParents = if (references == null)
        operations.associate { it.text("operation_ref")!! to it.text("preview_transaction_id")!! }
    else emptyMap()
    private fun operationParent(ref: String): String = references?.let {
        it.parentsByPosition[it.operationPositions.getValue(ref)]
    } ?: operationParents.getValue(ref)
    private val rowParents = references?.rowParents ?: operations.flatMap { operation -> operation.getValue("row_refs").jsonArray.map {
        it.jsonPrimitive.content to operation.text("preview_transaction_id")!!
    } }.toMap()
    private val oldRelations = facts.transactionRelations.associateBy { it.id }
    private val relationValues = mutableMapOf<String, JsonObject>()
    private val relationKeys = mutableMapOf<String, JsonPrimitive>()
    private val oldFunding = facts.cashInvestmentFundingRelations.associateBy { it.id }
    private val sourceGroups = plan.objects("row_decisions").filter { it.text("source_group_ref") != null }
        .groupBy { it.text("source_group_ref")!! }.mapValues { (_, rows) -> ordered(rows.map { it.getValue("row_ref") }) }
    private val groups = plan.objects("relation_groups")
    private val normalizedGroups = groups.map(::group)
    private val groupKeys = groups.zip(normalizedGroups).flatMap { (raw, normalized) ->
        listOfNotNull(raw.text("suggestion_id"), raw.text("source_suggestion_id")).map { it to JsonPrimitive(values.hash(normalized)) }
    }.toMap()

    fun choiceKeys(): JsonObject = buildJsonObject {
        put("relations", JsonObject(groups.associate { raw ->
            val candidates = raw.objects("candidates")
            val options = candidates.map { option(it, true) }
            val key = buildJsonObject {
                put("origin", raw["origin"] ?: JsonNull)
                put("allowed_actions", ordered(raw.getValue("allowed_actions").jsonArray))
                if (options.isNotEmpty()) put("candidates", ordered(options))
                else put("edges", ordered(raw.objects("edges").map { JsonObject(edge(it) - setOf("state", "applied_amount")) }))
            }
            raw.text("suggestion_id")!! to buildJsonObject {
                put("group", values.hash(key))
                put("candidates", JsonObject(candidates.zip(options).associate { (candidate, normalized) ->
                    candidate.text("candidate_id")!! to JsonPrimitive(values.hash(normalized))
                }))
            }
        }))
        put("entries", JsonObject(plan.getValue("entry_targets").jsonObject.mapValues { (_, target) ->
            JsonPrimitive(values.hash(ordered(target.jsonObject.getValue("parent_ids").jsonArray.map { parent(it.jsonPrimitive.content) })))
        }))
    }

    fun writeNormalized(append: (Int) -> Unit) {
        val stale by lazy {
            plan.getValue("invalidated_relation_choices").jsonObject.mapValues { (_, raw) -> staleChoice(raw.jsonObject) }
        }
        val fields = mapOf<String, () -> JsonElement>(
            "can_commit" to { plan.getValue("can_commit") },
            "summary" to { plan.getValue("summary") },
            "files" to { plan.getValue("file_results") },
            "rows" to { ordered(plan.objects("row_decisions").map(::row)) },
            "processing" to { ordered(plan.objects("processing_details").map(::row)) },
            "operations" to { ordered(operations.map(::operation)) },
            "groups" to { ordered(normalizedGroups) },
            "failures" to { ordered(plan.objects("relation_failures").map(::failure)) },
            "invalidated" to { ordered(plan.objects("invalidated_choices").map { marker ->
                val reference = marker.text("reference") ?: marker.text("suggestion_id")
                JsonObject(marker.filterKeys { it !in setOf("reference", "suggestion_id", "candidate_id") } +
                    (stale[reference]?.let { mapOf("source" to it) } ?: emptyMap()))
            }) },
            "invalidated_sources" to { ordered(stale.values.toList()) },
            "type_effects" to { JsonObject(plan.getValue("type_effects").jsonObject.mapValues { (key, raw) -> when (key) {
                "removed_relation_ids" -> ordered(raw.jsonArray.map { relationKey(it.jsonPrimitive.content) })
                "removed_funding_ids" -> ordered(raw.jsonArray.map { funding(oldFunding.getValue(it.jsonPrimitive.content)) })
                else -> raw
            } }) },
            "items" to { ordered(plan.objects("items").map { item ->
                val detail = plan.getValue("item_details").jsonObject.getValue(item.text("preview_item_id")!!).jsonObject
                JsonObject(item.filterKeys { it != "preview_item_id" } + mapOf(
                    "display" to financial(item.getValue("display").jsonObject),
                    "member_refs" to references(item.getValue("member_refs")), "detail" to detail(detail)))
            }) },
            "monthly" to { ordered(plan.objects("monthly_source").map(::financial)) },
            "details" to { ordered(plan.getValue("detail_values").jsonObject.map { (id, fields) ->
                buildJsonObject { put("parent", parent(id)); put("values", financial(fields.jsonObject)) }
            }) },
            "source_candidates" to { ordered(plan.getValue("source_candidate_sets").jsonObject.values.map { candidates ->
                ordered(candidates.jsonArray.map { parent(it.jsonPrimitive.content) })
            }) },
        )
        writeCashImportReviewConclusion(fields, ::participantFacts, append)
    }

    private fun parent(id: String): JsonElement { participants += id; return values.parent(id) }
    private fun ordered(items: List<JsonElement>) = values.ordered(items)
    private fun references(raw: JsonElement): JsonArray = ordered(raw.jsonArray.map { item ->
        val value = item.jsonObject
        value.text("row_ref")?.let { rowParents[it]?.let(::parent) } ?: value.text("transaction_id")?.let(::parent) ?: value
    })
    private fun relation(id: String): JsonObject = relationValues.getOrPut(id) {
        val raw = fullConclusionJson.encodeToJsonElement(oldRelations.getValue(id)).jsonObject
        edge(raw)
    }
    private fun relationKey(id: String): JsonPrimitive = relationKeys.getOrPut(id) { JsonPrimitive(values.hash(relation(id))) }
    private fun edge(raw: JsonObject): JsonObject = JsonObject(raw.filterKeys {
        it !in setOf("id", "row_revision", "decision_group_id", "created_at", "created_by", "decided_at", "decided_by")
    }.mapValues { (key, value) -> when {
        value == JsonNull -> value
        key in setOf("primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id") -> parent(value.jsonPrimitive.content)
        key in setOf("primary_component_id", "secondary_component_id", "anchor_component_id") -> values.part(value.jsonPrimitive.content)
        key == "applied_amount" -> JsonPrimitive(values.decimal(value.jsonPrimitive.content))
        else -> value
    } })

    private fun option(raw: JsonObject, candidate: Boolean): JsonObject {
        val edges = raw.objects("edges")
        val edgeKeys = edges.associate { it.text("id")!! to JsonPrimitive(values.hash(edge(it))) }
        return JsonObject(raw.filterKeys { it !in setOf("candidate_id", "suggestion_id", "source_suggestion_id", "commit_group_id", "choice_generation", "dependency_parent_ids") }.mapValues { (key, value) ->
            when (key) {
                "edges" -> ordered(edges.map(::edge))
                "evidence", "anchors" -> ordered(value.jsonObject.map { (id, item) -> buildJsonObject {
                    put("edge", edgeKeys.getValue(id))
                    put("value", if (key == "anchors" && item != JsonNull) values.part(item.jsonPrimitive.content) else item)
                } })
                "parent_ids", "old_parent_ids" -> ordered(value.jsonArray.map { parent(it.jsonPrimitive.content) })
                "member_refs" -> references(value)
                "relation_ids" -> ordered(value.jsonArray.map { relationKey(it.jsonPrimitive.content) })
                "display" -> financial(value.jsonObject)
                "candidates" -> if (candidate) error("cash_import_conclusion_nested_candidates") else ordered(value.jsonArray.map { option(it.jsonObject, true) })
                else -> value
            }
        })
    }

    private fun group(raw: JsonObject): JsonObject {
        val normalized = option(raw, false)
        val selected = raw.text("candidate_id")?.let { id ->
            val candidate = raw.objects("candidates").single { it.text("candidate_id") == id }
            JsonPrimitive(values.hash(option(candidate, true)))
        } ?: JsonNull
        return JsonObject(normalized + ("selected_candidate" to selected))
    }

    private fun operation(raw: JsonObject): JsonObject = buildJsonObject {
        put("parent", parent(raw.text("preview_transaction_id")!!))
        for (key in listOf("row_refs", "occurrence_key", "planned_action", "manual_fields", "issues", "display_retained_issues",
            "pending_fields", "type_changed", "keep_existing", "source_updates", "source_explanation")) raw[key]?.let { put(key, it) }
        raw["field_diffs"]?.let { put("field_diffs", diffs(it)) }
        val candidates = raw.text("candidate_set_ref")?.let { plan.getValue("source_candidate_sets").jsonObject.getValue(it) }
            ?: raw["candidate_ids"] ?: JsonArray(emptyList())
        put("candidates", ordered(candidates.jsonArray.map { parent(it.jsonPrimitive.content) }))
    }

    private fun row(raw: JsonObject): JsonObject = JsonObject(raw.filterKeys { it !in setOf("operation_ref", "source_group_ref") }.mapValues { (key, value) ->
        when {
            value == JsonNull -> value
            key == "transaction_id" -> parent(value.jsonPrimitive.content)
            key == "member_refs" -> references(value)
            key == "field_diffs" -> diffs(value)
            else -> value
        }
    } + (raw.text("operation_ref")?.let { mapOf("operation" to parent(operationParent(it))) } ?: emptyMap()) +
        (raw.text("source_group_ref")?.let { mapOf("source_group" to sourceGroups.getValue(it)) } ?: emptyMap()))

    private fun staleChoice(raw: JsonObject): JsonObject = buildJsonObject {
        val choice = raw.getValue("choice").jsonObject
        put("members", references(raw.getValue("member_refs")))
        put("choice", JsonObject(choice.filterKeys { it !in setOf("suggestion_id", "candidate_id") }))
        put("candidate_was_selected", !choice.text("candidate_id").isNullOrEmpty())
    }

    private fun failure(raw: JsonObject): JsonObject = JsonObject(raw.mapValues { (key, value) -> when (key) {
        "suggestion_id" -> if (value == JsonNull) value else groupKeys[value.jsonPrimitive.content] ?: value
        "parent_ids" -> ordered(value.jsonArray.map { parent(it.jsonPrimitive.content) })
        else -> value
    } })

    private fun detail(raw: JsonObject): JsonObject = JsonObject(raw.filterKeys { it != "relation_choices" }.mapValues { (key, value) -> when (key) {
        "entry" -> JsonObject(value.jsonObject.filterKeys { it != "edit_ref" })
        "members", "relation_members" -> ordered(value.jsonArray.map { item ->
            val member = item.jsonObject
            val identity = references(JsonArray(listOf(JsonObject(member.filterKeys { it in setOf("row_ref", "transaction_id") })))).single()
            JsonObject(member.filterKeys { it !in setOf("row_ref", "transaction_id", "fields_ref", "row_revision") } + ("parent" to identity))
        })
        "field_diffs" -> diffs(value)
        "relation_effects" -> ordered(value.jsonArray.map { item ->
            val effect = item.jsonObject
            JsonObject(effect.filterKeys { it != "relation_id" } + mapOf("member_refs" to references(effect.getValue("member_refs")),
                "relation" to relationKey(effect.text("relation_id")!!)))
        })
        else -> value
    } })

    private fun diffs(raw: JsonElement): JsonArray = ordered(raw.jsonArray.map { item ->
        val diff = item.jsonObject; val field = diff.text("field")
        JsonObject(diff.mapValues { (key, value) -> when {
            key == "transaction_id" && value != JsonNull -> parent(value.jsonPrimitive.content)
            key in setOf("current", "incoming", "effective") && field != null -> field(field, value)
            else -> value
        } })
    })
    private fun field(key: String, value: JsonElement): JsonElement = when {
        value == JsonNull -> value
        key == "amount" -> JsonPrimitive(values.decimal(value.jsonPrimitive.content))
        key == "occurred_at" -> JsonPrimitive(values.timestamp(value.jsonPrimitive.content))
        key == "components" -> ordered(value.jsonArray.map { financial(it.jsonObject) })
        key == "component_id" -> values.part(value.jsonPrimitive.content)
        key == "ordinal" -> JsonPrimitive(CashImportPreviewInteger.parse(value.jsonPrimitive.content).toString())
        key in setOf("manual_fields", "standalone_manual_fields") -> ordered(value.jsonArray)
        else -> value
    }
    private fun financial(raw: JsonObject) = JsonObject(raw.mapValues { (key, value) -> field(key, value) })

    private fun participantFacts(): JsonObject {
        val allMembers = facts.cashEntryMembers.groupBy { it.entryId }
        val touchedEntries = collectParticipants(allMembers)
        val relevantRelations = facts.transactionRelations.filter { it.active &&
            (it.primaryTransactionId in participants || it.secondaryTransactionId in participants) }
        val relatedRelations = ordered(relevantRelations.map { relation(it.id) })
        val relatedIssues = facts.cashTransactionIssues.filter { it.transactionId in participants }
        val issueById = relatedIssues.associateBy { it.id }
        val issueCandidates = ordered(facts.cashIssueCandidates.filter { it.issueId in issueById }.map { item ->
            val issue = issueById.getValue(item.issueId)
            buildJsonObject { put("owner", parent(issue.transactionId)); put("issue", issue.issueKey); put("candidate", parent(item.candidateTransactionId)) }
        })
        val relationIds = relevantRelations.map { it.id }.toSet()
        val relationCandidates = ordered(facts.cashRelationCandidates.filter { it.relationId in relationIds }.map { item ->
            val data = fullConclusionJson.encodeToJsonElement(item).jsonObject
            JsonObject(data - setOf("id", "relation_id", "candidate_transaction_id", "candidate_component_id", "expected_row_revision") + mapOf(
                "relation" to relationKey(item.relationId), "candidate" to parent(item.candidateTransactionId),
                "component" to (item.candidateComponentId?.let(values::part) ?: JsonNull)))
        })
        val partOwners = facts.cashTransactionComponents.associate { it.id to it.cashTransactionId }
        val funding = ordered(facts.cashInvestmentFundingRelations.filter { partOwners[it.cashTransactionComponentId] in participants &&
            it.status == "accepted" && it.activeSlot == "active" }.map(::funding))
        val parents = ordered(facts.cashTransactions.filter { it.id in participants }.map { row ->
            val data = fullConclusionJson.encodeToJsonElement(row).jsonObject
            JsonObject(financial(JsonObject(data - setOf("id", "row_revision", "created_at"))) + ("parent" to parent(row.id)))
        })
        val components = ordered(facts.cashTransactionComponents.filter { it.cashTransactionId in participants }.map { part ->
            val data = fullConclusionJson.encodeToJsonElement(part).jsonObject
            JsonObject(financial(JsonObject(data - setOf("id", "cash_transaction_id", "created_at"))) + ("parent" to parent(part.cashTransactionId)))
        })
        val entries = ordered(facts.cashEntries.filter { it.id in touchedEntries }.map { entry ->
            val data = fullConclusionJson.encodeToJsonElement(entry).jsonObject
            JsonObject(financial(JsonObject(data.filterKeys { it !in setOf("id", "root_transaction_id", "identity_transaction_id", "revision") })) +
                ("members" to ordered(allMembers.getValue(entry.id).map { member ->
                    val body = fullConclusionJson.encodeToJsonElement(member).jsonObject
                    JsonObject(financial(JsonObject(body - setOf("entry_id", "transaction_id"))) + ("parent" to parent(member.transactionId)))
                })))
        })
        val issues = ordered(facts.cashTransactionIssues.filter { it.transactionId in participants }.map { issue ->
            val data = fullConclusionJson.encodeToJsonElement(issue).jsonObject
            JsonObject(data - setOf("id", "transaction_id", "revision", "created_at", "updated_at") + ("parent" to parent(issue.transactionId)))
        })
        return buildJsonObject {
            put("parents", parents); put("components", components); put("entries", entries); put("issues", issues)
            put("relations", relatedRelations); put("relation_candidates", relationCandidates); put("issue_candidates", issueCandidates); put("funding", funding)
        }
    }

    private fun collectParticipants(allMembers: Map<String, List<LedgerCashEntryMemberFactDto>>): Set<String> {
        val entryByParent = facts.cashEntryMembers.associate { it.transactionId to it.entryId }
        val incident = facts.transactionRelations.filter { it.active }.flatMap { edge ->
            listOfNotNull(edge.primaryTransactionId, edge.secondaryTransactionId, edge.anchorTransactionId).distinct().map { it to edge }
        }.groupBy({ it.first }, { it.second })
        val relationCandidates = facts.cashRelationCandidates.groupBy { it.relationId }
        val issues = facts.cashTransactionIssues.groupBy { it.transactionId }
        val issueCandidates = facts.cashIssueCandidates.groupBy { it.issueId }
        val expandedEntries = linkedSetOf<String>(); val expandedRelations = mutableSetOf<String>()
        val queue = participants.toMutableList()
        fun visit(parent: String) { if (participants.add(parent)) queue += parent }
        var index = 0
        while (index < queue.size) {
            val parent = queue[index++]
            entryByParent[parent]?.let { entry -> if (expandedEntries.add(entry)) for (member in allMembers.getValue(entry)) visit(member.transactionId) }
            for (edge in incident[parent].orEmpty()) if (expandedRelations.add(edge.id)) {
                visit(edge.primaryTransactionId); edge.secondaryTransactionId?.let(::visit); visit(edge.anchorTransactionId)
                for (candidate in relationCandidates[edge.id].orEmpty()) visit(candidate.candidateTransactionId)
            }
            for (issue in issues[parent].orEmpty()) for (candidate in issueCandidates[issue.id].orEmpty()) visit(candidate.candidateTransactionId)
        }
        return expandedEntries
    }

    private fun funding(item: LedgerInvestmentFundingFactDto): JsonObject {
        val data = fullConclusionJson.encodeToJsonElement(item).jsonObject
        return JsonObject(data - setOf("id", "cash_transaction_component_id", "created_at", "created_by", "decided_at", "decided_by") + mapOf(
            "component" to values.part(item.cashTransactionComponentId), "investment_event" to (item.investmentEvent?.let { event ->
                JsonObject(fullConclusionJson.encodeToJsonElement(event).jsonObject.mapValues { (key, value) ->
                    if (key in setOf("from_amount", "to_amount") && value != JsonNull) JsonPrimitive(values.decimal(value.jsonPrimitive.content)) else value
                })
            } ?: JsonNull)))
    }
}

private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
