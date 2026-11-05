package com.finance.tracker.backend

/** 计划授权的公共编辑作用于完整预计条目；纯层，不写收支对象或投影。 */
internal data class EntryTargetContext(val relations: List<Map<String, Any?>>,
    val transactionRevisions: List<Any?>, val entryVersions: List<Any?>, val relationRevisions: List<Any?>)

internal data class EntryTargetSnapshot(val context: EntryTargetContext,
    val parentOps: Map<String, Map<String, Any?>>, val edges: Map<String, Map<String, Any?>>,
    val incident: Map<String, Set<String>>)

internal class EntryTargetIndex {
    val rows = linkedMapOf<String, Pair<Int, Any?>>()
    val entries = linkedMapOf<String, Pair<Int, Any?>>()
    val relations = linkedMapOf<String, Pair<Int, Any?>>()
    val oldEdges = linkedMapOf<String, MutableSet<String>>()
    val dependencies = linkedMapOf<String, LinkedHashMap<String, Pair<Int, Map<String, Any?>>>>()
}

internal object CashImportEntryChoices {
    fun index(snapshot: EntryTargetSnapshot, groups: List<Map<String, Any?>>): EntryTargetIndex {
        val index = EntryTargetIndex()
        for (edge in snapshot.context.relations) {
            for (parent in listOf(edge["primary_transaction_id"], edge["secondary_transaction_id"])) {
                if (parent != null) index.oldEdges.getOrPut(parent.toString()) { linkedSetOf() } += edge["id"].toString()
            }
        }
        for ((order, group) in groups.withIndex()) {
            if (group["action"] !in setOf("accept", "defer", "ignore")) continue
            val parents = group["parent_ids"] as? List<*> ?: continue
            for (parent in parents) {
                index.dependencies.getOrPut(parent.toString()) { linkedMapOf() }[group["suggestion_id"].toString()] =
                    order to linkedMapOf("suggestion_id" to group["suggestion_id"], "action" to group["action"])
            }
        }
        snapshot.context.transactionRevisions.forEachIndexed { order, item ->
            index.rows[listOf(item).firstValue()] = order to item
        }
        snapshot.context.entryVersions.forEachIndexed { order, item ->
            index.entries[listOf(item).firstValue()] = order to item
        }
        snapshot.context.relationRevisions.forEachIndexed { order, item ->
            index.relations[listOf(item).firstValue()] = order to item
        }
        return index
    }

    private fun List<Any?>.firstValue(): String {
        val item = firstOrNull()
        return when (item) {
            is List<*> -> item.firstOrNull().toString()
            is Pair<*, *> -> item.first.toString()
            else -> item.toString()
        }
    }

    fun target(snapshot: EntryTargetSnapshot, parents: Collection<String>, index: EntryTargetIndex): Pair<String, Map<String, Any?>> {
        val sorted = parents.sortedWith(cashRelationStringOrder)
        val oldEdges = sorted.flatMap { index.oldEdges[it].orEmpty() }.toSet()
        val dependencies = linkedMapOf<String, Pair<Int, Map<String, Any?>>>()
        for (parent in sorted) index.dependencies[parent]?.forEach { (key, value) -> dependencies[key] = value }
        fun selected(kind: Map<String, Pair<Int, Any?>>, keys: Set<String>): List<Any?> =
            keys.mapNotNull { kind[it] }.sortedBy { it.first }.map { it.second }
        val target = linkedMapOf<String, Any?>(
            "parent_ids" to sorted,
            "members" to sorted.map { parent ->
                snapshot.parentOps[parent]?.let { linkedMapOf("row_refs" to (it["row_refs"] as? List<*>).orEmpty()) }
                    ?: linkedMapOf("transaction_id" to parent)
            },
            "rows" to selected(index.rows, sorted.toSet()),
            "entries" to selected(index.entries, sorted.toSet()),
            "relations" to selected(index.relations, oldEdges),
            "relation_dependencies" to dependencies.values.sortedBy { it.first }.map { it.second },
            "final_edges" to (sorted.flatMap { snapshot.incident[it].orEmpty() }.toSet().sortedWith { first, second ->
                val a = first.toBigIntegerOrNull(); val b = second.toBigIntegerOrNull()
                if (a != null && b != null) a.compareTo(b) else cashRelationStringOrder.compare(first, second)
            }
                .map { snapshot.edges.getValue(it) }),
        )
        return "ie_" + cashImportDigest("cash-import-entry-v1", target) to target
    }

    fun apply(result: MutableMap<String, Any?>, choices: List<Any?>, previous: Map<String, Any?>?,
        categoryExists: (String) -> Boolean) {
        @Suppress("UNCHECKED_CAST")
        val targets = (result["entry_targets"] as? Map<String, Any?>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val previousTargets = (previous?.get("entry_targets") as? Map<String, Any?>).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val details = result["item_details"] as? MutableMap<String, Any?> ?: linkedMapOf()
        @Suppress("UNCHECKED_CAST")
        val items = (result["items"] as? List<Map<String, Any?>>).orEmpty()
        val itemsByEntry = linkedMapOf<String, Pair<Map<String, Any?>, MutableMap<String, Any?>>>()
        for (item in items) {
            @Suppress("UNCHECKED_CAST")
            val detail = details[item["preview_item_id"].toString()] as? MutableMap<String, Any?> ?: continue
            val entry = detail["entry"] as? Map<*, *> ?: continue
            itemsByEntry[entry["edit_ref"].toString()] = item to detail
        }
        val edits = mutableListOf<Map<String, Any?>>()
        for (raw in choices) {
            val choice = raw as? Map<*, *> ?: throw CashImportPlanError("plan_input_invalid")
            val reference = choice["edit_ref"]?.toString() ?: throw CashImportPlanError("plan_input_invalid")
            val target = targets[reference]
            if (target == null || cashImportDigest(target) != cashImportDigest(previousTargets[reference]))
                throw CashImportPlanError("plan_changed")
            val patch = linkedMapOf<String, Any?>()
            val patchRaw = choice["patch"] as? Map<*, *> ?: throw CashImportPlanError("plan_changed")
            try {
                for ((field, value) in patchRaw) patch[field.toString()] = CashRecordPolicy.normalize(field.toString(), value)
            } catch (_: CashPolicyError) { throw CashImportPlanError("plan_input_invalid") }
            catch (_: InvalidCashValue) { throw CashImportPlanError("plan_input_invalid") }
            catch (_: ArithmeticException) { throw CashImportPlanError("plan_input_invalid") }
            if ((patch.keys - commonCashFields).isNotEmpty()) throw CashImportPlanError("plan_input_invalid")
            val category = patch["category_id"]
            if (category != null && !categoryExists(category.toString())) throw CashImportPlanError("category_unavailable")
            val (item, detail) = itemsByEntry[reference] ?: throw CashImportPlanError("plan_input_invalid")
            @Suppress("UNCHECKED_CAST")
            val entry = detail["entry"] as MutableMap<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val fields = entry["fields"] as? MutableMap<String, Any?> ?: linkedMapOf()
            fields.putAll(CashRecordPolicy.copyValue(patch) as Map<String, Any?>)
            @Suppress("UNCHECKED_CAST")
            val display = item["display"] as? MutableMap<String, Any?>
            if (display != null) for ((field, value) in patch) if (field in display)
                display[field] = CashRecordPolicy.copyValue(value)
            if (patch.isNotEmpty()) edits += linkedMapOf("edit_ref" to reference, "target" to target, "patch" to patch)
        }
        result["entry_edits"] = edits
    }
}
