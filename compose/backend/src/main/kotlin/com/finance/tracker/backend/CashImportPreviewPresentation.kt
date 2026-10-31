package com.finance.tracker.backend

import java.math.BigInteger

/** 从冻结的最终图生成导入核对结果，不读取或修改财务数据。 */
internal object CashImportPreviewPresentation {
    private val importFields = mutableCashFields + financialCashFields
    private val memberFields = importFields + setOf("state", "record_id", "source_type")
    private val fileFields = setOf("file_ref", "display_name", "status", "recognized_row_count", "issues", "can_retry", "diagnostic")

    @Suppress("UNCHECKED_CAST")
    private fun maps(value: Any?): List<Map<String, Any?>> = (value as? List<Map<String, Any?>>).orEmpty()
    private fun parents(value: Map<String, Any?>): Set<String> = (value["parent_ids"] as List<*>).map { it.toString() }.toSet()
    private fun copy(value: Any?): Any? = CashRecordPolicy.copyValue(value)
    @Suppress("UNCHECKED_CAST")
    private fun copyMap(value: Map<String, Any?>): Map<String, Any?> = copy(value) as Map<String, Any?>
    private fun pending(code: String): Map<String, Any?> {
        val issue = cashImportIssue(code, blockingScope = "relation_group", afterImportAction = "open_home_item")
        return linkedMapOf("code" to issue.code, "message" to issue.message, "field_path" to issue.fieldPath,
            "blocking_scope" to issue.blockingScope, "next_action" to issue.nextAction,
            "after_import_action" to issue.afterImportAction)
    }

