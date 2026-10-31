package com.finance.tracker.data

import com.finance.tracker.domain.*
import com.finance.tracker.core.getLocalDateForInstant

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
    private val sourceRows = mutableMapOf<String, List<DemoImportRow>>()
    private val public = listOf("category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs")
    private fun fail(code: String, status: Int = 409): Nothing = throw DomainFailure(code, status, FailureCategory.RECOVERABLE)
    override suspend fun upload(files: List<ImportUpload>, context: ImportContext?, replacingFileRef: String?, onContext: suspend (ImportContext) -> Unit): ImportUploadBatch {
        val old = context?.let { this.context(it.sessionId) }
        if (old != null && old.state != "open") fail("session_closed")
        if (files.any { it.file !is DemoStatementFileSource }) fail("demo_statement_only", 400)
        val id = old?.sessionId ?: "demo${++next}".padEnd(43, 'x')
        val kept = old?.files.orEmpty().filterNot { it.fileRef == replacingFileRef }
        val added = files.map { file ->
            val ref = replacingFileRef ?: "demo-file-${++next}"
            val rows = (file.file as DemoStatementFileSource).rows
            sourceRows[ref] = rows
            ImportContextFile(ref, file.clientFileRef, file.file.name, "demo_bank", "ready", rows.size, emptyList(), false)
        }
        val groups = sourceGroups(kept + added)
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
        return old.copy(revision = revision + 1, files = remaining, sourceAccounts = sourceGroups(remaining),
            canPreview = remaining.isNotEmpty(), lastChoices = ImportChoiceSet(accountSelections = old.lastChoices?.accountSelections.orEmpty()), lastPlanId = null).also { contexts[sessionId] = it }
    }
    override suspend fun cancel(sessionId: String): ImportContext {
        val old = context(sessionId)
        if (old.state == "committed") fail("session_committed")
        return old.copy(state = "cancelled", canPreview = false).also { contexts[sessionId] = it }
    }
    private fun sourceGroups(files: List<ImportContextFile>) = files.flatMap { sourceRows.getValue(it.fileRef) }
        .groupBy { it.sourceRef }.map { (ref, rows) ->
            val first = rows.first()
            ImportSourceAccount(ref, first.sourceType, first.accountName, rows.mapNotNull { it.currency }.distinct(), rows.size,
                ImportAccountTarget(first.accountId, first.accountId?.let { accountVersions[it] ?: 1 }), "existing_account", null)
        }
    private suspend fun open(id: String, revision: Long): ImportContext {
        val old = context(id)
        if (old.state != "open") fail("session_closed")
        if (old.revision != revision) fail("session_changed")
        return old
    }
    override suspend fun preview(sessionId: String, revision: Long, previousPlanId: String?, choices: ImportChoiceSet): ImportPlan {
        val old = open(sessionId, revision)
        val version = revision + 1
        val id = "ip_$sessionId." + (++next).toString().padEnd(16, 'x')
        val details = old.files.flatMap { file -> sourceRows.getValue(file.fileRef).map { source ->
            "${file.fileRef}-${source.id}" to source
        } }.associate { (row, source) ->
            val target = choices.accountSelections.firstOrNull { it.sourceAccountRef == source.sourceRef }?.target ?: ImportAccountTarget(null, null)
            val selectedAccount = target.accountId?.let { account(it) }
            if (selectedAccount != null && (!selectedAccount.active || selectedAccount.revision != target.revision)) fail("account_changed")
            val oldPatch = choices.rows.firstOrNull { it.rowRef == row }?.patch?.fields.orEmpty()
            val entryPatch = choices.entries.firstOrNull { it.editRef == "demo-edit-$row" }?.patch?.fields.orEmpty()
            if (entryPatch.keys.any { it !in public }) fail("field_not_editable", 422)
            fun value(text: String?): StructuredValue = text?.let(::StructuredText) ?: StructuredNull
            val values = source.values.mapValues { value(it.value) } + mapOf("currency" to value(source.currency),
                "record_subtype" to value(source.values["record_subtype"] ?: "not_applicable"),
                "account_id" to value(selectedAccount?.accountId), "account_name" to value(selectedAccount?.name),
                "counterparty_account" to StructuredText(""), "counterparty_account_attrs" to StructuredArray(emptyList()))
            row to StructuredObject(values + ("source_payload" to StructuredObject(values + mapOf("source_type" to value(source.sourceType),
                "source_account" to value(source.accountName)))) + oldPatch)
        }
        val items = details.map { (row, values) ->
            val entryPatch = choices.entries.firstOrNull { it.editRef == "demo-edit-$row" }?.patch?.fields.orEmpty()
            fun text(key: String) = ((if (key in entryPatch) entryPatch[key] else values.fields[key]) as? StructuredText)?.value
            val ready = demoCashPostingComplete(values, finance.demoAccounts())
            val issues = if (ready) emptyList() else listOf(ImportIssue("posting_incomplete", if (text("account_id") == null) "请选择对应账户。" else "请补齐收支信息。", null, "row", "edit")) // i18n-allow: demo-fixture
            ImportProjectedItem(row, if (ready) "confirmed" else "pending", if (ready) "posted" else "draft",
                ImportDisplay(text("occurred_at"), text("amount"), text("currency"), text("counterparty"), text("note"), listOfNotNull(text("account_name")), text("category_id"),
                    recordType = text("record_type"), recordSubtype = text("record_subtype")),
                ready, listOf(ImportMemberRef(row, null)), issues, true)
        }.sortedByDescending { it.display.occurredAt.orEmpty() }
        val pending = items.count { it.confirmationStatus == "pending" }.toLong()
        val monthlySummaries = demoImportMonthlySummaries(items, details.toMap())
        val plan = ImportPlan(id, sessionId, version, "2099-01-01T00:00:00Z", mapOf("create_posted" to items.size.toLong() - pending, "create_draft" to pending),
            mapOf("all" to items.size.toLong(), "pending" to pending, "confirmed" to items.size.toLong() - pending), true, items,
            old.files.map { ImportPlanFile(it.fileRef, it.displayName, it.status, it.recognizedRowCount, it.issues, null, it.canRetry) }, null, emptyList(), monthlySummaries)
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

private fun demoImportMonthlySummaries(items: List<ImportProjectedItem>, details: Map<String, StructuredObject>): Map<String, List<CashMonthlySummary>> {
    val totals = mutableMapOf<Triple<String, String, String>, Pair<Long, Long>>()
    val months = mutableMapOf<String, MutableSet<String>>()
    for (item in items) {
        val instant = item.display.occurredAt ?: continue
        val month = getLocalDateForInstant(instant).take(7)
        if (month.length != 7) continue
        val statuses = listOf("all", item.confirmationStatus)
        statuses.forEach { months.getOrPut(it) { mutableSetOf() }.add(month) }
        if (!item.statisticsEligible) continue
        val currency = item.display.currency ?: continue
        val amount = item.display.amount ?: continue
        val type = (details[item.itemId]?.fields?.get("record_type") as? StructuredText)?.value
        val direction = when (type) {
            "consumption", "refund", "investment_out", "fee" -> "expense"
            "income", "investment_in", "interest" -> "income"
            else -> continue
        }
        val scaled = demoImportScaledAmount(amount)
        for (status in statuses) {
            val key = Triple(status, month, currency)
            val (income, expense) = totals[key] ?: (0L to 0L)
            totals[key] = if (direction == "income") (income + scaled) to expense else income to (expense + scaled)
        }
    }
    return months.mapValues { (status, monthSet) ->
        monthSet.sortedDescending().map { month ->
            CashMonthlySummary(month, totals.keys.filter { it.first == status && it.second == month }
                .map { it.third }.distinct().sorted().map { currency ->
                    val (income, expense) = totals.getValue(Triple(status, month, currency))
                    CashMonthlyCurrencySummary(currency, demoImportAmount(income), demoImportAmount(expense))
                })
        }
    }
}

private fun demoImportScaledAmount(value: String): Long {
    require(isExactDecimalString(value))
    val negative = value.startsWith('-')
    val unsigned = value.removePrefix("-").removePrefix("+")
    val fraction = unsigned.substringAfter('.', "")
    require(fraction.length <= 6)
    val scaled = unsigned.substringBefore('.').toLong() * 1_000_000 + fraction.padEnd(6, '0').toLong()
    return if (negative) -scaled else scaled
}

private fun demoImportAmount(value: Long): String {
    if (value == 0L) return "0"
    val unsigned = if (value < 0) -value else value
    val fraction = (unsigned % 1_000_000).toString().padStart(6, '0').trimEnd('0')
    return (if (value < 0) "-" else "") + (unsigned / 1_000_000) + (if (fraction.isEmpty()) "" else ".$fraction")
}
