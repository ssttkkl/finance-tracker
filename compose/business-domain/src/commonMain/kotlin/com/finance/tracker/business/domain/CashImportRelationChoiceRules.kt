package com.finance.tracker.business.domain

data class CashImportRelationChoiceResult<G>(val invalidated: List<Map<String, Any?>>, val sources: Map<String, Map<String, Any?>>,
    val generations: Map<String, G>)

/** Frozen relation decisions and their anti-revival generations; no matching, graph application or writes. */
class CashImportRelationChoiceRules<G : Comparable<G>>(private val zero: G,
    private val generation: (Any?) -> G, private val increment: (G) -> G,
    private val wireGeneration: (G) -> Any?, private val copyValue: (Any?) -> Any?,
    private val digestValues: (List<Any?>) -> String) {
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?): List<Map<String, Any?>> = value as List<Map<String, Any?>>
    private fun copy(value: Any?): Any? = copyValue(value)
    private fun invalid(): Nothing = throw CashImportPlanError("plan_input_invalid")
    private fun digest(vararg values: Any?): String = digestValues(values.toList())

    fun apply(groups: List<MutableMap<String, Any?>>, choices: List<Map<String, Any?>>,
        previous: Map<String, Any?>?): CashImportRelationChoiceResult<G> {
        val old = maps(previous?.get("relation_groups") ?: emptyList<Map<String, Any?>>()).associateBy { it["suggestion_id"] as String }
        val stale = map(previous?.get("invalidated_relation_choices") ?: emptyMap<String, Any?>())
        val generations = map(previous?.get("relation_choice_generations") ?: emptyMap<String, Any?>())
            .mapValuesTo(linkedMapOf()) { generation(it.value) }
        fun identify(group: MutableMap<String, Any?>) {
            val source = group.getValue("source_suggestion_id") as String
            val version = generations[source] ?: zero
            group["choice_generation"] = wireGeneration(version)
            group["suggestion_id"] = if (version.compareTo(zero) != 0) "sg_" + digest(
                "cash-relation-choice-generation-v1", source, wireGeneration(version)).take(40) else source
        }
        for (group in groups) { group["source_suggestion_id"] = group.getValue("suggestion_id"); identify(group) }
        for (choice in choices) {
            val identifier = choice.getValue("suggestion_id") as String
            val before = old[identifier]
            if (before == null) {
                if (identifier !in stale || map(stale[identifier])["choice"] != choice) invalid()
            } else if (choice["candidate_id"] != null && maps(before.getValue("candidates")).none {
                it["candidate_id"] == choice["candidate_id"]
            }) invalid()
        }
        val current = groups.associateByTo(linkedMapOf()) { it["suggestion_id"] as String }
        val invalidated = mutableListOf<Map<String, Any?>>()
        val touched = mutableSetOf<Map<String, Any?>>()
        val sources = linkedMapOf<String, Map<String, Any?>>()
        val explicit = mutableSetOf<String>()
        fun invalidate(choice: Map<String, Any?>) {
            val identifier = choice["suggestion_id"] as String
            invalidated += linkedMapOf("kind" to "relation", "reference" to identifier, "code" to "relation_context_changed")
            val refs = maps(old[identifier]?.get("member_refs") ?: map(stale.getValue(identifier)).getValue("member_refs"))
            sources[identifier] = linkedMapOf("choice" to copy(choice), "member_refs" to copy(refs))
            touched.addAll(refs)
            old[identifier]?.let { before ->
                val source = (before["source_suggestion_id"] ?: identifier) as String
                generations[source] = maxOf(generations[source] ?: zero,
                    increment(generation(before["choice_generation"])))
            }
            current.remove(identifier)?.let { identify(it); current[it["suggestion_id"] as String] = it }
        }
        for (choice in choices) {
            val identifier = choice["suggestion_id"] as String
            val group = current[identifier]
            if (group == null) { invalidate(choice); continue }
            val action = choice.getValue("action") as String
            if (action !in (group.getValue("allowed_actions") as List<*>)) invalid()
            val candidateId = choice["candidate_id"] as? String
            if (!candidateId.isNullOrEmpty()) {
                val candidate = maps(group.getValue("candidates")).firstOrNull { it["candidate_id"] == candidateId }
                if (candidate == null) { invalidate(choice); continue }
                for (key in listOf("edges", "evidence", "parent_ids", "member_refs")) group[key] = copy(candidate.getValue(key))
                group["candidate_id"] = candidateId
            } else if (action == "accept" && group["origin"] == "current") {
                val confirmation = group["direct_confirmation"]?.let(::map) ?: invalid()
                for (key in listOf("edges", "evidence", "parent_ids", "member_refs")) group[key] = copy(confirmation.getValue(key))
            } else if (action == "accept" && maps(group["edges"]).any { it["secondary_transaction_id"] == null }) invalid()
            if ("common_fields" in choice) {
                if (action != "accept") invalid()
                group["common_fields"] = copy(choice["common_fields"])
            }
            if ("inherit_common_fields" in choice) {
                if (action != "remove") invalid()
                group["inherit_common_fields"] = choice["inherit_common_fields"]
            }
            group["action"] = action
            group["explicit_decision"] = true
            explicit += identifier
            if (group["origin"] == "current") group["changed"] = true
        }
        for (group in groups) if (group["origin"] != "current" && group["suggestion_id"] !in explicit &&
            maps(group["member_refs"]).any { it in touched }) group["action"] = "defer"
        return CashImportRelationChoiceResult(invalidated, sources, generations)
    }

}
