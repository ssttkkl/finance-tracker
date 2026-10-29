package com.finance.tracker.data

import com.finance.tracker.domain.*

/** Synthetic, process-local data for the real import task UI. */
class DemoCashImportLifecycleRepository(private val finance: DemoFinanceRepository) : CashImportLifecycleRepository {
    private var next = 0
    private val contexts = mutableMapOf<String, ImportContext>()
    private val plans = mutableMapOf<String, ImportPlan>()
    private val choices = mutableMapOf<String, ImportChoiceSet>()
    private val fields = mutableMapOf<String, Map<String, StructuredObject>>()
    private val receipts = mutableMapOf<String, ImportReceipt>()
    private val accountVersions = mutableMapOf<String, Long>()
    private val deletedAccounts = mutableMapOf<String, ImportAccount>()
    private val public = listOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")
    private fun fail(code: String, status: Int = 409): Nothing = throw DomainFailure(code, status, FailureCategory.RECOVERABLE)
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch {
        val old = context?.let { this.context(it.sessionId) }
        if (old != null && old.state != "open") fail("session_closed")
        if (files.any { it.file !is DemoStatementFileSource }) fail("demo_statement_only", 400)
        val id = old?.sessionId ?: "demo${++next}".padEnd(43, 'x')
        val kept = old?.files.orEmpty().filterNot { it.fileRef == replacingFileRef }
        val added = files.map { file -> ImportContextFile(replacingFileRef ?: "demo-file-${++next}", file.clientFileRef, file.file.name,
            "demo_bank", "ready", 2, emptyList(), false) }
        val groups = if (kept.isEmpty() && added.isEmpty()) emptyList() else listOf(ImportSourceAccount("demo-source", "demo_bank", "日常账户", listOf("CNY"), // i18n-allow: demo-fixture
            (kept.size + added.size) * 2, ImportAccountTarget("1", accountVersions["1"] ?: 1), "existing_account", null))
        val selected = old?.lastChoices?.accountSelections?.filter { selection -> groups.any { it.sourceAccountRef == selection.sourceAccountRef } }
            ?: groups.map { ImportAccountSelection(it.sourceAccountRef, it.suggestion!!) }
        val value = ImportContext(id, (old?.revision ?: 0) + 1, "2099-01-01T00:00:00Z", "open", kept + added,
            groups, ImportChoiceSet(accountSelections = selected), null, kept.isNotEmpty() || added.isNotEmpty(), null)
        contexts[id] = value; onContext(value)
        return ImportUploadBatch(value, emptyList(), null)
    }
    override suspend fun context(sessionId: String) = contexts[sessionId] ?: fail("session_not_found", 404)
    override suspend fun removeFile(sessionId: String, fileRef: String, revision: Long): ImportContext {
        val old = open(sessionId, revision); val remaining = old.files.filterNot { it.fileRef == fileRef }
        return old.copy(revision = revision + 1, files = remaining, sourceAccounts = if (remaining.isEmpty()) emptyList() else old.sourceAccounts,
            canPreview = remaining.isNotEmpty(), lastChoices = ImportChoiceSet(accountSelections = old.lastChoices?.accountSelections.orEmpty()), lastPlanId = null).also { contexts[sessionId] = it }
    }
    override suspend fun cancel(sessionId: String): ImportContext {
        val old = context(sessionId)
        if (old.state == "committed") fail("session_committed")
        return old.copy(state = "cancelled", canPreview = false).also { contexts[sessionId] = it }
    }
    private suspend fun open(id: String, revision: Long): ImportContext {
        val old = context(id)
        if (old.state != "open") fail("session_closed")
        if (old.revision != revision) fail("session_changed")
        return old
    }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan {
        val old = open(sessionId, revision)
        val target = choices.accountSelections.firstOrNull { it.sourceAccountRef == "demo-source" }?.target ?: ImportAccountTarget(null, null)
        val selectedAccount = target.accountId?.let { account(it) }
        if (selectedAccount != null && (!selectedAccount.active || selectedAccount.revision != target.revision)) fail("account_changed")
        val version = revision + 1
        val id = "ip_$sessionId." + (++next).toString().padEnd(16, 'x')
        val details = old.files.flatMap { file -> listOf(
            "${file.fileRef}-coffee" to mapOf("occurred_at" to "2026-10-09T04:30:00Z", "amount" to "-28.00", "counterparty" to "山海咖啡", "note" to "午后咖啡", "record_type" to "consumption", "category_id" to "food"), // i18n-allow: demo-fixture
            "${file.fileRef}-income" to mapOf("occurred_at" to "2026-10-10T01:00:00Z", "amount" to "12000.00", "counterparty" to "示例公司", "note" to "月度收入", "record_type" to "income", "category_id" to "income") // i18n-allow: demo-fixture
        ) }.associate { (row, source) ->
            val oldPatch = choices.rows.firstOrNull { it.rowRef == row }?.patch?.fields.orEmpty()
            val entryPatch = choices.entries.firstOrNull { it.editRef == "demo-edit-$row" }?.patch?.fields.orEmpty()
            if (entryPatch.keys.any { it !in public }) fail("field_not_editable", 422)
            row to StructuredObject(source.mapValues { StructuredText(it.value) } + mapOf("currency" to StructuredText("CNY"),
                "record_subtype" to StructuredText("not_applicable"), "account_id" to (selectedAccount?.accountId?.let(::StructuredText) ?: StructuredNull),
                "account_name" to (selectedAccount?.name?.let(::StructuredText) ?: StructuredNull), "counterparty_account" to StructuredText(""),
                "counterparty_account_attrs" to StructuredArray(emptyList()), "source_payload" to StructuredObject(source.mapValues { StructuredText(it.value) })) + oldPatch)
        }
        val items = details.map { (row, values) ->
            val entryPatch = choices.entries.firstOrNull { it.editRef == "demo-edit-$row" }?.patch?.fields.orEmpty()
            fun text(key: String) = ((if (key in entryPatch) entryPatch[key] else values.fields[key]) as? StructuredText)?.value
            val ready = demoCashPostingComplete(values, finance.demoAccounts())
            val issues = if (ready) emptyList() else listOf(ImportIssue("posting_incomplete", if (text("account_id") == null) "请选择对应账户。" else "请补齐收支信息。", null, "row", "edit")) // i18n-allow: demo-fixture
            ImportProjectedItem(row, if (ready) "confirmed" else "pending", if (ready) "posted" else "draft",
                ImportDisplay(text("occurred_at"), text("amount"), text("currency"), text("counterparty"), text("note"), listOfNotNull(text("account_name")), text("category_id")),
                ready, listOf(ImportMemberRef(row, null)), issues, true)
        }
        val pending = items.count { it.confirmationStatus == "pending" }.toLong()
        val plan = ImportPlan(id, sessionId, version, "2099-01-01T00:00:00Z", mapOf("create_posted" to items.size.toLong() - pending, "create_draft" to pending),
            mapOf("all" to items.size.toLong(), "pending" to pending, "confirmed" to items.size.toLong() - pending), true, items,
            old.files.map { ImportPlanFile(it.fileRef, it.displayName, it.status, it.recognizedRowCount, it.issues, null, it.canRetry) }, null, emptyList())
        plans[id] = plan; this.choices[id] = choices; fields[id] = details
        contexts[sessionId] = old.copy(revision = version, lastPlanId = id, lastChoices = choices)
        return plan
    }
    override suspend fun plan(planId: String) = plans[planId] ?: fail("plan_not_found", 404)
    override suspend fun items(planId: String, cursor: String?, limit: Int, confirmationStatus: String, startIndex: Int?) : ImportItemPage {
        val values = plan(planId).items.filter { confirmationStatus == "all" || it.confirmationStatus == confirmationStatus }
        val offset = startIndex ?: 0
        return ImportItemPage(values.drop(offset).take(limit), null, offset, values.size)
    }
    override suspend fun item(planId: String, itemId: String): ImportItemDetail {
        val item = plan(planId).items.firstOrNull { it.itemId == itemId } ?: fail("plan_item_not_found", 404)
        val values = fields[planId]?.get(itemId) ?: fail("plan_item_not_found", 404)
        return ImportItemDetail(itemId, listOf(ImportMemberDetail(itemId, null, if (item.anticipatedState == "draft") "create_draft" else "create_posted", values,
            listOf("account_id", "amount", "currency", "occurred_at", "record_type", "record_subtype"), listOf("apply", "defer"), null)),
            emptyList(), item.attentionReasons, emptyList(), emptyList(), ImportEntryFields(StructuredObject(values.fields.filterKeys { it in public } + choices[planId]?.entries?.firstOrNull { it.editRef == "demo-edit-$itemId" }?.patch?.fields.orEmpty()), public, "demo-edit-$itemId"))
    }
    override suspend fun processing(planId: String, cursor: String?, limit: Int) = ImportProcessingPage(emptyList(), null)
    override suspend fun confirm(planId: String, revision: Long, idempotencyKey: String): ImportReceipt {
        receipts[idempotencyKey]?.let { if (it.planId != planId) fail("idempotency_conflict"); return it }
        val plan = plan(planId); open(plan.sessionId, revision)
        if (contexts[plan.sessionId]?.lastPlanId != planId) fail("plan_changed")
        val pending = plan.itemCounts["pending"] ?: 0
        plan.items.forEach { item ->
            val transactionId = finance.insertImported(fields[planId]!![item.itemId]!!, item.anticipatedState)
            choices[planId]?.entries?.firstOrNull { it.editRef == "demo-edit-${item.itemId}" }?.let { finance.saveCommonFields(transactionId, it.patch.fields) }
        }
        val receipt = ImportReceipt("demo-import-receipt-${++next}", planId, "import", true, "2026-10-24T00:00:00Z", "completed",
            mapOf("created_posted" to plan.items.size.toLong() - pending, "saved_draft" to pending), emptyMap(), pending, pending, 1, null, null, null, null, ImportCommitOutcome(false, plan.items.isNotEmpty(), emptyList()))
        receipts[idempotencyKey] = receipt
        contexts[plan.sessionId] = contexts[plan.sessionId]!!.copy(state = "committed", receiptId = receipt.receiptId, canPreview = false)
        return receipt
    }
    override suspend fun receipt(receiptId: String) = receipts.values.firstOrNull { it.receiptId == receiptId } ?: fail("receipt_not_found", 404)
    override suspend fun receiptByKey(key: String) = receipts[key] ?: fail("receipt_not_found", 404)
    override suspend fun results(receiptId: String, kind: ImportResultKind, after: String, limit: Int) = ImportResultPage(emptyList(), null, emptyList(), 1)
    override suspend fun resolve(planId: String, idempotencyKey: String) = receipts[idempotencyKey]?.let {
        if (it.planId != planId) fail("idempotency_conflict"); ImportResolveResult("committed", it)
    } ?: ImportResolveResult("retry_original", null)
    override suspend fun accounts() = finance.demoAccounts().filter { it.type in setOf("cash", "loan", "lend") }.map { ImportAccountListItem(it.id.toString(), it.name, it.type, it.active, it.currencies) }
    override suspend fun account(accountId: String): ImportAccount {
        deletedAccounts[accountId]?.let { return it }
        val current = finance.demoAccounts().firstOrNull { it.id.toString() == accountId } ?: fail("account_not_found", 404)
        return ImportAccount(accountId, current.name, current.type, current.active, current.currencies.orEmpty(), accountVersions[accountId] ?: 1, false,
            mapOf("can_update" to true, "can_delete" to !finance.demoAccountInUse(accountId)), null, null)
    }
    private fun accountReceipt(key: String, kind: String, value: ImportAccount): ImportAccount {
        val committed = value.copy(receiptId = "demo-account-${++next}", committed = true)
        receipts[key] = ImportReceipt(committed.receiptId!!, null, kind, true, "2026-10-24T00:00:00Z", null,
            emptyMap(), emptyMap(), null, null, null, null, value.accountId, value.revision, committed)
        accountVersions[value.accountId] = value.revision
        return committed
    }
    override suspend fun createAccount(name: String, type: String, currencies: List<String>, idempotencyKey: String): ImportAccount {
        receipts[idempotencyKey]?.let { return it.account ?: fail("idempotency_conflict") }
        if (finance.demoAccounts().any { it.name == name }) fail("account_name_unavailable", 422)
        val created = finance.saveDemoAccount(null, name, type, true, currencies)
        return accountReceipt(idempotencyKey, "account_create", account(created.id.toString()))
    }
    override suspend fun updateAccount(accountId: String, revision: Long, patch: StructuredObject, idempotencyKey: String): ImportAccount {
        receipts[idempotencyKey]?.let { return it.account ?: fail("idempotency_conflict") }
        val current = account(accountId)
        if (current.revision != revision) fail("account_changed")
        fun text(key: String) = (patch.fields[key] as? StructuredText)?.value
        val name = text("name") ?: current.name
        if (finance.demoAccounts().any { it.id.toString() != accountId && it.name == name }) fail("account_name_unavailable", 422)
        finance.saveDemoAccount(accountId, name, current.type, (patch.fields["active"] as? StructuredBoolean)?.value ?: current.active,
            (patch.fields["currencies"] as? StructuredArray)?.values?.mapNotNull { (it as? StructuredText)?.value } ?: current.currencies)
        return accountReceipt(idempotencyKey, "account_update", account(accountId).copy(revision = revision + 1))
    }
    override suspend fun deleteAccount(accountId: String, revision: Long, idempotencyKey: String): ImportAccount {
        receipts[idempotencyKey]?.let { return it.account ?: fail("idempotency_conflict") }
        val current = account(accountId)
        if (current.revision != revision) fail("account_changed")
        if (current.active) fail("account_active")
        if (finance.demoAccountInUse(accountId)) fail("account_in_use")
        finance.deleteDemoAccount(accountId)
        val result = accountReceipt(idempotencyKey, "account_delete", current.copy(revision = revision + 1, deleted = true))
        deletedAccounts[accountId] = result
        return result
    }
}
