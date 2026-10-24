package com.finance.tracker.data

import com.finance.tracker.domain.*

/** 演示数据仅存在当前 Graph，走与真实页面相同的维护合同。 */
class DemoCashMaintenanceRepository(private val finance: DemoFinanceRepository) : CashMaintenanceRepository {
    override suspend fun currentGroup(projectionId: String): CashCurrentGroup {
        val evidence = finance.fetchEvidence(projectionId)
        return CashCurrentGroup(evidence.projectionVersion, evidence.members.map { CashCurrentMember(it.id, revisions[it.id] ?: 1, "confirmed") })
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
        return CashSourceEvidence(evidence.projectionVersion, evidence.projection, evidence.members.map { member ->
            CashSourceMember(member.id, member.occurredAt, member.amount, member.currency, member.counterparty, member.note, member.counterpartyAccount,
                member.account?.name ?: member.accountName, member.components.orEmpty().map { part -> CashSourceComponent(part.id, part.amount, part.currency, part.account?.name ?: part.accountName, "") })
        })
    }
    override suspend fun record(transactionId: String): CashCurrentRecord {
        val row = finance.fetchCashRecord(transactionId).record
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
        if (current.rowRevision != input.expectedRevision) failure("record_changed", 409)
        if (input.intent !in setOf("edit_entry", "edit_record")) failure("posted_required", 409)
        if (input.patch.fields.keys.any { it !in current.entry!!.editableFields }) failure("field_not_editable", 422)
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
        val item = ImportProjectedItem("demo-item", "confirmed", "posted", ImportDisplay(value("occurred_at"), value("amount"), value("currency"),
            value("counterparty"), value("note"), listOfNotNull(value("account_name"))), true,
            (preparedMembers.map { ImportMemberRef(null, it.id) }).ifEmpty { listOf(ImportMemberRef(null, current.transactionId)) }, emptyList(), true)
        val plan = CashMaintenancePlan(id, context, (old?.revision ?: 0) + 1, "2099-01-01T00:00:00Z", mapOf("update_posted" to 1L),
            mapOf("all" to 1L, "confirmed" to 1L, "pending" to 0L), true, listOf(item), null, emptyList(), emptyList())
        old?.let { plans.remove(it.planId); requests.remove(it.planId) }
        plans[id] = plan; requests[id] = input
        return plan
    }
    override suspend fun plan(planId: String) = plans[planId] ?: failure("plan_not_found", 404)
    override suspend fun currentPlan(contextId: String) = plans.values.firstOrNull { it.editContextId == contextId } ?: failure("plan_not_found", 404)
    override suspend fun items(planId: String, cursor: String?, confirmationStatus: String) = ImportItemPage(plan(planId).items.filter { confirmationStatus == "all" || it.confirmationStatus == confirmationStatus }, null)
    override suspend fun item(planId: String, itemId: String): ImportItemDetail {
        val input = requests[planId] ?: failure("plan_not_found", 404)
        val current = record(input.transactionId)
        val prepared = if (input.memberPatches.isEmpty()) listOf(current) else finance.prepareTransferMembers(input.transactionId, input.memberPatches).map { row ->
            val source = record(row.id)
            source.copy(fields = StructuredObject(source.fields.fields + mapOf("amount" to StructuredText(row.amount), "currency" to StructuredText(row.currency),
                "account_id" to StructuredText(row.accountId.toString()), "account_name" to StructuredText(row.accountName), "occurred_at" to StructuredText(row.occurredAt),
                "record_type" to StructuredText(row.recordType), "record_subtype" to StructuredText(row.recordSubtype))))
        }
        return ImportItemDetail(itemId, prepared.map { member -> ImportMemberDetail(null, member.transactionId, "update_posted",
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
        entryRevisions[finance.transferRoot(current.transactionId)] = current.entry!!.revision + 1
        val receipt = ImportReceipt("demo-receipt-${++counter}", command.planId, "mutation", true, "2026-10-24T00:00:00Z", "completed",
            mapOf("updated_posted" to 1L), emptyMap(), 0, 0, null, null, null, current.transactionId, null, null, null)
        receipts[command.idempotencyKey] = receipt
        plans.remove(command.planId); requests.remove(command.planId)
        return receipt
    }
    override suspend fun resolve(command: ImportFrozenCommand) = receipts[command.idempotencyKey]?.let { ImportResolveResult("committed", it) }
        ?: ImportResolveResult(if (command.planId in plans) "retry_original" else "not_committed", null)
    override suspend fun receiptByKey(key: String) = receipts[key] ?: failure("receipt_not_found", 404)
    override suspend fun saveDraft(transactionId: String, revision: Long, patch: StructuredObject, key: String): CashDraftSaveResult = failure("draft_required", 409)
    private fun failure(code: String, status: Int): Nothing = throw DomainFailure(code, status, FailureCategory.RECOVERABLE)
}
