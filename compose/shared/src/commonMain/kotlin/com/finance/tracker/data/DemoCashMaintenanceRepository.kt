package com.finance.tracker.data

import com.finance.tracker.domain.*

/** 演示数据仅存在当前 Graph，走与真实页面相同的维护合同。 */
class DemoCashMaintenanceRepository(private val finance: DemoFinanceRepository) : CashMaintenanceRepository {
    override suspend fun currentGroup(projectionId: String): CashCurrentGroup {
        val evidence = finance.fetchEvidence(projectionId)
        return CashCurrentGroup(evidence.projectionVersion, evidence.members.map { CashCurrentMember(it.id, revisions[it.id] ?: 1, record(it.id).confirmationStatus) })
    }
    private val revisions = mutableMapOf<String, Long>()
    private val entryRevisions = mutableMapOf<String, Long>()
    private val common = setOf("note", "counterparty", "counterparty_account", "counterparty_account_attrs", "category_id")
    private val financial = setOf("amount", "currency", "account_id", "occurred_at")
    private val requests = mutableMapOf<String, CashMaintenanceRequest>()
    private val plans = mutableMapOf<String, CashMaintenancePlan>()
    private val receipts = mutableMapOf<String, ImportReceipt>()
    private var counter = 0
    override suspend fun sources(projectionId: String): CashSourceEvidence {
        val evidence = finance.fetchEvidence(projectionId)
        val current = evidence.members.associate { it.id to record(it.id) }
        return CashSourceEvidence(evidence.projectionVersion, evidence.projection, evidence.members.map { member ->
            fun value(key: String) = (current.getValue(member.id).fields.fields[key] as? StructuredText)?.value
            CashSourceMember(member.id, value("occurred_at"), value("amount"), value("currency"), member.counterparty, member.note, member.counterpartyAccount,
                member.account?.name ?: member.accountName, member.components.orEmpty().map { part -> CashSourceComponent(part.id, part.amount, part.currency, part.account?.name ?: part.accountName, "") },
                finance.importedFields[member.id]?.fields?.get("source_payload") as? StructuredObject, current.getValue(member.id).fields, current.getValue(member.id).relations)
        })
    }
    override suspend fun record(transactionId: String): CashCurrentRecord {
        val row = finance.fetchCashRecord(transactionId).record
        finance.importedFields[transactionId]?.let { source ->
            val fields = source
            val state = finance.importedStates[transactionId] ?: "posted"
            val draft = state == "draft"
            val pending = draft || finance.pendingRelations(transactionId)
            val editable = if (draft) listOf("occurred_at", "amount", "currency", "account_id", "record_type", "record_subtype") + common else common.toList()
            val revision = revisions[transactionId] ?: 1
            val version = finance.fetchCashPage(CashFilters()).projectionVersion
            val issues = if (!draft) emptyList() else listOf(CashRecordIssue("demo-account-$transactionId", revision, "posting_incomplete",
                if (fields.fields["account_id"] is StructuredText) "请核对后转为正式收支。" else "请选择对应账户。", "account_id", "row", "edit", emptyList(), StructuredObject(emptyMap()), null, emptyList())) // i18n-allow: demo-fixture
            return CashCurrentRecord("import", CashPublicEntry("demo-entry-$transactionId", entryRevisions[transactionId] ?: 1,
                StructuredObject(fields.fields.filterKeys { it in common } + finance.currentCommonFields(transactionId)), finance.currentCommonFields(transactionId).keys.toList(), common.toList(), false), transactionId,
                state, revision, if (pending) "pending" else "confirmed", fields, editable, issues, finance.savedRelations(transactionId),
                CashCurrentProjectionRef(transactionId, "projection-${finance.transferRoot(transactionId)}", if (pending) 1 else 0, version))
        }

        fun text(value: String) = StructuredText(value)
        val root = finance.transferRoot(transactionId)
        val isTransfer = finance.transferMembers(transactionId).size == 2
        val fields = StructuredObject(mapOf("occurred_at" to text(row.occurredAt), "amount" to text(row.amount), "currency" to text(row.currency),
            "counterparty" to text(row.counterparty), "counterparty_account" to text(row.counterpartyAccount), "note" to text(row.note),
            "account_id" to (row.accountId?.let { text(it.toString()) } ?: StructuredNull), "account_name" to text(row.accountName),
            "category_id" to (row.categoryId?.let(::text) ?: StructuredNull), "record_type" to text(row.recordType), "record_subtype" to text(row.recordSubtype)) +
            if (isTransfer) mapOf("manual_transfer_id" to text("demo-transfer-$root")) else emptyMap())
        val editable = if (isTransfer) (common + financial).toList() else listOf("occurred_at", "amount", "currency", "account_id", "counterparty", "counterparty_account", "counterparty_account_attrs", "note", "category_id", "record_type", "record_subtype")
        val revision = revisions[transactionId] ?: 1
        val projectionVersion = finance.fetchEvidence("projection-$transactionId").projectionVersion
        val entryFields = if (isTransfer) finance.fetchCashRecord(root).record.let { source -> mapOf(
            "note" to text(source.note), "counterparty" to text(source.counterparty), "counterparty_account" to text(source.counterpartyAccount),
            "counterparty_account_attrs" to StructuredArray(emptyList()), "category_id" to (source.categoryId?.let(::text) ?: StructuredNull)) } else fields.fields
        return CashCurrentRecord(if (isTransfer) "manual_transfer" else "manual", CashPublicEntry("demo-entry-$root", entryRevisions[root] ?: 1, StructuredObject(entryFields + finance.currentCommonFields(transactionId)), finance.currentCommonFields(transactionId).keys.toList(), editable, isTransfer),
            transactionId, "posted", revision, "confirmed", fields, editable, emptyList(), emptyList(), CashCurrentProjectionRef(transactionId, "projection-$root", 0, projectionVersion))
    }
    override suspend fun preview(input: CashMaintenanceRequest): CashMaintenancePlan {
        val current = record(input.transactionId)
        if (input.intent == "maintain_relations") return previewRelations(input, current)
        if (current.rowRevision != input.expectedRevision) failure("record_changed", 409)
        val draft = input.intent in setOf("save_draft", "finalize_draft")
        if (draft && current.state != "draft") failure("draft_required", 409)
        if (!draft && input.intent !in setOf("edit_entry", "edit_record")) failure("intent_invalid", 422)
        val editable = if (draft) current.editableFields else current.entry!!.editableFields
        if (input.patch.fields.keys.any { it !in editable }) failure("field_not_editable", 422)
        if (input.intent == "finalize_draft") validateFinalDraft(StructuredObject(current.fields.fields + input.patch.fields))
        if (input.intent == "edit_entry" && (input.entryId != current.entry!!.entryId || input.expectedEntryRevision != current.entry!!.revision)) failure("entry_changed", 409)
        if (current.recordOrigin == "manual_transfer" && input.patch.fields.keys.any { it !in common }) failure("field_not_editable", 422)
        val preparedMembers = if (input.memberPatches.isNotEmpty()) finance.prepareTransferMembers(input.transactionId, input.memberPatches) else emptyList()
        val old = input.previousPlanId?.let { plans[it] ?: failure("plan_changed", 409) }
        if (old != null && (old.revision != input.expectedPlanRevision || old.editContextId != input.editContextId)) failure("plan_changed", 409)
        val context = old?.editContextId ?: "demo${++counter}".padEnd(43, 'x')
        val id = "mp_$context." + counter.toString().padEnd(16, 'x')
        val proposed = preparedMembers.firstOrNull { it.id == current.transactionId }
        val proposedFields = proposed?.let { mapOf("amount" to StructuredText(it.amount), "currency" to StructuredText(it.currency),
            "occurred_at" to StructuredText(it.occurredAt), "account_name" to StructuredText(it.accountName)) }.orEmpty()
        val fields = StructuredObject(current.fields.fields + current.entry!!.fields.fields + proposedFields + input.patch.fields)
        fun value(key: String) = (fields.fields[key] as? StructuredText)?.value
        val state = if (input.intent == "save_draft") "draft" else "posted"
        val pending = if (state == "draft") 1L else 0L
        val action = if (draft) input.intent else "update_posted"
        val item = ImportProjectedItem("demo-item", if (pending == 1L) "pending" else "confirmed", state, ImportDisplay(value("occurred_at"), value("amount"), value("currency"),
            value("counterparty"), value("note"), listOfNotNull(value("account_name"))), pending == 0L,
            (preparedMembers.map { ImportMemberRef(null, it.id) }).ifEmpty { listOf(ImportMemberRef(null, current.transactionId)) }, emptyList(), true)
        val plan = CashMaintenancePlan(id, context, (old?.revision ?: 0) + 1, "2099-01-01T00:00:00Z", mapOf(action to 1L),
            mapOf("all" to 1L, "confirmed" to 1L - pending, "pending" to pending), true, listOf(item), null, emptyList(), emptyList())
        old?.let { plans.remove(it.planId); requests.remove(it.planId) }
        plans[id] = plan; requests[id] = input
        return plan
    }
    override suspend fun plan(planId: String) = plans[planId] ?: failure("plan_not_found", 404)
    override suspend fun currentPlan(contextId: String) = plans.values.firstOrNull { it.editContextId == contextId } ?: failure("plan_not_found", 404)
    override suspend fun items(planId: String, cursor: String?, confirmationStatus: String) = ImportItemPage(plan(planId).items.filter { confirmationStatus == "all" || it.confirmationStatus == confirmationStatus }, null)
    override suspend fun item(planId: String, itemId: String): ImportItemDetail {
        val input = requests[planId] ?: failure("plan_not_found", 404)
        if (input.intent == "maintain_relations") return relationDetails.getValue(planId)
        val current = record(input.transactionId)
        val prepared = if (input.memberPatches.isEmpty()) listOf(current) else finance.prepareTransferMembers(input.transactionId, input.memberPatches).map { row ->
            val source = record(row.id)
            source.copy(fields = StructuredObject(source.fields.fields + mapOf("amount" to StructuredText(row.amount), "currency" to StructuredText(row.currency),
                "account_id" to StructuredText(row.accountId.toString()), "account_name" to StructuredText(row.accountName), "occurred_at" to StructuredText(row.occurredAt),
                "record_type" to StructuredText(row.recordType), "record_subtype" to StructuredText(row.recordSubtype))))
        }
        return ImportItemDetail(itemId, prepared.map { member -> ImportMemberDetail(null, member.transactionId, if (input.intent in setOf("save_draft", "finalize_draft")) input.intent else "update_posted",
            StructuredObject(member.fields.fields + input.patch.fields), emptyList(), emptyList(), member.rowRevision) },
            emptyList(), emptyList(), emptyList(), emptyList(), ImportEntryFields(StructuredObject(current.entry!!.fields.fields + input.patch.fields), current.entry!!.editableFields))
    }
    override suspend fun commit(command: ImportFrozenCommand): ImportReceipt {
        receipts[command.idempotencyKey]?.let { if (it.planId != command.planId) failure("idempotency_conflict", 409); return it }
        val plan = plan(command.planId)
        if (plan.revision != command.revision) failure("plan_changed", 409)
        val input = requests[command.planId] ?: failure("plan_not_found", 404)
        val current = record(input.transactionId)
        if (current.rowRevision != input.expectedRevision) failure("record_changed", 409)
        if (input.intent == "edit_entry" && (input.entryId != current.entry!!.entryId || input.expectedEntryRevision != current.entry!!.revision)) failure("entry_changed", 409)
        if (input.intent == "maintain_relations") {
            return commitRelations(command, plan, input, current)
        }
        if (input.intent in setOf("save_draft", "finalize_draft")) {
            if (current.state != "draft") failure("draft_required", 409)
            val fields = StructuredObject(current.fields.fields + input.patch.fields)
            if (input.intent == "finalize_draft") validateFinalDraft(fields)
            finance.updateImported(current.transactionId, fields, if (input.intent == "finalize_draft") "posted" else "draft")
            revisions[current.transactionId] = current.rowRevision + 1
        } else {
            if (input.memberPatches.isNotEmpty()) {
                finance.saveTransferMembers(input.transactionId, input.memberPatches).forEach { id -> revisions[id] = (revisions[id] ?: 1) + 1 }
            }
            val fields = current.fields.fields + input.patch.fields.filterKeys { it !in common }
            fun value(key: String) = (fields[key] as? StructuredText)?.value.orEmpty()
            val account = finance.fetchCashAccounts().firstOrNull { it.id.toString() == value("account_id") }
            if (input.patch.fields.keys.any { it !in common }) finance.updateCashRecord(current.transactionId, CashRecordWrite(value("occurred_at"), value("amount"), value("currency"), value("counterparty"),
                value("counterparty_account"), value("note"), account?.name ?: value("account_name"), value("record_type"), value("record_subtype"), value("category_id").ifEmpty { null }))
            if (input.patch.fields.keys.any { it !in common }) revisions[current.transactionId] = current.rowRevision + 1
            finance.saveCommonFields(current.transactionId, input.patch.fields.filterKeys { it in common })
        }
        entryRevisions[finance.transferRoot(current.transactionId)] = current.entry!!.revision + 1
        val pending = if (input.intent == "save_draft") 1L else 0L
        val result = when (input.intent) { "save_draft" -> "saved_draft"; "finalize_draft" -> "finalized_draft"; else -> "updated_posted" }
        val receipt = ImportReceipt("demo-receipt-${++counter}", command.planId, "mutation", true, "2026-10-24T00:00:00Z", "completed",
            mapOf(result to 1L), emptyMap(), pending, pending, finance.fetchCashPage(CashFilters()).projectionVersion, current.transactionId, null, null, null)
        receipts[command.idempotencyKey] = receipt
        plans.remove(command.planId); requests.remove(command.planId)
        return receipt
    }
    override suspend fun resolve(command: ImportFrozenCommand) = receipts[command.idempotencyKey]?.let { ImportResolveResult("committed", it) }
        ?: ImportResolveResult(if (command.planId in plans) "retry_original" else "not_committed", null)
    override suspend fun receiptByKey(key: String) = receipts[key] ?: failure("receipt_not_found", 404)
    override suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String): CashDraftSaveResult {
        val receipt = receipts[key] ?: preview(CashMaintenanceRequest("save_draft", transactionId, revision, patch)).let {
            commit(ImportFrozenCommand(it.planId, it.revision, key))
        }
        if (receipt.retainedTransactionId != transactionId) failure("idempotency_conflict", 409)
        return CashDraftSaveResult(receipt, "available", record(transactionId))
    }
    private val relationDetails = mutableMapOf<String, ImportItemDetail>()
    override suspend fun associationTargets(transactionId: String, search: CashAssociationSearch): CashAssociationPage {
        val anchor = record(transactionId)
        if (anchor.recordOrigin != "import" || anchor.state != "posted") failure("choice_invalid", 422)
        if (search.amount != null && !isExactDecimalString(search.amount)) failure("invalid_filter", 400)
        val filtered = finance.auditRecords().filter { it.id != transactionId }.map { record(it.id) }.filter { target ->
            fun text(key: String) = (target.fields.fields[key] as? StructuredText)?.value
            (search.query.isBlank() || listOf("counterparty", "note", "account_name").any { text(it)?.contains(search.query, true) == true }) &&
                (search.accountId == null || text("account_id") == search.accountId) &&
                (search.amount == null || text("amount")?.let { compareDemoDecimals(it, search.amount) == 0 } == true) &&
                (search.dateFrom == null || text("occurred_at")?.let { com.finance.tracker.core.getLocalDateForInstant(it) >= search.dateFrom } == true) &&
                (search.dateTo == null || text("occurred_at")?.let { com.finance.tracker.core.getLocalDateForInstant(it) <= search.dateTo } == true)
        }.mapNotNull { target ->
            val reason = associationReason(anchor, target, search.kind ?: "payment_mirror")
            if (reason != null) null else target.copy(unavailableReason = null, componentChoices = listOf(
                CashAssociationComponentChoice("part-$transactionId", "part-${target.transactionId}")))
        }
        val offset = search.cursor?.toIntOrNull() ?: 0
        return CashAssociationPage(filtered.drop(offset).take(20), (offset + 20).takeIf { it < filtered.size }?.toString())
    }
    private fun associationReason(anchor: CashCurrentRecord, target: CashCurrentRecord, kind: String): String? {
        fun text(row: CashCurrentRecord, key: String) = (row.fields.fields[key] as? StructuredText)?.value
        val a = text(anchor, "amount"); val b = text(target, "amount")
        return when {
            kind !in setOf("payment_mirror", "refund_offset", "transfer_pair") -> "relation_role_incompatible"
            target.state != "posted" || a == null || b == null || text(target, "account_id") == null -> "relation_endpoint_incomplete"
            target.recordOrigin != "import" || finance.projectionMembers(target.transactionId).size > 1 -> "relation_endpoint_occupied"
            text(anchor, "currency") != text(target, "currency") -> "relation_currency_mismatch"
            kind == "payment_mirror" && text(anchor, "account_id") != text(target, "account_id") -> "relation_account_mismatch"
            kind == "refund_offset" && text(anchor, "account_id") != text(target, "account_id") -> "relation_account_mismatch"
            kind == "payment_mirror" && compareDemoDecimals(a, b) != 0 -> "relation_amount_invalid"
            kind in setOf("refund_offset", "transfer_pair") && (a.startsWith('-') == b.startsWith('-') || compareDemoDecimals(a.removePrefix("-"), b.removePrefix("-")) != 0) -> "relation_amount_invalid"
            else -> null
        }
    }
    private suspend fun previewRelations(input: CashMaintenanceRequest, current: CashCurrentRecord): CashMaintenancePlan {
        if (current.rowRevision != input.expectedRevision) failure("record_changed", 409)
        if (current.recordOrigin != "import" || current.state != "posted" || input.patch.fields.isNotEmpty() || input.memberPatches.isNotEmpty()) failure("choice_invalid", 422)
        val suggestion = finance.auditSuggestions[input.transactionId]
        val existing = finance.auditRelations.filter { it.active && it.state == "confirmed" && input.transactionId in it.members }
        val groups = listOfNotNull(suggestion) + existing.map { relation ->
            ImportPairOption(relation.id, relation.kind, "keep", null, emptyList(), relation.members.map { ImportMemberRef(null, it) },
                listOf("remove"), null, relationIds = listOf(relation.id), relations = listOf(ImportPairRelation(relation.kind, relation.members.map { ImportMemberRef(null, it) })))
        }
        if (input.relationChoices.map { it.suggestionId }.distinct().size != input.relationChoices.size) failure("choice_invalid", 422)
        val selected = input.relationChoices.map { decision ->
            val group = groups.singleOrNull { it.suggestionId == decision.suggestionId } ?: failure("choice_invalid", 422)
            if (decision.action !in group.allowedActions || decision.commonFields != null) failure("choice_invalid", 422)
            if (decision.action != "accept" && decision.candidateId != null) failure("choice_invalid", 422)
            val candidate = if (decision.action == "accept") group.candidates.singleOrNull { it.candidateId == decision.candidateId }
                ?: failure("choice_invalid", 422) else null
            candidate?.relations.orEmpty()
        }.flatten()
        input.relationAdditions.forEach { addition ->
            val target = record(addition.targetTransactionId)
            if (target.rowRevision != addition.expectedTargetRevision) failure("record_changed", 409)
            associationReason(current, target, addition.kind)?.let { failure(it, 422) }
            if (addition.anchorComponentId != "part-${input.transactionId}" || addition.targetComponentId != "part-${addition.targetTransactionId}" || addition.commonFields != null) failure("choice_invalid", 422)
        }
        val proposedRelations = selected + input.relationAdditions.map { ImportPairRelation(it.kind,
            listOf(ImportMemberRef(null, input.transactionId), ImportMemberRef(null, it.targetTransactionId))) }
        proposedRelations.forEach { relation ->
            val first = record(relation.members.first().transactionId!!)
            val second = record(relation.members.last().transactionId!!)
            associationReason(first, second, relation.kind)?.let { failure(it, 422) }
        }
        val members = (finance.projectionMembers(input.transactionId) + proposedRelations.flatMap { it.members.mapNotNull { ref -> ref.transactionId } }).distinct()
        val pending = suggestion != null && input.relationChoices.none { it.suggestionId == suggestion.suggestionId }
        val old = input.previousPlanId?.let { plan(it) }
        if (old != null && (old.revision != input.expectedPlanRevision || old.editContextId != input.editContextId)) failure("plan_changed", 409)
        val context = old?.editContextId ?: "demo-relations-${++counter}".padEnd(43, 'x')
        val id = "mp_$context." + (++counter).toString().padEnd(16, 'x')
        fun value(key: String) = (current.fields.fields[key] as? StructuredText)?.value
        val proposedRefunds = proposedRelations.filter { it.kind == "refund_offset" && it.members.first().transactionId == input.transactionId }
        val previewAmount = if (proposedRefunds.isNotEmpty()) {
            val remainder = allocationBalance(value("amount")!!, proposedRefunds.map { relation ->
                (record(relation.members.last().transactionId!!).fields.fields["amount"] as StructuredText).value.removePrefix("-") } + "0").difference
            if (compareDemoDecimals(remainder, "0") == 0) "0.00" else "-$remainder"
        } else value("amount")
        val item = ImportProjectedItem("demo-item", if (pending) "pending" else "confirmed", "posted",
            ImportDisplay(value("occurred_at"), previewAmount, value("currency"), value("counterparty"), value("note"), listOfNotNull(value("account_name"))),
            true, members.map { ImportMemberRef(null, it) }, emptyList(), true)
        val result = CashMaintenancePlan(id, context, (old?.revision ?: 0) + 1, "2099-01-01T00:00:00Z", mapOf("maintain_relations" to 1L),
            mapOf("all" to 1L, "pending" to if (pending) 1L else 0L, "confirmed" to if (pending) 0L else 1L),
            input.relationChoices.isNotEmpty() || input.relationAdditions.isNotEmpty(), listOf(item), null, emptyList(), emptyList())
        val details = members.map { memberId -> record(memberId).let { ImportMemberDetail(null, it.transactionId, "keep_existing", it.fields, emptyList(), emptyList(), it.rowRevision) } }
        relationDetails[id] = ImportItemDetail(item.itemId, details, emptyList(), emptyList(), groups.map { group ->
            input.relationChoices.firstOrNull { it.suggestionId == group.suggestionId }?.let { group.copy(action = it.action, candidateId = it.candidateId) } ?: group
        }, proposedRelations.mapIndexed { index, relation -> ImportPairEffect("proposed-$index", relation.kind, "confirm", relation.members, null) },
            ImportEntryFields(current.entry!!.fields, emptyList()))
        plans[id] = result; requests[id] = input
        return result
    }
    private suspend fun commitRelations(command: ImportFrozenCommand, plan: CashMaintenancePlan, input: CashMaintenanceRequest, current: CashCurrentRecord): ImportReceipt {
        if (!plan.canCommit) failure("choice_invalid", 422)
        // Revalidate every referenced revision before any process-local mutation.
        relationDetails.getValue(command.planId).members.forEach { if (record(it.transactionId!!).rowRevision != it.rowRevision) failure("record_changed", 409) }
        input.relationAdditions.forEach { associationReason(current, record(it.targetTransactionId), it.kind)?.let { failure(it, 422) } }
        input.relationChoices.forEach { decision ->
            if (decision.action == "remove") {
                finance.auditRelations.filter { it.id == decision.suggestionId && it.active }.forEach { it.active = false; it.revision++ }
            } else {
                val suggestion = finance.auditSuggestions.getValue(input.transactionId)
                finance.auditRelations.filter { it.suggestionId == decision.suggestionId }.forEach { it.active = false; it.revision++ }
                if (decision.action == "accept") suggestion.candidates.single { it.candidateId == decision.candidateId }.relations.forEachIndexed { index, relation ->
                    finance.auditRelations += DemoAuditRelation("accepted-${++counter}-$index", relation.kind, relation.members.map { it.transactionId!! }, null, "confirmed")
                }
                finance.auditSuggestions.remove(input.transactionId)
            }
        }
        input.relationAdditions.forEach { addition -> finance.auditRelations += DemoAuditRelation("added-${++counter}", addition.kind,
            listOf(input.transactionId, addition.targetTransactionId), null, "confirmed") }
        relationDetails.getValue(command.planId).members.forEach { revisions[it.transactionId!!] = it.rowRevision!! + 1 }
        finance.auditRelationsChanged()
        val receipt = ImportReceipt("demo-receipt-${++counter}", command.planId, "mutation", true, "2026-10-30T00:00:00Z", "completed",
            mapOf("updated_posted" to 1L), emptyMap(), 0, 0, finance.fetchCashPage(CashFilters()).projectionVersion, input.transactionId, null, null, null)
        receipts[command.idempotencyKey] = receipt
        plans.remove(command.planId); requests.remove(command.planId); relationDetails.remove(command.planId)
        return receipt
    }
    private fun validateFinalDraft(fields: StructuredObject) {
        if (!demoCashPostingComplete(fields, finance.demoAccounts())) failure("posting_incomplete", 422)
    }
    private fun failure(code: String, status: Int): Nothing = throw DomainFailure(code, status, FailureCategory.RECOVERABLE)
}
