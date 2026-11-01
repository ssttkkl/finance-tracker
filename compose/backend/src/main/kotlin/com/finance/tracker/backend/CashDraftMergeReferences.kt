package com.finance.tracker.backend

internal object CashDraftMergeReferences {
    private val endpoints = listOf("primary", "secondary", "anchor").flatMap { listOf(it + "_transaction_id", it + "_component_id") } + listOf("primary_role", "secondary_role")
    @Suppress("UNCHECKED_CAST") private fun map(value: Any?) = value as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") private fun maps(value: Any?) = value as List<Map<String, Any?>>
    private fun number(value: Any?) = value?.toString()?.toLong()
    private fun unused(prefix: String, id: Long, original: Any?, occupied: MutableSet<String>): String {
        for (attempt in 0 until 32) {
            val value = "$prefix:$id:" + sourceDigest(original, attempt).take(64)
            if (occupied.add(value)) return value
        }
        throw CashRelationFailure("relation_context_changed")
    }
    private fun deduplicate(rows: List<Map<String, Any?>>, fields: List<String>, target: Long, original: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val updates = rows.filter { it["delete"] == true }.toMutableList()
        val groups = rows.filter { it["delete"] != true }.groupBy { row -> fields.map { number(map(row["values"])[it]) } }
        val byId = original.associateBy { number(it["id"])!! }
        for (group in groups.values) {
            fun semantic(row: Map<String, Any?>) = map(row["values"]).mapValues { (key, value) ->
                if (key == "expected_row_revision" && number(map(row["values"])["candidate_transaction_id"]) == target) "stale_target" else value
            }
            if (group.any { !CashRecordPolicy.valuesEqual(semantic(it), semantic(group.first())) }) throw CashRelationFailure("relation_context_changed")
            val keeper = group.minWith(compareBy({ number(byId.getValue(number(it["id"])!!)["candidate_transaction_id"]) != target }, { number(it["id"])!! }))
            updates += keeper
            updates += group.filter { it !== keeper }.map { mapOf("id" to it["id"], "delete" to true) }
        }
        return updates.sortedBy { number(it["id"]) }
    }
    @Suppress("UNCHECKED_CAST") fun prepare(operation: MutableMap<String, Any?>) {
        val merge = operation["merge_draft"] as MutableMap<String, Any?>
        val source = number(merge["source_transaction_id"])!!; val target = number(merge["target_transaction_id"])!!
        val resolved = (operation["resolved_issue_ids"] as List<*>).map { number(it)!! }.toSet()
        val refs = map(merge["references"]); val componentMap = map(merge["component_map"])
        val targetParts = maps(map(operation["values"])["components"]).mapNotNull { number(it["component_id"]) }.toSet()
        val issues = maps(refs["issues"]); val issuesById = issues.associateBy { number(it["id"])!! }
        val targetIssues = issues.filter { number(it["transaction_id"]) == target }
        val occupied = targetIssues.map { it["issue_key"].toString() }.toMutableSet()
        val existingReasons = targetIssues.map { it["code"] to it["field_path"] }.toSet()
        for (issue in maps(operation["issues"])) if ((issue["code"] to issue["field_path"]) !in existingReasons)
            occupied += "import:" + sourceDigest(issue["code"], issue["field_path"])
        val moves = issues.filter { number(it["transaction_id"]) == source && number(it["id"]) !in resolved }.map { row ->
            val id = number(row["id"])!!; val original = row["issue_key"].toString()
            val key = if (!occupied.add(original)) unused("merge", id, original, occupied) else original
            var pending = CashRecordPolicy.copyValue(row["pending_fields"])
            if (pending is Map<*, *> && pending["components"] is List<*>) {
                val fields = map(pending).toMutableMap()
                fields["components"] = (pending["components"] as List<*>).map { part ->
                    if (part !is Map<*, *> || part["component_id"]?.toString() !in componentMap) part else {
                        val result = map(part).toMutableMap(); val mapped = componentMap[part["component_id"]?.toString()]
                        if (mapped == null) result.remove("component_id") else result["component_id"] = mapped
                        result
                    }
                }
                pending = fields
            }
            mapOf("id" to id, "issue_key" to key, "pending_fields" to pending)
        }
        val planned = maps(merge["relations"]).associateBy { number(it["id"])!! }
        val changed = maps(refs["relations"]).filter { row -> endpoints.any { !CashRecordPolicy.valuesEqual(row[it], planned.getValue(number(row["id"])!!)[it]) } }
            .map { number(it["id"])!! }.toSet()
        val issueCandidates = maps(refs["issue_candidates"]).mapNotNull { row ->
            var owner = number(issuesById.getValue(number(row["issue_id"])!!)["transaction_id"])!!
            if (owner == source && number(row["issue_id"]) in resolved) null else {
                if (owner == source) owner = target
                val values = listOf("issue_id", "candidate_transaction_id", "expected_row_revision").associateWith { number(row[it]) }.toMutableMap<String, Any?>()
                if (number(values["candidate_transaction_id"]) == source) { values["candidate_transaction_id"] = target; values["expected_row_revision"] = number(operation["expected_revision"]) }
                if (number(values["candidate_transaction_id"]) == owner) mapOf("id" to row["id"], "delete" to true) else mapOf("id" to row["id"], "values" to values)
            }
        }
        val relationCandidates = maps(refs["relation_candidates"]).map { row ->
            val values = row.filterKeys { it in setOf("relation_id", "candidate_transaction_id", "candidate_component_id", "expected_row_revision", "evidence", "validity") }
                .mapValues { (key, value) -> if (key in setOf("relation_id", "candidate_transaction_id", "candidate_component_id", "expected_row_revision")) number(value) else CashRecordPolicy.copyValue(value) }.toMutableMap()
            if (number(values["candidate_transaction_id"]) == source) {
                values["candidate_transaction_id"] = target; values["candidate_component_id"] = number(componentMap[values["candidate_component_id"]?.toString()])
                values["expected_row_revision"] = number(operation["expected_revision"]); values["validity"] = "stale"
            } else if (number(values["candidate_transaction_id"]) == target) {
                if (number(values["candidate_component_id"]) !in targetParts) values["candidate_component_id"] = null
                values["validity"] = "stale"
            }
            if (number(values["relation_id"]) in changed) values["validity"] = "stale"
            if (number(values["candidate_transaction_id"]) == number(planned.getValue(number(values["relation_id"])!!)["anchor_transaction_id"]))
                mapOf("id" to row["id"], "delete" to true) else mapOf("id" to row["id"], "values" to values)
        }
        merge["reference_moves"] = mapOf("issues" to moves,
            "issue_candidates" to deduplicate(issueCandidates, listOf("issue_id", "candidate_transaction_id"), target, maps(refs["issue_candidates"])),
            "relation_candidates" to deduplicate(relationCandidates, listOf("relation_id", "candidate_transaction_id", "candidate_component_id"), target, maps(refs["relation_candidates"])),
            "pending_issue_key" to if (map(merge["pending_fields"]).isNotEmpty()) unused("merge-fields", source, merge["pending_fields"], occupied) else null)
    }
}