    private fun memberFields(value: Map<String, Any?>): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST") val result = copy(value.filterKeys { it in memberFields }) as MutableMap<String, Any?>
        for (part in maps(result["components"])) {
            val identifier = part["component_id"]?.toString()?.toBigIntegerOrNull()
            if (identifier != null && identifier >= BigInteger.ONE.shiftLeft(63)) {
                @Suppress("UNCHECKED_CAST") (part as MutableMap<String, Any?>).remove("component_id")
            }
        }
        return result
    }

    private fun targetSnapshot(snapshot: CashImportPreviewSnapshot): EntryTargetSnapshot {
        val context = snapshot.context
        val edges = snapshot.edges.mapKeys { it.key.toString() }.mapValues { cashImportEdgeMap(it.value) }
        val incident = linkedMapOf<String, MutableSet<String>>()
        for ((identifier, edge) in edges) for (parent in listOf(edge["primary_transaction_id"], edge["secondary_transaction_id"])) {
            if (parent != null) incident.getOrPut(parent.toString()) { linkedSetOf() } += identifier
        }
        return EntryTargetSnapshot(EntryTargetContext(context.relations.map(::cashImportEdgeMap),
            context.transactionRevisions.map { listOf(it.first, it.second) },
            context.entryVersions.map { listOf(it.transactionId, it.entryId, it.revision, it.digest) },
            context.relationRevisions.map { listOf(it.first, it.second) }), snapshot.parentOps, edges, incident)
    }

    fun present(files: List<ParsedImportFile>, snapshot: CashImportPreviewSnapshot, build: CashProjectionBuild,
        groups: List<Map<String, Any?>>, failures: List<Map<String, Any?>>, accounts: Map<String, Map<String, Any?>>,
        includeUnchanged: Boolean = false, entryParentIds: Set<String> = emptySet()): MutableMap<String, Any?> {
        val changed = snapshot.parentOps.filterValues { it["planned_action"] != "duplicate" }.keys
        val touched = (changed + entryParentIds).toMutableSet()
        if (includeUnchanged) touched += snapshot.parentOps.keys
        for (group in groups) {
            touched += parents(group)
            if (group["changed"] == true) touched += (group["old_parent_ids"] as? List<*>).orEmpty().map { it.toString() }
        }
        touched += affectedCashRelationClosure(changed, snapshot.context.relations.map { cashImportLink(it) },
            snapshot.edges.values.map { cashImportLink(it) })
        val projections = build.projections.filter { item -> item.memberIds.any { it.toString() in touched } &&
            (includeUnchanged || item.visible || item.confirmationStatus == "pending") }
            .sortedWith(compareByDescending<CashProjection> { it.sortAt }.thenByDescending { it.primaryRecord.id })
        val oldEdges = snapshot.context.relations.filter { it.active == true && it.state == "confirmed" }
        val revisions = snapshot.context.transactionRevisions.toMap()
        val targetSnapshot = targetSnapshot(snapshot)
        val targetIndex = CashImportEntryChoices.index(targetSnapshot, groups)
        val items = mutableListOf<Map<String, Any?>>()
        val details = linkedMapOf<String, Any?>()
        val detailValues = linkedMapOf<String, Any?>()
        val entryTargets = linkedMapOf<String, Any?>()
        fun ref(parent: String) = cashImportRef(snapshot, parent)
        fun member(parent: String, editable: List<String> = emptyList(), allowed: List<String> = emptyList()): Map<String, Any?> {
            val operation = snapshot.parentOps[parent]
            return ref(parent) + linkedMapOf("planned_action" to (operation?.get("planned_action") ?: "duplicate"),
                "fields_ref" to if (operation != null) mapOf("operation_ref" to operation["operation_ref"]) else mapOf("transaction_id" to parent),
                "editable_fields" to editable, "allowed_actions" to allowed,
                "row_revision" to if (operation != null) operation["expected_revision"] else revisions[parent.toLongOrNull()])
        }
        fun relationSummaries(option: Map<String, Any?>): List<Map<String, Any?>> = maps(option["edges"])
            .filter { it["active"] == true }.map { edge ->
                mapOf("kind" to edge["kind"], "subtype" to edge["subtype"], "member_refs" to
                    listOf(edge["primary_transaction_id"], edge["secondary_transaction_id"]).filter { it in snapshot.values }
                        .map { ref(it.toString()) })
            }
        for ((index, projection) in projections.withIndex()) {
            val identifier = "pi_${index + 1}"
            val parents = projection.memberIds.map { it.toString() }.toSet()
            val ordered = parents.sortedWith(cashRelationStringOrder)
            val relevant = groups.filter { parents.intersect(parents(it)).isNotEmpty() }
            val issues = parents.flatMap { parent -> listOf("issues", "display_retained_issues")
                .flatMap { maps(snapshot.parentOps[parent]?.get(it)) } }.toMutableList()
            if (relevant.any { it["action"] == "defer" }) issues += pending("relation_pending")
            issues += failures.filter { parents.intersect(parents(it)).isNotEmpty() }.map { pending(it["code"].toString()) }
            val root = projection.primaryRecord
            val public = snapshot.entryPublic[root.id.toString()]?.get("values") as? Map<*, *>
            val display = linkedMapOf<String, Any?>("occurred_at" to projection.occurredAt, "amount" to projection.netAmount,
                "currency" to root.currency, "counterparty" to root.counterparty, "note" to root.note,
                "category_id" to root.categoryId, "counterparty_account" to copy(public?.get("counterparty_account")),
                "counterparty_account_attrs" to copy(public?.get("counterparty_account_attrs")),
                "accounts" to ordered.flatMap { maps(snapshot.values.getValue(it)["components"]) }
                    .mapNotNull { part -> accounts[part["account_id"]]?.get("name") }.distinct())
            items += linkedMapOf("preview_item_id" to identifier,
                "confirmation_status" to if (issues.isNotEmpty() || projection.confirmationStatus == "pending") "pending" else "confirmed",
                "anticipated_state" to projection.state, "statistics_eligible" to projection.statisticsEligible,
                "display" to display, "member_refs" to ordered.map(::ref), "attention_reasons" to issues, "detail_available" to true)
            val members = ordered.map { parent ->
                val operation = snapshot.parentOps[parent]
                val value = snapshot.values.getValue(parent)
                if (operation == null) detailValues[parent] = memberFields(value)
                val editable = if (value["state"] == "draft" || operation != null && operation["transaction_id"] == null ||
                    value["record_origin"] == "manual" && parents.size == 1) {
                    (if (value["state"] == "posted") importFields - setOf("source_payload", "account_name") else importFields).sorted()
                } else emptyList()
                val allowed = if (operation == null) emptyList() else if (operation["transaction_id"] != null)
                    listOf("apply", "keep_existing", "defer") else listOf("apply", "defer")
                member(parent, editable, allowed)
            }
            val diffs = ordered.flatMap { parent -> maps(snapshot.parentOps[parent]?.get("field_diffs")).map { copyMap(it) + ref(parent) } }
            val effects = oldEdges.filter { it.primaryTransactionId in parents || it.secondaryTransactionId in parents }.map { edge ->
                val id = strictCashInteger(edge.id)!!
                mapOf("relation_id" to id.toString(), "kind" to edge.kind, "subtype" to edge.subtype,
                    "effect" to if (id in snapshot.removedRelations || snapshot.edges.getValue(id).active != true) "remove" else "keep",
                    "member_refs" to listOf(edge.primaryTransactionId, edge.secondaryTransactionId)
                        .filter { it in snapshot.values }.map { ref(it.toString()) })
            }
            val choices = relevant.map { group ->
                val choice = group.filterKeys { it in setOf("suggestion_id", "action", "candidate_id", "member_refs", "allowed_actions") }.toMutableMap()
                val first = maps(group["edges"]).first()
                choice["kind"] = first["kind"]; choice["subtype"] = first["subtype"]
                choice["relations"] = relationSummaries(group)
                choice["candidates"] = maps(group["candidates"]).map { candidate -> candidate + ("relations" to relationSummaries(candidate)) }
                for (key in listOf("common_field_conflicts", "common_fields", "inherit_common_fields", "relation_ids", "split_field_diffs"))
                    if (key in group) choice[key] = copy(group[key])
                choice
            }
            val relationParents = relevant.flatMap { group -> (listOf(group) + maps(group["candidates"])).flatMap { parents(it) } }.toSet()
            val relationMembers = relationParents.sortedWith(cashRelationStringOrder).map { parent ->
                if (parent !in snapshot.parentOps && parent !in detailValues) detailValues[parent] = memberFields(snapshot.values.getValue(parent))
                member(parent)
            }
            val fields = linkedMapOf<String, Any?>()
            public?.forEach { (key, value) -> fields[key.toString()] = copy(value) }
            fields.putAll(mapOf("note" to root.note, "counterparty" to root.counterparty, "category_id" to root.categoryId))
            val (editRef, target) = CashImportEntryChoices.target(targetSnapshot, parents, targetIndex)
            entryTargets[editRef] = target
            details[identifier] = linkedMapOf("entry" to linkedMapOf("fields" to fields,
                "editable_fields" to commonCashFields.sorted(), "edit_ref" to editRef), "members" to members,
                "relation_members" to relationMembers, "field_diffs" to diffs, "issues" to issues,
                "relation_choices" to choices, "relation_effects" to effects)
        }
        val counts = snapshot.operations.groupingBy { it["planned_action"].toString() }.eachCount().toMutableMap()
        counts["duplicate"] = snapshot.rows.count { it["planned_action"] in setOf("duplicate", "duplicate_in_batch") }
        counts["skip"] = snapshot.rows.count { it["planned_action"] == "skip" }
        for ((action, field) in listOf("accept" to "accepted", "defer" to "deferred", "ignore" to "ignored", "remove" to "removed"))
            counts["relations_$field"] = groups.count { it["action"] == action && it["changed"] != false }
        counts["relations_failed"] = failures.size
        val processing = snapshot.rows.filter { it["planned_action"] in setOf("skip", "duplicate", "duplicate_in_batch") }
            .map { copyMap(it) + (maps(it["issues"]).firstOrNull() ?: emptyMap()) }.toMutableList()
        processing += failures.map { failure -> linkedMapOf("planned_action" to "skip",
            "member_refs" to (failure["parent_ids"] as List<*>).filter { it in snapshot.values }.map { ref(it.toString()) }) + pending(failure["code"].toString()) }
        return linkedMapOf("items" to items, "item_details" to details, "detail_values" to detailValues,
            "entry_targets" to entryTargets, "processing_details" to processing,
            "file_results" to files.map { CashImportSessionView.fileView(it).filterKeys { key -> key in fileFields } },
            "summary" to counts, "can_commit" to files.any { it.status == "parsed" })
    }
}
