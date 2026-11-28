package com.finance.tracker.business.domain

import com.finance.tracker.business.contracts.*
import kotlinx.serialization.json.*

/** Transient calculation input. It neither replaces the original confirmation nor authorizes a submission. */
class LocalCashImportReboundInput internal constructor(internal val owner: LocalCashImportReview,
    internal val workspace: String, internal val sequence: String, internal val choices: JsonObject,
    previous: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST") private val previous = copyLocalCashValue(previous) as Map<String, Any?>
    fun inputJson(): JsonObject = Json.parseToJsonElement(choices.toString()).jsonObject
    fun input(): Map<String, Any?> = prepareLocalCashImportInput(choices["account_selections"], choices["row_choices"], choices["relation_choices"], choices["entry_choices"])
    @Suppress("UNCHECKED_CAST") fun previousPlan(): Map<String, Any?> = copyLocalCashValue(previous) as Map<String, Any?>
}

internal fun freezeLocalCashImportChoiceBindings(source: LedgerSnapshotDto, plan: JsonObject, changes: CashWriteSetDto,
    input: JsonObject, context: JsonObject, trustedPlan: Boolean = false): JsonObject {
    val noChoices = trustedPlan && input.getValue("relation_choices").jsonArray.isEmpty() &&
        input.getValue("entry_choices").jsonArray.isEmpty()
    val keys = if (noChoices) JsonObject(emptyMap()) else localCashImportChoiceKeys(source, plan, changes)
    val groups = context.objects("relation_groups").map { it.text("suggestion_id") }.toSet()
    val stale = context.getValue("invalidated_relation_choices").jsonObject
    val relations = linkedMapOf<String, JsonElement>()
    for (choice in input.objects("relation_choices")) {
        val id = choice.text("suggestion_id")!!
        if (id !in groups) { if (id !in stale) changed(); continue }
        val target = keys.getValue("relations").jsonObject.getValue(id).jsonObject
        relations[id] = buildJsonObject {
            put("group", target.getValue("group"))
            put("candidate", choice.text("candidate_id")?.let { target.getValue("candidates").jsonObject[it] ?: changed() } ?: JsonNull)
        }
    }
    return buildJsonObject {
        put("workspace", source.workspaceId); put("rules", CASH_RULES_VERSION); put("relations", JsonObject(relations))
        put("entries", JsonObject(input.objects("entry_choices").associate { choice ->
            val id = choice.text("edit_ref")!!
            id to keys.getValue("entries").jsonObject.getValue(id)
        }))
    }
}

internal fun validateLocalCashImportChoiceBindings(value: JsonObject, input: JsonObject, context: JsonObject) {
    fun valid(condition: Boolean) { require(condition) { "cash_import_review_invalid" } }
    fun hash(item: JsonElement?) = item is JsonPrimitive && item.isString && item.content.length == 64 &&
        item.content.all { it in 'a'..'f' || it in '0'..'9' }
    valid(value.keys == setOf("workspace", "rules", "relations", "entries"))
    for (key in listOf("workspace", "rules")) valid((value[key] as? JsonPrimitive)?.let { it.isString && it.content.isNotEmpty() } == true)
    val groups = context.objects("relation_groups").map { it.text("suggestion_id") }.toSet()
    val choices = input.objects("relation_choices").associateBy { it.text("suggestion_id")!! }.filterKeys { it in groups }
    val relations = value.getValue("relations").jsonObject
    valid(relations.keys == choices.keys)
    for ((id, raw) in relations) {
        val target = raw.jsonObject
        valid(target.keys == setOf("group", "candidate") && hash(target["group"]))
        valid(if (choices.getValue(id).text("candidate_id") == null) target["candidate"] == JsonNull else hash(target["candidate"]))
    }
    val entries = value.getValue("entries").jsonObject
    valid(entries.keys == input.objects("entry_choices").map { it.text("edit_ref")!! }.toSet() && entries.values.all(::hash))
}

/** Locate original active decisions in fresh, undecided groups. Invalidated choices and their generations stay retained. */
fun LocalCashImportReview.rebindRelations(source: LedgerSnapshotDto, plan: Map<String, Any?>, changes: CashWriteSetDto): LocalCashImportReboundInput {
    val document = Json.parseToJsonElement(encode()).jsonObject
    val bindings = document["bindings"]?.jsonObject ?: changed()
    if (bindings.text("workspace") != source.workspaceId || bindings.text("rules") != CASH_RULES_VERSION || source.rulesVersion != CASH_RULES_VERSION) changed()
    val input = document.getValue("input").jsonObject
    val context = document.getValue("context").jsonObject
    val wire = freezeLocalCashImportPreviewPlan(plan)
    val keys = localCashImportChoiceKeys(source, wire, changes).getValue("relations").jsonObject
    val byGroup = keys.entries.groupBy { it.value.jsonObject.getValue("group") }
    val current = wire.objects("relation_groups").associateBy { it.text("suggestion_id")!! }
    val old = context.objects("relation_groups").associateBy { it.text("suggestion_id")!! }
    val history = context.getValue("relation_choice_generations").jsonObject.toMutableMap()
    for ((key, value) in wire.getValue("relation_choice_generations").jsonObject) {
        if (generation(value) > generation(history[key])) history[key] = value
    }
    val generations = history.toMutableMap()
    val matched = linkedMapOf<String, Pair<String, String?>>()
    val used = mutableSetOf<String>()
    for (choice in input.objects("relation_choices")) {
        val id = choice.text("suggestion_id")!!
        val before = old[id] ?: continue
        val target = bindings.getValue("relations").jsonObject.getValue(id).jsonObject
        val selected = byGroup[target.getValue("group")]?.singleOrNull() ?: changed()
        if (!used.add(selected.key)) changed()
        val candidate = target.getValue("candidate").takeIf { it != JsonNull }?.let { expected ->
            selected.value.jsonObject.getValue("candidates").jsonObject.entries.filter { it.value == expected }.singleOrNull()?.key ?: changed()
        }
        val group = current.getValue(selected.key)
        val base = group.text("source_suggestion_id") ?: selected.key
        val oldGeneration = before.getValue("choice_generation")
        if (generation(history[base]) > generation(oldGeneration) ||
            generation(history[before.text("source_suggestion_id")]) > generation(oldGeneration) ||
            generation(group["choice_generation"]) > generation(oldGeneration)) changed()
        generations[base] = oldGeneration
        matched[id] = base to candidate
    }
    val rawGroups = wire.objects("relation_groups").map { raw ->
        JsonObject(raw + ("suggestion_id" to (raw["source_suggestion_id"] ?: raw.getValue("suggestion_id"))))
    }
    val identified = applyLocalCashImportRelationChoices(JsonArray(rawGroups), JsonArray(emptyList()),
        JsonObject(context + ("relation_choice_generations" to JsonObject(generations))))
    val groups = identified.objects("groups")
    val bySource = groups.groupBy { it.text("source_suggestion_id")!! }
    val nextChoices = input.objects("relation_choices").map { choice ->
        val match = matched[choice.text("suggestion_id")] ?: return@map choice
        val group = bySource[match.first]?.singleOrNull() ?: changed()
        JsonObject(choice + ("suggestion_id" to group.getValue("suggestion_id")) +
            (match.second?.let { mapOf("candidate_id" to JsonPrimitive(it)) } ?: emptyMap()))
    }
    val nextInput = JsonObject(input + mapOf("relation_choices" to JsonArray(nextChoices), "entry_choices" to JsonArray(emptyList())))
    val nextContext = localCashImportChoiceContextFromPlan(JsonObject(context + mapOf(
        "relation_groups" to identified.getValue("groups"), "relation_choice_generations" to identified.getValue("generations"))))
    @Suppress("UNCHECKED_CAST") val previous = decodeLocalCashValue(nextContext) as Map<String, Any?>
    return LocalCashImportReboundInput(this, source.workspaceId, source.ledgerSeq, nextInput, previous + ("entry_targets" to emptyMap<String, Any?>()))
}

/** Bind final edits after the restored relation choices have run through the original graph and public-field rules. */
fun LocalCashImportReview.rebindEntries(source: LedgerSnapshotDto, plan: Map<String, Any?>, changes: CashWriteSetDto,
    relations: LocalCashImportReboundInput): LocalCashImportReboundInput {
    if (relations.owner !== this || relations.workspace != source.workspaceId || relations.sequence != source.ledgerSeq || source.rulesVersion != CASH_RULES_VERSION) changed()
    val document = Json.parseToJsonElement(encode()).jsonObject
    val bindings = document.getValue("bindings").jsonObject
    val originalChoices = document.getValue("input").jsonObject.objects("entry_choices")
    if (originalChoices.isEmpty()) return relations
    val keys = localCashImportChoiceKeys(source, freezeLocalCashImportPreviewPlan(plan), changes).getValue("entries").jsonObject
    val byMembers = keys.entries.groupBy { it.value }
    val targets = plan.getValue("entry_targets") as Map<*, *>
    val selectedTargets = linkedMapOf<String, Any?>()
    val choices = originalChoices.map { choice ->
        val expected = bindings.getValue("entries").jsonObject.getValue(choice.text("edit_ref")!!)
        val current = byMembers[expected]?.singleOrNull()?.key ?: changed()
        if (current in selectedTargets) changed()
        selectedTargets[current] = checkNotNull(targets[current])
        JsonObject(choice + ("edit_ref" to JsonPrimitive(current)))
    }
    val input = JsonObject(relations.choices + ("entry_choices" to JsonArray(choices)))
    return LocalCashImportReboundInput(this, source.workspaceId, source.ledgerSeq, input,
        relations.previousPlan() + ("entry_targets" to selectedTargets))
}

private fun changed(): Nothing = throw CashImportPlanError("plan_changed")
private fun generation(value: JsonElement?) = parseLocalCashIntegerText((value ?: JsonPrimitive(0)).jsonPrimitive.content)
private fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
private fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
